package com.gateway.schema

import com.gateway.introspection.GraphQLFieldDefinition
import com.gateway.introspection.GraphQLInputValueDefinition
import com.gateway.introspection.GraphQLTypeKind
import com.gateway.introspection.GraphQLTypeRef

/**
 * Result of composing the gateway schema into an SDL representation.
 */
data class ComposedSchema(
    val sdl: String,
    val reachableObjectTypes: Set<String>,
    val reachableInputObjectTypes: Set<String>,
)

class SchemaComposer {

    fun compose(rootSchema: GatewayRootSchema, typeRegistry: GatewayTypeRegistry): ComposedSchema {
        val reachableObjects = linkedSetOf<String>()
        val reachableInputs = linkedSetOf<String>()
        val reachableEnums = linkedSetOf<String>()
        val reachableScalars = linkedSetOf<String>()
        val objectQueue = ArrayDeque<String>()
        val inputQueue = ArrayDeque<String>()

        fun markType(ref: GraphQLTypeRef) {
            val baseType = ref.unwrap()
            val typeName = baseType.name ?: return
            if (typeRegistry.objectTypes.containsKey(typeName) && reachableObjects.add(typeName)) {
                objectQueue.add(typeName)
            }
            if (typeRegistry.inputObjectTypes.containsKey(typeName) && reachableInputs.add(typeName)) {
                inputQueue.add(typeName)
            }
            if (typeRegistry.enumTypes.containsKey(typeName)) {
                reachableEnums.add(typeName)
            }
            // Track custom scalars (non-built-in scalars)
            if (baseType.kind == GraphQLTypeKind.SCALAR && !isBuiltInScalar(typeName)) {
                reachableScalars.add(typeName)
            }
        }

        fun markArguments(arguments: List<GraphQLInputValueDefinition>) {
            arguments.forEach { argument -> markType(argument.type) }
        }

        rootSchema.query.fields.forEach { field ->
            markType(field.definition.type)
            markArguments(field.definition.arguments)
        }
        rootSchema.mutation?.fields?.forEach { field ->
            markType(field.definition.type)
            markArguments(field.definition.arguments)
        }

        while (objectQueue.isNotEmpty()) {
            val typeName = objectQueue.removeFirst()
            val type = typeRegistry.objectTypes[typeName] ?: continue
            type.fields.forEach { field ->
                markType(field.type)
                field.arguments.forEach { argument -> markType(argument.type) }
            }
        }

        while (inputQueue.isNotEmpty()) {
            val typeName = inputQueue.removeFirst()
            val type = typeRegistry.inputObjectTypes[typeName] ?: continue
            type.inputFields.forEach { field -> markType(field.type) }
        }

        val sections = mutableListOf<String>()
        
        // Add schema directive to explicitly declare root types
        val schemaDirective = buildString {
            appendLine("schema {")
            append("  query: ").appendLine(rootSchema.query.typeName)
            rootSchema.mutation?.let { mutation ->
                append("  mutation: ").appendLine(mutation.typeName)
            }
            append("}")
        }
        sections += schemaDirective
        
        // Add custom scalar declarations (scalars that are not built-in GraphQL types)
        reachableScalars.sorted().forEach { scalarName ->
            sections += "scalar $scalarName"
        }
        
        sections += renderObjectType(
            keyword = "type",
            name = rootSchema.query.typeName,
            fieldDefinitions = rootSchema.query.fields.map { renderField(it.definition) },
        )

        rootSchema.mutation?.let { mutation ->
            sections += renderObjectType(
                keyword = "type",
                name = mutation.typeName,
                fieldDefinitions = mutation.fields.map { renderField(it.definition) },
            )
        }

        reachableObjects.sorted().mapNotNull { typeName ->
            typeRegistry.objectTypes[typeName]
        }.forEach { objectType ->
            sections += renderObjectType(
                keyword = "type",
                name = objectType.name,
                fieldDefinitions = objectType.fields.map { renderField(it) },
            )
        }

        reachableInputs.sorted().mapNotNull { typeName ->
            typeRegistry.inputObjectTypes[typeName]
        }.forEach { inputType ->
            sections += renderObjectType(
                keyword = "input",
                name = inputType.name,
                fieldDefinitions = inputType.inputFields.map { renderInputField(it) },
            )
        }

        reachableEnums.sorted().mapNotNull { typeName ->
            typeRegistry.enumTypes[typeName]
        }.forEach { enumType ->
            sections += renderEnumType(enumType.name, enumType.values)
        }

        val sdl = sections.joinToString(separator = "\n\n")
        return ComposedSchema(
            sdl = sdl,
            reachableObjectTypes = LinkedHashSet(reachableObjects),
            reachableInputObjectTypes = LinkedHashSet(reachableInputs),
        )
    }

    private fun GraphQLTypeRef.unwrap(): GraphQLTypeRef = when (kind) {
        GraphQLTypeKind.LIST, GraphQLTypeKind.NON_NULL -> ofType?.unwrap() ?: this
        else -> this
    }

    private fun renderField(definition: GraphQLFieldDefinition): String {
        val arguments = definition.arguments
        val renderedArguments = if (arguments.isEmpty()) {
            ""
        } else {
            arguments.joinToString(", ", prefix = "(", postfix = ")") { arg ->
                "${arg.name}: ${arg.type.render()}"
            }
        }
        return "${definition.name}${renderedArguments}: ${definition.type.render()}"
    }

    private fun renderField(field: GatewayObjectField): String {
        val arguments = field.arguments
        val renderedArguments = if (arguments.isEmpty()) {
            ""
        } else {
            arguments.joinToString(", ", prefix = "(", postfix = ")") { arg ->
                "${arg.name}: ${arg.type.render()}"
            }
        }
        return "${field.name}${renderedArguments}: ${field.type.render()}"
    }

    private fun renderInputField(field: GatewayInputField): String = "${field.name}: ${field.type.render()}"

    private fun renderObjectType(keyword: String, name: String, fieldDefinitions: List<String>): String {
        val builder = StringBuilder()
        builder.appendLine("$keyword $name {")
        fieldDefinitions.forEach { field ->
            builder.append("  ").appendLine(field)
        }
        builder.append("}")
        return builder.toString()
    }

    private fun renderEnumType(name: String, values: List<String>): String {
        val builder = StringBuilder()
        builder.appendLine("enum $name {")
        values.forEach { value ->
            builder.append("  ").appendLine(value)
        }
        builder.append("}")
        return builder.toString()
    }
    
    private fun isBuiltInScalar(typeName: String): Boolean {
        // GraphQL built-in scalar types
        return typeName in setOf("Int", "Float", "String", "Boolean", "ID")
    }
}
