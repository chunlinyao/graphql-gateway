package com.gateway

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gateway.config.UpstreamService
import com.gateway.introspection.GraphQLFieldDefinition
import com.gateway.introspection.GraphQLTypeDefinition
import com.gateway.introspection.GraphQLTypeKind
import com.gateway.introspection.GraphQLTypeRef
import com.gateway.introspection.UpstreamSchema
import com.gateway.graphql.GatewayGraphQLFactory
import com.gateway.schema.RootSchemaMerger
import com.gateway.schema.SchemaComposer
import com.gateway.schema.TypeMerger
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApplicationGraphQLIntrospectionTest {

    private val mapper = jacksonObjectMapper()

    @Test
    fun `POST graphql introspection is answered locally`() = testApplication {
        val service = upstreamService()
        val upstreamSchema = upstreamSchema(service)
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

        val response = client.post("/graphql") {
            headers.append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"query":"{ __schema { queryType { name } } }"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val payload = mapper.readValue(response.bodyAsText(), Map::class.java)
        val data = payload["data"] as Map<*, *>
        val schema = data["__schema"] as Map<*, *>
        val queryType = schema["queryType"] as Map<*, *>
        assertEquals("Query", queryType["name"])
    }

    @Test
    fun `POST graphql non introspection returns not implemented`() = testApplication {
        val service = upstreamService()
        val upstreamSchema = upstreamSchema(service)
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

        val response = client.post("/graphql") {
            headers.append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"query":"{ students { id } }"}""")
        }

        assertEquals(HttpStatusCode.NotImplemented, response.status)
        assertTrue(response.bodyAsText().contains("Only introspection queries"))
    }

    private fun upstreamService(): UpstreamService = UpstreamService(
        name = "students",
        url = "http://students/graphql",
        priority = 0,
    )

    private fun upstreamSchema(service: UpstreamService): UpstreamSchema {
        val queryType = GraphQLTypeDefinition(
            name = "Query",
            kind = GraphQLTypeKind.OBJECT,
            fields = listOf(
                GraphQLFieldDefinition(
                    name = "students",
                    type = list(objectRef("Student")),
                    arguments = emptyList(),
                ),
            ),
            inputFields = emptyList(),
        )

        val studentType = GraphQLTypeDefinition(
            name = "Student",
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

        return UpstreamSchema(
            service = service,
            queryTypeName = "Query",
            queryFieldNames = listOf("students"),
            mutationTypeName = null,
            mutationFieldNames = emptyList(),
            typeDefinitions = mapOf(
                "Query" to queryType,
                "Student" to studentType,
            ),
        )
    }
}

private fun scalar(name: String): GraphQLTypeRef = GraphQLTypeRef(GraphQLTypeKind.SCALAR, name, null)

private fun objectRef(name: String): GraphQLTypeRef = GraphQLTypeRef(GraphQLTypeKind.OBJECT, name, null)

private fun list(ofType: GraphQLTypeRef): GraphQLTypeRef = GraphQLTypeRef(GraphQLTypeKind.LIST, null, ofType)

private fun nonNull(ofType: GraphQLTypeRef): GraphQLTypeRef = GraphQLTypeRef(GraphQLTypeKind.NON_NULL, null, ofType)
