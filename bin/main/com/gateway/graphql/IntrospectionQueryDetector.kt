package com.gateway.graphql

import graphql.language.Field
import graphql.language.FragmentDefinition
import graphql.language.FragmentSpread
import graphql.language.InlineFragment
import graphql.language.OperationDefinition
import graphql.language.Selection
import graphql.parser.Parser

object IntrospectionQueryDetector {
    private val parser = Parser()

    fun isIntrospectionQuery(query: String, operationName: String?): Boolean {
        return try {
            val document = parser.parseDocument(query)
            val operations = document.definitions.filterIsInstance<OperationDefinition>()
            val fragments = document.definitions
                .filterIsInstance<FragmentDefinition>()
                .associateBy { definition -> definition.name }
            if (operations.isEmpty()) {
                return false
            }

            val targetOperations = if (operationName != null) {
                operations.filter { operation -> operation.name == operationName }
            } else {
                operations
            }

            if (targetOperations.isEmpty()) {
                return false
            }

            targetOperations.all { operation ->
                operation.operation == OperationDefinition.Operation.QUERY &&
                    operation.selectionSet.selections.all { selection ->
                        selection.isIntrospectionSelection(fragments)
                    }
            }
        } catch (ex: Exception) {
            false
        }
    }

    private fun Selection<*>.isIntrospectionSelection(
        fragments: Map<String, FragmentDefinition>,
    ): Boolean = when (this) {
        is Field -> name.startsWith("__")
        is InlineFragment -> selectionSet.selections.all { selection ->
            selection.isIntrospectionSelection(fragments)
        }
        is FragmentSpread -> fragments[name]?.selectionSet?.selections?.all { selection ->
            selection.isIntrospectionSelection(fragments)
        } ?: false
        else -> false
    }
}
