package com.gateway

import com.gateway.config.UpstreamConfigLoader
import com.gateway.config.UpstreamService
import com.gateway.graphql.GatewayGraphQLFactory
import com.gateway.graphql.GraphQLRequest
import com.gateway.graphql.IntrospectionQueryDetector
import com.gateway.health.GatewayReadiness
import com.gateway.introspection.IntrospectionFailure
import com.gateway.introspection.IntrospectionService
import com.gateway.introspection.UpstreamSchema
import com.gateway.routing.GraphQLRequestForwarder
import com.gateway.routing.GraphQLRequestRouter
import com.gateway.schema.ComposedSchema
import com.gateway.schema.GatewayRootSchema
import com.gateway.schema.GatewayTypeRegistry
import com.gateway.schema.RootSchemaMerger
import com.gateway.schema.SchemaComposer
import com.gateway.schema.TypeMerger
import io.ktor.http.ContentType
import io.ktor.http.ContentType.Application.Json
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpStatusCode.Companion.BadGateway
import io.ktor.http.HttpStatusCode.Companion.BadRequest
import io.ktor.http.HttpStatusCode.Companion.ServiceUnavailable
import io.ktor.serialization.jackson.jackson
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.util.AttributeKey
import graphql.ExecutionInput
import graphql.GraphQL
import kotlinx.coroutines.CancellationException
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
    val typeRegistry = TypeMerger().merge(upstreamSchemas)
    val composedSchema = SchemaComposer().compose(rootSchema, typeRegistry)
    val graphQL = GatewayGraphQLFactory().create(composedSchema.sdl)

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

    if (typeRegistry.objectTypes.isNotEmpty()) {
        val sample = typeRegistry.objectTypes.values.first()
        logger.info("Merged object type {} fields:", sample.name)
        sample.fields.forEach { field ->
            val arguments = if (field.arguments.isEmpty()) {
                ""
            } else {
                field.arguments.joinToString(", ") { arg -> "${'$'}{arg.name}: ${'$'}{arg.type.render()}" }
            }
            logger.info(
                "  {}: {} (owner={}, args=[{}])",
                field.name,
                field.type.render(),
                field.owner.name,
                arguments,
            )
        }
    }

    logger.info("Generated merged schema SDL preview:\n{}", composedSchema.sdl.lines().take(20).joinToString("\n"))

    logger.info("Starting GraphQL gateway on port {} using Ktor(Netty) + graphql-java stack", port)

    embeddedServer(Netty, port = port) {
        gatewayModule(
            upstreams = upstreams,
            schemas = upstreamSchemas,
            rootSchema = rootSchema,
            typeRegistry = typeRegistry,
            composedSchema = composedSchema,
            graphQL = graphQL,
            introspectionFailures = introspectionResult.failures,
        )
    }.start(wait = true)
}

