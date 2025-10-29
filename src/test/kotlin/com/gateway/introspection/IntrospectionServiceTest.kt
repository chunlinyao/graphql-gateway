package com.gateway.introspection

import com.gateway.config.UpstreamService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class IntrospectionServiceTest {

    private val service = IntrospectionService()

    @Test
    fun `successful introspection returns query and mutation fields`() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "data": {
                            "__schema": {
                              "queryType": { "name": "Query" },
                              "mutationType": { "name": "Mutation" }
                            }
                          }
                        }
                        """.trimIndent(),
                    ),
            )

            // __type for Query
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "data": {
                            "__type": {
                              "fields": [
                                { "name": "students" },
                                { "name": "student" }
                              ]
                            }
                          }
                        }
                        """.trimIndent(),
                    ),
            )

            // __type for Mutation
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "data": {
                            "__type": {
                              "fields": [
                                { "name": "createStudent" }
                              ]
                            }
                          }
                        }
                        """.trimIndent(),
                    ),
            )

            server.start()
            val upstream = UpstreamService("Test", server.url("/graphql").toString(), 0)
            val schema = service.introspect(upstream)

            val recordedRequest1 = server.takeRequest()
            assertEquals("POST", recordedRequest1.method)
            assertEquals("/graphql", recordedRequest1.path)

            val recordedRequest2 = server.takeRequest()
            assertEquals("POST", recordedRequest2.method)
            assertEquals("/graphql", recordedRequest2.path)

            val recordedRequest3 = server.takeRequest()
            assertEquals("POST", recordedRequest3.method)
            assertEquals("/graphql", recordedRequest3.path)

            assertEquals("Query", schema.queryTypeName)
            assertEquals(listOf("students", "student"), schema.queryFieldNames)
            assertEquals("Mutation", schema.mutationTypeName)
            assertEquals(listOf("createStudent"), schema.mutationFieldNames)
        }
    }

    @Test
    fun `introspection errors trigger exception`() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "errors": [
                            { "message": "Not authorized" }
                          ]
                        }
                        """.trimIndent(),
                    ),
            )

            server.start()
            val upstream = UpstreamService("Test", server.url("/graphql").toString(), 0)

            val exception = assertFailsWith<IllegalStateException> {
                service.introspect(upstream)
            }

            assertTrue(exception.message?.contains("Not authorized") == true)
        }
    }

    @Test
    fun `introspectAll collects failures without throwing`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(500))
            server.start()

            val upstream = UpstreamService("Broken", server.url("/graphql").toString(), 0)

            val result = service.introspectAll(listOf(upstream))

            assertTrue(result.schemas.isEmpty())
            assertEquals(1, result.failures.size)
            assertEquals("Broken", result.failures.first().service.name)
            assertTrue(result.failures.first().reason.isNotBlank())
        }
    }
}
