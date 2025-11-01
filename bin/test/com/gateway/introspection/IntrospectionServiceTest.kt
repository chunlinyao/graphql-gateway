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
    private val typeNamePattern = Regex("""\"typeName\"\s*:\s*\"([\w]+)\"""")

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
                              "mutationType": { "name": "Mutation" },
                              "types": [
                                { "kind": "OBJECT", "name": "Query" },
                                { "kind": "OBJECT", "name": "Mutation" },
                                { "kind": "OBJECT", "name": "Student" },
                                { "kind": "INPUT_OBJECT", "name": "StudentFilter" },
                                { "kind": "SCALAR", "name": "String" }
                              ]
                            }
                          }
                        }
                        """.trimIndent(),
                    ),
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "data": {
                            "__type": {
                              "kind": "OBJECT",
                              "name": "Query",
                              "fields": [
                                {
                                  "name": "students",
                                  "type": {
                                    "kind": "LIST",
                                    "name": null,
                                    "ofType": {
                                      "kind": "OBJECT",
                                      "name": "Student",
                                      "ofType": null
                                    }
                                  },
                                  "args": [
                                    {
                                      "name": "filter",
                                      "type": {
                                        "kind": "INPUT_OBJECT",
                                        "name": "StudentFilter",
                                        "ofType": null
                                      }
                                    }
                                  ]
                                },
                                {
                                  "name": "student",
                                  "type": {
                                    "kind": "OBJECT",
                                    "name": "Student",
                                    "ofType": null
                                  },
                                  "args": [
                                    {
                                      "name": "id",
                                      "type": {
                                        "kind": "NON_NULL",
                                        "name": null,
                                        "ofType": {
                                          "kind": "SCALAR",
                                          "name": "ID",
                                          "ofType": null
                                        }
                                      }
                                    }
                                  ]
                                }
                              ],
                              "inputFields": []
                            }
                          }
                        }
                        """.trimIndent(),
                    ),
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "data": {
                            "__type": {
                              "kind": "OBJECT",
                              "name": "Mutation",
                              "fields": [
                                {
                                  "name": "createStudent",
                                  "type": {
                                    "kind": "OBJECT",
                                    "name": "Student",
                                    "ofType": null
                                  },
                                  "args": []
                                }
                              ],
                              "inputFields": []
                            }
                          }
                        }
                        """.trimIndent(),
                    ),
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "data": {
                            "__type": {
                              "kind": "OBJECT",
                              "name": "Student",
                              "fields": [
                                {
                                  "name": "id",
                                  "type": {
                                    "kind": "NON_NULL",
                                    "name": null,
                                    "ofType": {
                                      "kind": "SCALAR",
                                      "name": "ID",
                                      "ofType": null
                                    }
                                  },
                                  "args": []
                                },
                                {
                                  "name": "name",
                                  "type": {
                                    "kind": "SCALAR",
                                    "name": "String",
                                    "ofType": null
                                  },
                                  "args": []
                                }
                              ],
                              "inputFields": []
                            }
                          }
                        }
                        """.trimIndent(),
                    ),
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "data": {
                            "__type": {
                              "kind": "INPUT_OBJECT",
                              "name": "StudentFilter",
                              "fields": [],
                              "inputFields": [
                                {
                                  "name": "status",
                                  "type": {
                                    "kind": "SCALAR",
                                    "name": "String",
                                    "ofType": null
                                  }
                                }
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

            val overviewRequest = server.takeRequest()
            assertEquals("POST", overviewRequest.method)
            assertEquals("/graphql", overviewRequest.path)
            val overviewBody = overviewRequest.body.readUtf8()
            assertTrue(overviewBody.contains("GatewaySchemaOverview"))

            val typeRequestNames = mutableListOf<String>()
            repeat(4) {
                val request = server.takeRequest()
                assertEquals("POST", request.method)
                val body = request.body.readUtf8()
                assertTrue(body.contains("GatewayTypeDetails"))
                val typeName = typeNamePattern.find(body)?.groupValues?.get(1)
                if (typeName != null) {
                    typeRequestNames += typeName
                }
            }
            assertEquals(setOf("Query", "Mutation", "Student", "StudentFilter"), typeRequestNames.toSet())

            assertEquals("Query", schema.queryTypeName)
            assertEquals(listOf("students", "student"), schema.queryFieldNames)
            assertEquals("Mutation", schema.mutationTypeName)
            assertEquals(listOf("createStudent"), schema.mutationFieldNames)

            val queryDefinition = schema.typeDefinitions["Query"]
            assertEquals(2, queryDefinition?.fields?.size)
            val mutationDefinition = schema.typeDefinitions["Mutation"]
            assertEquals(1, mutationDefinition?.fields?.size)
            val studentType = schema.typeDefinitions["Student"]
            assertEquals(2, studentType?.fields?.size)
            val filterType = schema.typeDefinitions["StudentFilter"]
            assertEquals(1, filterType?.inputFields?.size)
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
