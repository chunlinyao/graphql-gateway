package com.gateway

import com.gateway.config.UpstreamConfigLoader
import com.gateway.config.UpstreamService
import com.gateway.introspection.IntrospectionService
import com.gateway.introspection.UpstreamSchema
import com.gateway.schema.GatewayRootSchema
import com.gateway.schema.RootSchemaMerger
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
    val introspectionResult = introspectionService.introspectAll(upstreams)

    if (introspectionResult.hasFailures) {
        introspectionResult.failures.forEach { failure ->
            logger.warn(
                "Skipping upstream service due to introspection failure: name={}, url={}, reason={}",
                failure.service.name,
                failure.service.url,
                failure.reason,
            )
        }
    }

    val upstreamSchemas = introspectionResult.schemas

    if (upstreamSchemas.isEmpty()) {
        logger.warn("No upstream schemas available after introspection; gateway will expose only health endpoints")
    }

    val rootSchema = RootSchemaMerger().merge(upstreamSchemas)

    if (rootSchema.query.fields.isNotEmpty()) {
        logger.info("Gateway query routing table:")
        rootSchema.query.fields.forEach { field ->
            logger.info(
                "  {} -> {} ({})",
                field.fieldName,
                field.service.name,
                field.service.url,
            )
        }
    }

    rootSchema.mutation?.let { mutation ->
        if (mutation.fields.isNotEmpty()) {
            logger.info("Gateway mutation routing table:")
            mutation.fields.forEach { field ->
                logger.info(
                    "  {} -> {} ({})",
                    field.fieldName,
                    field.service.name,
                    field.service.url,
                )
            }
        }
    }

    logger.info("Starting GraphQL gateway on port {} using Ktor(Netty) + graphql-java stack", port)

    embeddedServer(Netty, port = port) {
        gatewayModule(upstreams, upstreamSchemas, rootSchema)
    }.start(wait = true)
}

@Suppress("unused")
fun Application.gatewayModule(
    upstreams: List<UpstreamService>,
    schemas: List<UpstreamSchema>,
    rootSchema: GatewayRootSchema,
) {
    attributes.put(UPSTREAMS_KEY, upstreams)
    attributes.put(UPSTREAM_SCHEMAS_KEY, schemas)
    attributes.put(GATEWAY_ROOT_SCHEMA_KEY, rootSchema)

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
    val result = IntrospectionService().introspectAll(upstreams)
    if (result.hasFailures) {
        result.failures.forEach { failure ->
            environment.log.warn(
                "Skipping upstream service due to introspection failure: name={}, url={}, reason={}",
                failure.service.name,
                failure.service.url,
                failure.reason,
            )
        }
    }
    val rootSchema = RootSchemaMerger().merge(result.schemas)
    gatewayModule(upstreams, result.schemas, rootSchema)
}

val UPSTREAMS_KEY: AttributeKey<List<UpstreamService>> = AttributeKey("gateway.upstreams")
val UPSTREAM_SCHEMAS_KEY: AttributeKey<List<UpstreamSchema>> = AttributeKey("gateway.upstream.schemas")
val GATEWAY_ROOT_SCHEMA_KEY: AttributeKey<GatewayRootSchema> = AttributeKey("gateway.root.schema")
