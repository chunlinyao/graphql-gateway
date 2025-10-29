package com.gateway

import com.gateway.config.UpstreamService
import com.gateway.introspection.GraphQLFieldDefinition
import com.gateway.introspection.GraphQLTypeDefinition
import com.gateway.introspection.GraphQLTypeKind
import com.gateway.introspection.GraphQLTypeRef
import com.gateway.introspection.UpstreamSchema
import com.gateway.schema.RootSchemaMerger
import com.gateway.schema.SchemaComposer
import com.gateway.schema.TypeMerger
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApplicationSchemaEndpointTest {

    @Test
    fun `GET schema returns merged SDL`() = testApplication {
        val service = UpstreamService(
            name = "students",
            url = "http://students/graphql",
            priority = 0,
        )

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

        val upstreamSchema = UpstreamSchema(
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

        val rootSchema = RootSchemaMerger().merge(listOf(upstreamSchema))
        val typeRegistry = TypeMerger().merge(listOf(upstreamSchema))
        val composedSchema = SchemaComposer().compose(rootSchema, typeRegistry)

        application {
            gatewayModule(
                upstreams = listOf(service),
                schemas = listOf(upstreamSchema),
                rootSchema = rootSchema,
                typeRegistry = typeRegistry,
                composedSchema = composedSchema,
            )
        }

        val response = client.get("/schema")

        assertEquals(HttpStatusCode.OK, response.status)
        val contentType = response.headers[HttpHeaders.ContentType]
        assertTrue(contentType?.startsWith("text/plain") == true)
        val body = response.bodyAsText()
        assertTrue(body.contains("type Query"))
        assertEquals(composedSchema.sdl.trim(), body.trim())
    }
}

private fun scalar(name: String): GraphQLTypeRef = GraphQLTypeRef(GraphQLTypeKind.SCALAR, name, null)

private fun objectRef(name: String): GraphQLTypeRef = GraphQLTypeRef(GraphQLTypeKind.OBJECT, name, null)

private fun list(ofType: GraphQLTypeRef): GraphQLTypeRef = GraphQLTypeRef(GraphQLTypeKind.LIST, null, ofType)

private fun nonNull(ofType: GraphQLTypeRef): GraphQLTypeRef = GraphQLTypeRef(GraphQLTypeKind.NON_NULL, null, ofType)
