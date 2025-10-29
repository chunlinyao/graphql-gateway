package com.gateway.introspection

/**
 * GraphQL type kinds supported by the introspection output.
 */
enum class GraphQLTypeKind {
    SCALAR,
    OBJECT,
    INTERFACE,
    UNION,
    ENUM,
    INPUT_OBJECT,
    LIST,
    NON_NULL,
}

/**
 * Reference to a GraphQL type (possibly wrapped in LIST/NON_NULL modifiers).
 */
data class GraphQLTypeRef(
    val kind: GraphQLTypeKind,
    val name: String?,
    val ofType: GraphQLTypeRef?,
) {
    fun render(): String = when (kind) {
        GraphQLTypeKind.NON_NULL -> "${ofType?.render() ?: "Unknown"}!"
        GraphQLTypeKind.LIST -> "[${ofType?.render() ?: "Unknown"}]"
        else -> name ?: kind.name
    }
}

/**
 * GraphQL input value definition (arguments or input object fields).
 */
data class GraphQLInputValueDefinition(
    val name: String,
    val type: GraphQLTypeRef,
)

/**
 * GraphQL field definition for object types.
 */
data class GraphQLFieldDefinition(
    val name: String,
    val type: GraphQLTypeRef,
    val arguments: List<GraphQLInputValueDefinition>,
)

/**
 * GraphQL type definition extracted from introspection.
 */
data class GraphQLTypeDefinition(
    val name: String,
    val kind: GraphQLTypeKind,
    val fields: List<GraphQLFieldDefinition>,
    val inputFields: List<GraphQLInputValueDefinition>,
    val enumValues: List<String> = emptyList(),
)
