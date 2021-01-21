package net.adoptopenjdk.api.v3

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.adoptopenjdk.api.v3.ai.AppInsightsTelemetry
import net.adoptopenjdk.api.v3.dataSources.APIDataStore
import net.adoptopenjdk.api.v3.dataSources.ReleaseVersionResolver
import net.adoptopenjdk.api.v3.dataSources.UpdaterJsonMapper
import net.adoptopenjdk.api.v3.dataSources.models.AdoptRepos
import net.adoptopenjdk.api.v3.dataSources.persitence.ApiPersistence
import net.adoptopenjdk.api.v3.models.Variants
import net.adoptopenjdk.api.v3.stats.StatsInterface
import org.slf4j.LoggerFactory
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.concurrent.timerTask

class V3Updater @Inject constructor(
    private val adoptReposBuilder: AdoptReposBuilder,
    private val apiDataStore: APIDataStore,
    private val database: ApiPersistence,
    private val statsInterface: StatsInterface,
    private val releaseVersionResolver: ReleaseVersionResolver
) {

    private val variants: Variants
    private val mutex = Mutex()

    companion object {
        @JvmStatic
        private val LOGGER = LoggerFactory.getLogger(this::class.java)

        fun calculateChecksum(repo: AdoptRepos): String {
            val md = MessageDigest.getInstance("MD5")
            val outputStream = object : OutputStream() {
                override fun write(b: Int) {
                    md.update(b.toByte())
                }
            }
            UpdaterJsonMapper.mapper.writeValue(outputStream, repo)

            return String(Base64.getEncoder().encode(md.digest()))
        }
    }

    init {
        AppInsightsTelemetry.start()
        val variantData = this.javaClass.getResource("/JSON/variants.json").readText()
        variants = UpdaterJsonMapper.mapper.readValue(variantData, Variants::class.java)
    }

    fun incrementalUpdate(oldRepo: AdoptRepos): AdoptRepos? {
        return runBlocking {
            // Must catch errors or may kill the scheduler
            try {
                LOGGER.info("Starting Incremental update")
                val updatedRepo = adoptReposBuilder.incrementalUpdate(oldRepo)

                return@runBlocking if (updatedRepo != oldRepo) {
                    writeIncrementalUpdate(updatedRepo, oldRepo)
                } else {
                    null
                }
            } catch (e: Exception) {
                LOGGER.error("Failed to perform incremental update", e)
            }
            return@runBlocking null
        }
    }

    private suspend fun writeIncrementalUpdate(updatedRepo: AdoptRepos, oldRepo: AdoptRepos): AdoptRepos? {
        val checksum = calculateChecksum(updatedRepo)
        val oldChecksum = calculateChecksum(oldRepo)

        if (checksum == oldChecksum) {
            return updatedRepo
        }

        return mutex.withLock {
            // Ensure that the database has not been updated since calculating the incremental update
            if (database.getUpdatedAt().checksum == oldChecksum) {
                database.updateAllRepos(updatedRepo, checksum)
                database.setReleaseInfo(releaseVersionResolver.formReleaseInfo(updatedRepo))

                LOGGER.info("Incremental update done")
                LOGGER.info("Saved version: $checksum ${updatedRepo.hashCode()}")
                return@withLock updatedRepo
            } else {
                LOGGER.info("Incremental update done")
                LOGGER.warn("Not applying incremental update due to checksum miss match")
                return@withLock null
            }
        }
    }

    fun run(instantFullUpdate: Boolean) {
        val executor = Executors.newScheduledThreadPool(2)

        val delay = if (instantFullUpdate) 0L else 1L

        var repo: AdoptRepos = try {
            apiDataStore.loadDataFromDb(true)
        } catch (e: java.lang.Exception) {
            LOGGER.error("Failed to load db", e)
            AdoptRepos(emptyList())
        }

        executor.scheduleWithFixedDelay(
            timerTask {
                repo = fullUpdate() ?: repo
            },
            delay, 1, TimeUnit.DAYS
        )

        executor.scheduleWithFixedDelay(
            timerTask {
                repo = incrementalUpdate(repo) ?: repo
            },
            1, 3, TimeUnit.MINUTES
        )
    }

    private fun fullUpdate(): AdoptRepos? {
        // Must catch errors or may kill the scheduler
        try {
            return runBlocking {
                LOGGER.info("Starting Full update")

                val repo = adoptReposBuilder.build(variants.versions)

                val checksum = calculateChecksum(repo)

                mutex.withLock {
                    runBlocking {
                        database.updateAllRepos(repo, checksum)
                        statsInterface.update(repo)
                        database.setReleaseInfo(releaseVersionResolver.formReleaseInfo(repo))
                    }
                }

                LOGGER.info("Full update done")
                return@runBlocking repo
            }
        } catch (e: Exception) {
            LOGGER.error("Failed to perform full update", e)
        } catch (e: Throwable) {
            // Log and rethrow, may be unrecoverable error such as OutOfMemoryError
            LOGGER.error("Error during full update", e)
            throw e
        }
        return null
    }
}
