package com.gateway

import com.gateway.config.UpstreamConfigLoader
import com.gateway.config.UpstreamService
import com.gateway.introspection.IntrospectionService
import com.gateway.introspection.UpstreamSchema
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.jackson.jackson
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.util.AttributeKey
import org.slf4j.LoggerFactory

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 4000
    val logger = LoggerFactory.getLogger("Gateway")

    val upstreams = UpstreamConfigLoader().load()
    upstreams.forEach { upstream ->
        logger.info(
            "Registered upstream service: name={}, url={}, priority={}",
            upstream.name,
            upstream.url,
            upstream.priority,
        )
    }

    val introspectionService = IntrospectionService()
    val upstreamSchemas = introspectionService.introspectAll(upstreams)

    logger.info("Starting GraphQL gateway on port {} using Ktor(Netty) + graphql-java stack", port)

    embeddedServer(Netty, port = port) {
        gatewayModule(upstreams, upstreamSchemas)
    }.start(wait = true)
}

@Suppress("unused")
fun Application.gatewayModule(
    upstreams: List<UpstreamService>,
    schemas: List<UpstreamSchema>,
) {
    attributes.put(UPSTREAMS_KEY, upstreams)
    attributes.put(UPSTREAM_SCHEMAS_KEY, schemas)

    install(ContentNegotiation) {
        jackson()
    }

    routing {
        get("/healthz") {
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }
    }
}

@Suppress("unused")
fun Application.gatewayModule() {
    val upstreams = UpstreamConfigLoader().load()
    val schemas = IntrospectionService().introspectAll(upstreams)
    gatewayModule(upstreams, schemas)
}

val UPSTREAMS_KEY: AttributeKey<List<UpstreamService>> = AttributeKey("gateway.upstreams")
val UPSTREAM_SCHEMAS_KEY: AttributeKey<List<UpstreamSchema>> = AttributeKey("gateway.upstream.schemas")
