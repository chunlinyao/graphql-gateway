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
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class ApplicationGraphQLRoutingTest {

    private val mapper = jacksonObjectMapper()

    @Test
    fun `POST graphql query is forwarded to owning upstream`() = testApplication {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":{"students":[]}}"""))
        server.start()

        val service = upstreamService(server)
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
            headers.append(HttpHeaders.Authorization, "Bearer token-123")
            setBody("""{"query":"{ students { id } }"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("""{"data":{"students":[]}}""", response.bodyAsText())

        val recorded = server.takeRequest(1, TimeUnit.SECONDS)
        requireNotNull(recorded)
        assertEquals("POST", recorded.method)
        assertEquals("application/json", recorded.getHeader("Content-Type"))
        assertEquals("Bearer token-123", recorded.getHeader("Authorization"))

        val forwarded = mapper.readValue(recorded.body.readUtf8(), Map::class.java)
        assertEquals("{ students { id } }", forwarded["query"])

        server.shutdown()
    }

    @Test
    fun `POST graphql query referencing multiple services returns bad request`() = testApplication {
        val studentsServer = MockWebServer().apply {
            enqueue(MockResponse().setResponseCode(200).setBody("""{"data":{"students":[]}}"""))
            start()
        }
        val coursesServer = MockWebServer().apply {
            enqueue(MockResponse().setResponseCode(200).setBody("""{"data":{"courses":[]}}"""))
            start()
        }

        val students = upstreamSchema(upstreamService(studentsServer), fieldName = "students")
        val courses = upstreamSchema(upstreamService(coursesServer), fieldName = "courses", priority = 1)

        val rootSchema = RootSchemaMerger().merge(listOf(students, courses))
        val typeRegistry = TypeMerger().merge(listOf(students, courses))
        val composedSchema = SchemaComposer().compose(rootSchema, typeRegistry)
        val graphQL = GatewayGraphQLFactory().create(composedSchema.sdl)

        application {
            gatewayModule(
                upstreams = listOf(students.service, courses.service),
                schemas = listOf(students, courses),
                rootSchema = rootSchema,
                typeRegistry = typeRegistry,
                composedSchema = composedSchema,
                graphQL = graphQL,
            )
        }

        val response = client.post("/graphql") {
            headers.append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"query":"{ students { id } courses { id } }"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("multiple upstream services"))

        studentsServer.shutdown()
        coursesServer.shutdown()
    }

    private fun upstreamService(server: MockWebServer, priority: Int = 0): UpstreamService = UpstreamService(
        name = "upstream-${server.port}",
        url = server.url("/graphql").toString(),
        priority = priority,
    )

    private fun upstreamSchema(
        service: UpstreamService,
        fieldName: String = "students",
        priority: Int = service.priority,
    ): UpstreamSchema {
        val queryType = GraphQLTypeDefinition(
            name = "Query",
            kind = GraphQLTypeKind.OBJECT,
            fields = listOf(
                GraphQLFieldDefinition(
                    name = fieldName,
                    type = list(objectRef(fieldName.removeSuffix("s").replaceFirstChar { it.uppercase() })),
                    arguments = emptyList(),
                ),
            ),
            inputFields = emptyList(),
        )

        val objectType = GraphQLTypeDefinition(
            name = fieldName.removeSuffix("s").replaceFirstChar { it.uppercase() },
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
            service = service.copy(priority = priority),
            queryTypeName = "Query",
            queryFieldNames = listOf(fieldName),
            mutationTypeName = null,
            mutationFieldNames = emptyList(),
            typeDefinitions = mapOf(
                "Query" to queryType,
                objectType.name to objectType,
            ),
        )
    }
}

private fun scalar(name: String): GraphQLTypeRef = GraphQLTypeRef(GraphQLTypeKind.SCALAR, name, null)

private fun objectRef(name: String): GraphQLTypeRef = GraphQLTypeRef(GraphQLTypeKind.OBJECT, name, null)

private fun list(ofType: GraphQLTypeRef): GraphQLTypeRef = GraphQLTypeRef(GraphQLTypeKind.LIST, null, ofType)

private fun nonNull(ofType: GraphQLTypeRef): GraphQLTypeRef = GraphQLTypeRef(GraphQLTypeKind.NON_NULL, null, ofType)