@Suppress("unused")
fun Application.gatewayModule(
    upstreams: List<UpstreamService>,
    schemas: List<UpstreamSchema>,
    rootSchema: GatewayRootSchema,
    typeRegistry: GatewayTypeRegistry,
    composedSchema: ComposedSchema,
    graphQL: GraphQL?,
    introspectionFailures: List<IntrospectionFailure> = emptyList(),
) {
    attributes.put(UPSTREAMS_KEY, upstreams)
    attributes.put(UPSTREAM_SCHEMAS_KEY, schemas)
    attributes.put(GATEWAY_ROOT_SCHEMA_KEY, rootSchema)
    attributes.put(GATEWAY_TYPE_REGISTRY_KEY, typeRegistry)
    attributes.put(GATEWAY_MERGED_SDL_KEY, composedSchema.sdl)
    graphQL?.let { attributes.put(GATEWAY_GRAPHQL_KEY, it) }
    val readiness = GatewayReadiness(
        expectedUpstreams = upstreams.size,
        schemasAvailable = schemas.size,
        introspectionFailures = introspectionFailures,
        graphQLAvailable = graphQL != null,
    )
    attributes.put(GATEWAY_READINESS_KEY, readiness)

    install(ContentNegotiation) {
        jackson()
    }

    val requestRouter = GraphQLRequestRouter(rootSchema)
    val requestForwarder = GraphQLRequestForwarder()

    routing {
        get("/healthz") {
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }

        get("/readyz") {
            val readinessState = call.application.attributes[GATEWAY_READINESS_KEY]
            if (readinessState.isReady) {
                call.respond(HttpStatusCode.OK, mapOf("status" to "ready"))
            } else {
                call.respond(
                    status = ServiceUnavailable,
                    message = mapOf(
                        "status" to "not_ready",
                        "expectedUpstreams" to readinessState.expectedUpstreams,
                        "schemasLoaded" to readinessState.schemasAvailable,
                        "graphQLAvailable" to readinessState.graphQLAvailable,
                        "failures" to readinessState.introspectionFailures.map { failure ->
                            mapOf(
                                "name" to failure.service.name,
                                "url" to failure.service.url,
                                "priority" to failure.service.priority,
                                "reason" to failure.reason,
                            )
                        },
                        "reasons" to readinessState.reasons(),
                    ),
                )
            }
        }

        get("/schema") {
            val mergedSchema = call.application.attributes[GATEWAY_MERGED_SDL_KEY]
            call.respondText(
                text = mergedSchema,
                contentType = ContentType.Text.Plain,
                status = HttpStatusCode.OK,
            )
        }

        post("/graphql") {
            val request = call.receive<GraphQLRequest>()
            val query = request.query
            if (query.isNullOrBlank()) {
                call.respond(
                    status = HttpStatusCode.BadRequest,
                    message = mapOf("errors" to listOf(mapOf("message" to "query must be provided"))),
                )
                return@post
            }

            val targetGraphQL = if (call.application.attributes.contains(GATEWAY_GRAPHQL_KEY)) {
                call.application.attributes[GATEWAY_GRAPHQL_KEY]
            } else {
                null
            }

            if (IntrospectionQueryDetector.isIntrospectionQuery(query, request.operationName)) {
                if (targetGraphQL == null) {
                    call.respond(
                        status = HttpStatusCode.ServiceUnavailable,
                        message = mapOf("errors" to listOf(mapOf("message" to "Introspection schema is unavailable"))),
                    )
                    return@post
                }

                val executionInput = ExecutionInput.newExecutionInput()
                    .query(query)
                    .operationName(request.operationName)
                    .variables(request.variables ?: emptyMap())
                    .build()

                val result = targetGraphQL.execute(executionInput)
                call.respond(HttpStatusCode.OK, result.toSpecification())
                return@post
            }

            val routedRequest = try {
                requestRouter.route(query, request.operationName)
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                val message = ex.message ?: "Unable to route request"
                call.respond(
                    status = BadRequest,
                    message = mapOf(
                        "errors" to listOf(mapOf("message" to message)),
                    ),
                )
                return@post
            }

            val forwardResult = try {
                requestForwarder.forward(
                    request = request,
                    routedRequest = routedRequest,
                    authorizationHeader = call.request.headers[HttpHeaders.Authorization],
                )
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                call.respond(
                    status = BadGateway,
                    message = mapOf(
                        "errors" to listOf(
                            mapOf(
                                "message" to "Failed to contact upstream service '${routedRequest.service.name}': ${ex.message ?: ex.javaClass.simpleName}",
                            ),
                        ),
                    ),
                )
                return@post
            }

            if (forwardResult.statusCode !in 200..299) {
                call.respond(
                    status = BadGateway,
                    message = mapOf(
                        "errors" to listOf(
                            mapOf(
                                "message" to "Upstream service '${routedRequest.service.name}' responded with status ${forwardResult.statusCode}",
                            ),
                        ),
                    ),
                )
                return@post
            }

            call.respondText(
                text = forwardResult.body,
                contentType = Json,
                status = HttpStatusCode.OK,
            )
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
    val typeRegistry = TypeMerger().merge(result.schemas)
    val composedSchema = SchemaComposer().compose(rootSchema, typeRegistry)
    val graphQL = GatewayGraphQLFactory().create(composedSchema.sdl)
    gatewayModule(
        upstreams = upstreams,
        schemas = result.schemas,
        rootSchema = rootSchema,
        typeRegistry = typeRegistry,
        composedSchema = composedSchema,
        graphQL = graphQL,
        introspectionFailures = result.failures,
    )
}

val UPSTREAMS_KEY: AttributeKey<List<UpstreamService>> = AttributeKey("gateway.upstreams")
val UPSTREAM_SCHEMAS_KEY: AttributeKey<List<UpstreamSchema>> = AttributeKey("gateway.upstream.schemas")
val GATEWAY_ROOT_SCHEMA_KEY: AttributeKey<GatewayRootSchema> = AttributeKey("gateway.root.schema")
val GATEWAY_TYPE_REGISTRY_KEY: AttributeKey<GatewayTypeRegistry> = AttributeKey("gateway.types.registry")
val GATEWAY_MERGED_SDL_KEY: AttributeKey<String> = AttributeKey("gateway.merged.sdl")
val GATEWAY_GRAPHQL_KEY: AttributeKey<GraphQL> = AttributeKey("gateway.graphql")
val GATEWAY_READINESS_KEY: AttributeKey<GatewayReadiness> = AttributeKey("gateway.readiness")
