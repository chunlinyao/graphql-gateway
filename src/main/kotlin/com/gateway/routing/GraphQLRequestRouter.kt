package com.gateway.routing

import com.gateway.config.UpstreamService
import com.gateway.schema.GatewayRootSchema
import graphql.language.Field
import graphql.language.OperationDefinition
import graphql.parser.Parser

/**
 * Represents a routed GraphQL request targeting a specific upstream service.
 */
data class RoutedGraphQLRequest(
    val service: UpstreamService,
    val operationType: OperationDefinition.Operation,
    val rootFieldNames: List<String>,
)

class GraphQLRoutingException(message: String) : RuntimeException(message)

/**
 * Determine which upstream service should execute a GraphQL request based on the merged gateway schema.
 */
class GraphQLRequestRouter(
    private val rootSchema: GatewayRootSchema,
    private val parser: Parser = Parser(),
) {

    fun route(query: String, operationName: String?): RoutedGraphQLRequest {
        val document = try {
            parser.parseDocument(query)
        } catch (ex: Exception) {
            throw GraphQLRoutingException(ex.message ?: "Failed to parse GraphQL query")
        }

        val operations = document.getDefinitionsOfType(OperationDefinition::class.java)
        if (operations.isEmpty()) {
            throw GraphQLRoutingException("No operations defined in GraphQL document")
        }

        val operation = resolveOperation(operations, operationName)

        if (operation.operation != OperationDefinition.Operation.QUERY &&
            operation.operation != OperationDefinition.Operation.MUTATION
        ) {
            throw GraphQLRoutingException("Unsupported operation type: ${operation.operation.name.lowercase()}")
        }

        val selections = operation.selectionSet?.selections.orEmpty()
        if (selections.isEmpty()) {
            throw GraphQLRoutingException("Operation contains no fields")
        }

        val fieldNames = selections.map { selection ->
            if (selection !is Field) {
                throw GraphQLRoutingException("Unsupported selection ${selection.javaClass.simpleName} at operation root")
            }
            selection.name
        }

        val routingTable = when (operation.operation) {
            OperationDefinition.Operation.QUERY -> rootSchema.queryRouting()
            OperationDefinition.Operation.MUTATION -> rootSchema.mutationRouting()
            else -> emptyMap()
        }

        if (routingTable.isEmpty()) {
            throw GraphQLRoutingException("No routing information available for ${operation.operation.name.lowercase()} operations")
        }

        val services = fieldNames.map { fieldName ->
            routingTable[fieldName]
                ?: throw GraphQLRoutingException("Field '$fieldName' is not available in gateway schema")
        }

        val distinctServices = services.distinct()
        if (distinctServices.size > 1) {
            val names = distinctServices.joinToString(", ") { it.name }
            throw GraphQLRoutingException("Query references fields from multiple upstream services: $names")
        }

        if (distinctServices.isEmpty()) {
            throw GraphQLRoutingException("No routed fields were resolved for the requested operation")
        }

        return RoutedGraphQLRequest(
            service = distinctServices.first(),
            operationType = operation.operation,
            rootFieldNames = fieldNames,
        )
    }

    private fun resolveOperation(
        operations: List<OperationDefinition>,
        operationName: String?,
    ): OperationDefinition {
        if (operationName != null) {
            return operations.find { op -> op.name == operationName }
                ?: throw GraphQLRoutingException("Operation '$operationName' was not found in the GraphQL document")
        }

        if (operations.size > 1) {
            throw GraphQLRoutingException("operationName must be provided when multiple operations are present")
        }

        return operations.first()
    }
}
