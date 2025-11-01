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
import kotlin.test.assertTrue

class SchemaComposerTest {

    private val composer = SchemaComposer()
    private val rootMerger = RootSchemaMerger()
    private val typeMerger = TypeMerger()

    @Test
    fun `compose generates SDL and prunes unreachable types`() {
        val service = UpstreamService("students", "http://students/graphql", 0)

        val queryDefinition = GraphQLTypeDefinition(
            name = "Query",
            kind = GraphQLTypeKind.OBJECT,
            fields = listOf(
                GraphQLFieldDefinition(
                    name = "students",
                    type = list(objectRef("Student")),
                    arguments = listOf(GraphQLInputValueDefinition("filter", inputObject("StudentFilter"))),
                ),
                GraphQLFieldDefinition(
                    name = "student",
                    type = objectRef("Student"),
                    arguments = listOf(GraphQLInputValueDefinition("id", nonNull(scalar("ID")))),
                ),
            ),
            inputFields = emptyList(),
        )

        val mutationDefinition = GraphQLTypeDefinition(
            name = "Mutation",
            kind = GraphQLTypeKind.OBJECT,
            fields = listOf(
                GraphQLFieldDefinition(
                    name = "updateStudent",
                    type = objectRef("Student"),
                    arguments = listOf(GraphQLInputValueDefinition("input", nonNull(inputObject("StudentInput")))),
                ),
            ),
            inputFields = emptyList(),
        )

        val studentType = GraphQLTypeDefinition(
            name = "Student",
            kind = GraphQLTypeKind.OBJECT,
            fields = listOf(
                GraphQLFieldDefinition("id", nonNull(scalar("ID")), emptyList()),
                GraphQLFieldDefinition("advisor", objectRef("Advisor"), emptyList()),
            ),
            inputFields = emptyList(),
        )

        val advisorType = GraphQLTypeDefinition(
            name = "Advisor",
            kind = GraphQLTypeKind.OBJECT,
            fields = listOf(GraphQLFieldDefinition("id", nonNull(scalar("ID")), emptyList())),
            inputFields = emptyList(),
        )

        val studentFilter = GraphQLTypeDefinition(
            name = "StudentFilter",
            kind = GraphQLTypeKind.INPUT_OBJECT,
            fields = emptyList(),
            inputFields = listOf(GraphQLInputValueDefinition("status", scalar("String"))),
        )

        val studentInput = GraphQLTypeDefinition(
            name = "StudentInput",
            kind = GraphQLTypeKind.INPUT_OBJECT,
            fields = emptyList(),
            inputFields = listOf(GraphQLInputValueDefinition("advisorId", scalar("ID"))),
        )

        val unusedType = GraphQLTypeDefinition(
            name = "AuditLog",
            kind = GraphQLTypeKind.OBJECT,
            fields = listOf(GraphQLFieldDefinition("message", scalar("String"), emptyList())),
            inputFields = emptyList(),
        )

        val upstreamSchema = UpstreamSchema(
            service = service,
            queryTypeName = "Query",
            queryFieldNames = listOf("students", "student"),
            mutationTypeName = "Mutation",
            mutationFieldNames = listOf("updateStudent"),
            typeDefinitions = mapOf(
                "Query" to queryDefinition,
                "Mutation" to mutationDefinition,
                "Student" to studentType,
                "Advisor" to advisorType,
                "StudentFilter" to studentFilter,
                "StudentInput" to studentInput,
                "AuditLog" to unusedType,
            ),
        )

        val rootSchema = rootMerger.merge(listOf(upstreamSchema))
        val typeRegistry = typeMerger.merge(listOf(upstreamSchema))

        val composed = composer.compose(rootSchema, typeRegistry)

        val expectedSdl = """
            |schema {
            |  query: Query
            |  mutation: Mutation
            |}
            |
            |type Query {
            |  students(filter: StudentFilter): [Student]
            |  student(id: ID!): Student
            |}
            |
            |type Mutation {
            |  updateStudent(input: StudentInput!): Student
            |}
            |
            |type Advisor {
            |  id: ID!
            |}
            |
            |type Student {
            |  id: ID!
            |  advisor: Advisor
            |}
            |
            |input StudentFilter {
            |  status: String
            |}
            |
            |input StudentInput {
            |  advisorId: ID
            |}
            |""".trimMargin()

        assertEquals(expectedSdl.trim(), composed.sdl.trim())

        assertEquals(setOf("Advisor", "Student"), composed.reachableObjectTypes)
        assertEquals(setOf("StudentFilter", "StudentInput"), composed.reachableInputObjectTypes)
        assertTrue("AuditLog" !in composed.reachableObjectTypes)
    }
}

private fun scalar(name: String): GraphQLTypeRef = GraphQLTypeRef(GraphQLTypeKind.SCALAR, name, null)

private fun objectRef(name: String): GraphQLTypeRef = GraphQLTypeRef(GraphQLTypeKind.OBJECT, name, null)

private fun inputObject(name: String): GraphQLTypeRef = GraphQLTypeRef(GraphQLTypeKind.INPUT_OBJECT, name, null)

private fun list(ofType: GraphQLTypeRef): GraphQLTypeRef = GraphQLTypeRef(GraphQLTypeKind.LIST, null, ofType)

private fun nonNull(ofType: GraphQLTypeRef): GraphQLTypeRef = GraphQLTypeRef(GraphQLTypeKind.NON_NULL, null, ofType)
