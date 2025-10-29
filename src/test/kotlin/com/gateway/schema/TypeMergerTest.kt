package com.gateway.schema

import com.gateway.config.UpstreamService
import com.gateway.introspection.GraphQLFieldDefinition
import com.gateway.introspection.GraphQLInputValueDefinition
import com.gateway.introspection.GraphQLTypeDefinition
import com.gateway.introspection.GraphQLTypeKind
import com.gateway.introspection.GraphQLTypeRef
import com.gateway.introspection.UpstreamSchema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class TypeMergerTest {

    private val merger = TypeMerger()

    @Test
    fun `higher priority service overrides conflicting object fields`() {
        val lowPriorityService = UpstreamService("payments", "http://payments/graphql", 1)
        val highPriorityService = UpstreamService("students", "http://students/graphql", 0)

        val studentTypeLow = GraphQLTypeDefinition(
            name = "Student",
            kind = GraphQLTypeKind.OBJECT,
            fields = listOf(
                GraphQLFieldDefinition("status", scalar("String"), emptyList()),
                GraphQLFieldDefinition("legacyId", scalar("ID"), emptyList()),
            ),
            inputFields = emptyList(),
        )

        val studentTypeHigh = GraphQLTypeDefinition(
            name = "Student",
            kind = GraphQLTypeKind.OBJECT,
            fields = listOf(
                GraphQLFieldDefinition("status", nonNull(scalar("String")), emptyList()),
                GraphQLFieldDefinition("gpa", scalar("Float"), emptyList()),
            ),
            inputFields = emptyList(),
        )

        val schemas = listOf(
            UpstreamSchema(
                service = lowPriorityService,
                queryTypeName = "Query",
                queryFieldNames = emptyList(),
                mutationTypeName = null,
                mutationFieldNames = emptyList(),
                typeDefinitions = mapOf("Student" to studentTypeLow),
            ),
            UpstreamSchema(
                service = highPriorityService,
                queryTypeName = "Query",
                queryFieldNames = emptyList(),
                mutationTypeName = null,
                mutationFieldNames = emptyList(),
                typeDefinitions = mapOf("Student" to studentTypeHigh),
            ),
        )

        val merged = merger.merge(schemas)
        val student = merged.objectTypes["Student"]
        assertNotNull(student)
        val statusField = student.fields.find { it.name == "status" }
        assertNotNull(statusField)
        assertEquals(highPriorityService, statusField.owner)
        assertEquals(nonNull(scalar("String")), statusField.type)
        val legacyField = student.fields.find { it.name == "legacyId" }
        assertEquals(lowPriorityService, legacyField?.owner)
        val gpaField = student.fields.find { it.name == "gpa" }
        assertEquals(highPriorityService, gpaField?.owner)
        assertEquals(3, student.fields.size)
    }

    @Test
    fun `input object fields are merged according to priority`() {
        val lowPriorityService = UpstreamService("payments", "http://payments/graphql", 5)
        val highPriorityService = UpstreamService("students", "http://students/graphql", 0)

        val inputLow = GraphQLTypeDefinition(
            name = "StudentFilter",
            kind = GraphQLTypeKind.INPUT_OBJECT,
            fields = emptyList(),
            inputFields = listOf(GraphQLInputValueDefinition("status", scalar("String"))),
        )

        val inputHigh = GraphQLTypeDefinition(
            name = "StudentFilter",
            kind = GraphQLTypeKind.INPUT_OBJECT,
            fields = emptyList(),
            inputFields = listOf(GraphQLInputValueDefinition("status", scalar("ID"))),
        )

        val schemas = listOf(
            UpstreamSchema(
                service = lowPriorityService,
                queryTypeName = "Query",
                queryFieldNames = emptyList(),
                mutationTypeName = null,
                mutationFieldNames = emptyList(),
                typeDefinitions = mapOf("StudentFilter" to inputLow),
            ),
            UpstreamSchema(
                service = highPriorityService,
                queryTypeName = "Query",
                queryFieldNames = emptyList(),
                mutationTypeName = null,
                mutationFieldNames = emptyList(),
                typeDefinitions = mapOf("StudentFilter" to inputHigh),
            ),
        )

        val merged = merger.merge(schemas)
        val inputType = merged.inputObjectTypes["StudentFilter"]
        assertNotNull(inputType)
        val statusField = inputType.inputFields.find { it.name == "status" }
        assertNotNull(statusField)
        assertEquals(highPriorityService, statusField.owner)
        assertEquals(scalar("ID"), statusField.type)
    }

    private fun scalar(name: String): GraphQLTypeRef = GraphQLTypeRef(GraphQLTypeKind.SCALAR, name, null)

    private fun nonNull(ofType: GraphQLTypeRef): GraphQLTypeRef = GraphQLTypeRef(GraphQLTypeKind.NON_NULL, null, ofType)
}
