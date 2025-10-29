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
    fun `successful introspection returns query fields`() {
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
                              "queryType": {
                                "name": "Query",
                                "fields": [
                                  { "name": "students" },
                                  { "name": "student" }
                                ]
                              }
                            }
                          }
                        }
                        """.trimIndent(),
                    ),
            )

            server.start()
            val upstream = UpstreamService("Test", server.url("/graphql").toString(), 0)
            val schema = service.introspect(upstream)

            val recordedRequest = server.takeRequest()
            assertEquals("POST", recordedRequest.method)
            assertEquals("/graphql", recordedRequest.path)

            assertEquals("Query", schema.queryTypeName)
            assertEquals(listOf("students", "student"), schema.queryFieldNames)
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
}
