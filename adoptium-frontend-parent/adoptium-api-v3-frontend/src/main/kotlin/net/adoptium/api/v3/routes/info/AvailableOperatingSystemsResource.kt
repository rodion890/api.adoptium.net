package net.adoptium.api.v3.routes.info

import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import jakarta.ws.rs.core.UriInfo
import net.adoptium.api.v3.models.OperatingSystem
import org.eclipse.microprofile.openapi.annotations.Operation
import org.eclipse.microprofile.openapi.annotations.tags.Tag

@Tag(name = "Release Info")
@Path("/v3/info")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
class AvailableOperatingSystemsResource {

    @GET
    @Path("/available_operating-systems")
    @Deprecated("Use the new get() method with new path /available/operating-systems")
    fun get301(uriInfo: UriInfo): Response {
        val location = uriInfo.requestUriBuilder.replacePath("/v3/info/available/operating-systems").build()
        return Response
            .status(Response.Status.MOVED_PERMANENTLY)
            .location(location)
            .entity(OperatingSystem.values().map { it.name }.toList())
            .build()
    }

    @GET
    @Path("/available/operating-systems")
    @Operation(summary = "Returns names of available operating systems", operationId = "getAvailableOperatingSystems")
    fun get(): List<String> {
        return OperatingSystem.values().map { it.name }.toList()
    }
}
