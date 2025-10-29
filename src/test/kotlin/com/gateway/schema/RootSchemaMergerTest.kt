package com.gateway.schema

import com.gateway.config.UpstreamService
import com.gateway.introspection.UpstreamSchema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RootSchemaMergerTest {

    private val merger = RootSchemaMerger()

    @Test
    fun `higher priority service wins conflicting fields`() {
        val highPriority = UpstreamService("Students", "http://students/graphql", priority = 0)
        val lowPriority = UpstreamService("Payments", "http://payments/graphql", priority = 1)

        val schemas = listOf(
            UpstreamSchema(
                service = lowPriority,
                queryTypeName = "Query",
                queryFieldNames = listOf("student", "payment"),
                mutationTypeName = "Mutation",
                mutationFieldNames = listOf("createPayment"),
            ),
            UpstreamSchema(
                service = highPriority,
                queryTypeName = "Query",
                queryFieldNames = listOf("student", "students"),
                mutationTypeName = "Mutation",
                mutationFieldNames = listOf("createStudent"),
            ),
        )

        val merged = merger.merge(schemas)

        val queryRouting = merged.queryRouting()
        assertEquals(highPriority, queryRouting["student"])
        assertEquals(highPriority, queryRouting["students"])
        assertEquals(lowPriority, queryRouting["payment"])

        val mutationRouting = merged.mutationRouting()
        assertEquals(highPriority, mutationRouting["createStudent"])
        assertEquals(lowPriority, mutationRouting["createPayment"])
    }

    @Test
    fun `mutation definition absent when upstreams have none`() {
        val service = UpstreamService("Students", "http://students/graphql", priority = 0)
        val schemas = listOf(
            UpstreamSchema(
                service = service,
                queryTypeName = "Query",
                queryFieldNames = listOf("students"),
                mutationTypeName = null,
                mutationFieldNames = emptyList(),
            ),
        )

        val merged = merger.merge(schemas)

        assertNotNull(merged.query)
        assertNull(merged.mutation)
        assertEquals(mapOf("students" to service), merged.queryRouting())
        assertEquals(emptyMap(), merged.mutationRouting())
    }
}
