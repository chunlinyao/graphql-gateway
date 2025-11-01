package com.gateway

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gateway.config.UpstreamService
import com.gateway.graphql.GatewayGraphQLFactory
import com.gateway.introspection.GraphQLFieldDefinition
import com.gateway.introspection.GraphQLTypeDefinition
import com.gateway.introspection.GraphQLTypeKind
import com.gateway.introspection.GraphQLTypeRef
import com.gateway.introspection.IntrospectionFailure
import com.gateway.introspection.UpstreamSchema
import com.gateway.schema.RootSchemaMerger
import com.gateway.schema.SchemaComposer
import com.gateway.schema.TypeMerger
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApplicationHealthEndpointTest {
    private val objectMapper = jacksonObjectMapper()

    @Test
    fun `readyz returns 200 when gateway is ready`() = testApplication {
        val (service, upstreamSchema) = createServiceAndSchema("students")
        val rootSchema = RootSchemaMerger().merge(listOf(upstreamSchema))
        val typeRegistry = TypeMerger().merge(listOf(upstreamSchema))
        val composedSchema = SchemaComposer().compose(rootSchema, typeRegistry)
        val graphQL = GatewayGraphQLFactory().create(composedSchema.sdl)

        application {
            gatewayModule(
                upstreams = listOf(service),
                schemas = listOf(upstreamSchema),
                rootSchema = rootSchema,
                typeRegistry = typeRegistry,
                composedSchema = composedSchema,
                graphQL = graphQL,
            )
        }

        val response = client.get("/readyz")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = objectMapper.readValue(response.bodyAsText(), Map::class.java)
        assertEquals("ready", body["status"])
    }

    @Test
    fun `readyz returns 503 when introspection failures remain`() = testApplication {
        val (studentsService, studentsSchema) = createServiceAndSchema("students")
        val coursesService = UpstreamService(
            name = "courses",
            url = "http://courses/graphql",
            priority = 1,
        )

        val rootSchema = RootSchemaMerger().merge(listOf(studentsSchema))
        val typeRegistry = TypeMerger().merge(listOf(studentsSchema))
        val composedSchema = SchemaComposer().compose(rootSchema, typeRegistry)
        val graphQL = GatewayGraphQLFactory().create(composedSchema.sdl)

        application {
            gatewayModule(
                upstreams = listOf(studentsService, coursesService),
                schemas = listOf(studentsSchema),
                rootSchema = rootSchema,
                typeRegistry = typeRegistry,
                composedSchema = composedSchema,
                graphQL = graphQL,
                introspectionFailures = listOf(
                    IntrospectionFailure(
                        service = coursesService,
                        reason = "timeout",
                    ),
                ),
            )
        }

        val response = client.get("/readyz")
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        val body = objectMapper.readValue(response.bodyAsText(), Map::class.java)
        assertEquals("not_ready", body["status"])
        @Suppress("UNCHECKED_CAST")
        val reasons = body["reasons"] as List<*>
        assertTrue(reasons.any { it.toString().contains("courses") })
        @Suppress("UNCHECKED_CAST")
        val failures = body["failures"] as List<Map<String, Any?>>
        assertEquals(1, failures.size)
        assertEquals("courses", failures.first()["name"])
    }
}

private fun createServiceAndSchema(name: String): Pair<UpstreamService, UpstreamSchema> {
    val service = UpstreamService(
        name = name,
        url = "http://$name/graphql",
        priority = 0,
    )

    val typeName = name.toTypeName()
    val queryType = GraphQLTypeDefinition(
        name = "Query",
        kind = GraphQLTypeKind.OBJECT,
        fields = listOf(
            GraphQLFieldDefinition(
                name = name,
                type = list(objectRef(typeName)),
                arguments = emptyList(),
            ),
        ),
        inputFields = emptyList(),
    )

    val objectType = GraphQLTypeDefinition(
        name = typeName,
        kind = GraphQLTypeKind.OBJECT,
        fields = listOf(
            GraphQLFieldDefinition(
                name = "id",
                type = nonNull(scalar("ID")),
                arguments = emptyList(),
            ),
        ),
        inputFields = emptyList(),
    )

    val upstreamSchema = UpstreamSchema(
        service = service,
        queryTypeName = "Query",
        queryFieldNames = listOf(name),
        mutationTypeName = null,
        mutationFieldNames = emptyList(),
        typeDefinitions = mapOf(
            "Query" to queryType,
            typeName to objectType,
        ),
    )

    return service to upstreamSchema
}

private fun scalar(name: String): GraphQLTypeRef = GraphQLTypeRef(GraphQLTypeKind.SCALAR, name, null)

private fun objectRef(name: String): GraphQLTypeRef = GraphQLTypeRef(GraphQLTypeKind.OBJECT, name, null)

private fun list(ofType: GraphQLTypeRef): GraphQLTypeRef = GraphQLTypeRef(GraphQLTypeKind.LIST, null, ofType)

private fun nonNull(ofType: GraphQLTypeRef): GraphQLTypeRef = GraphQLTypeRef(GraphQLTypeKind.NON_NULL, null, ofType)

private fun String.toTypeName(): String = replaceFirstChar { character ->
    if (character.isLowerCase()) character.titlecase() else character.toString()
}
