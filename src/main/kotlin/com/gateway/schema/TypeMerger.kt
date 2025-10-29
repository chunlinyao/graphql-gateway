package com.gateway.schema

import com.gateway.config.UpstreamService
import com.gateway.introspection.GraphQLFieldDefinition
import com.gateway.introspection.GraphQLInputValueDefinition
import com.gateway.introspection.GraphQLTypeDefinition
import com.gateway.introspection.GraphQLTypeKind
import com.gateway.introspection.GraphQLTypeRef
import com.gateway.introspection.UpstreamSchema
import org.slf4j.LoggerFactory

/**
 * Representation of the merged non-root types that the gateway exposes.
 */
data class GatewayTypeRegistry(
    val objectTypes: Map<String, GatewayObjectType>,
    val inputObjectTypes: Map<String, GatewayInputObjectType>,
)

data class GatewayObjectType(
    val name: String,
    val fields: List<GatewayObjectField>,
)

data class GatewayObjectField(
    val name: String,
    val type: GraphQLTypeRef,
    val arguments: List<GatewayInputValue>,
    val owner: UpstreamService,
)

data class GatewayInputValue(
    val name: String,
    val type: GraphQLTypeRef,
)

data class GatewayInputObjectType(
    val name: String,
    val inputFields: List<GatewayInputField>,
)

data class GatewayInputField(
    val name: String,
    val type: GraphQLTypeRef,
    val owner: UpstreamService,
)

/**
 * Merge type definitions from all upstream schemas according to priority rules.
 */
class TypeMerger {
    private val logger = LoggerFactory.getLogger(TypeMerger::class.java)

    fun merge(upstreamSchemas: List<UpstreamSchema>): GatewayTypeRegistry {
        val sorted = upstreamSchemas.sortedByDescending { schema -> schema.service.priority }

        val objectAccumulators = linkedMapOf<String, MutableObjectTypeAccumulator>()
        val inputAccumulators = linkedMapOf<String, MutableInputObjectTypeAccumulator>()

        sorted.forEach { schema ->
            schema.typeDefinitions.values.forEach { definition ->
                when (definition.kind) {
                    GraphQLTypeKind.OBJECT -> mergeObjectType(objectAccumulators, definition, schema)
                    GraphQLTypeKind.INPUT_OBJECT -> mergeInputObjectType(inputAccumulators, definition, schema)
                    else -> Unit
                }
            }
        }

        val objectTypes = objectAccumulators
            .mapValues { (_, accumulator) -> accumulator.toGatewayType() }
            .filterValues { type -> type.fields.isNotEmpty() }

        val inputObjectTypes = inputAccumulators
            .mapValues { (_, accumulator) -> accumulator.toGatewayType() }
            .filterValues { type -> type.inputFields.isNotEmpty() }

        return GatewayTypeRegistry(objectTypes, inputObjectTypes)
    }

    private fun mergeObjectType(
        registry: MutableMap<String, MutableObjectTypeAccumulator>,
        definition: GraphQLTypeDefinition,
        schema: UpstreamSchema,
    ) {
        if (definition.name == schema.queryTypeName || definition.name == schema.mutationTypeName) {
            return
        }
        val accumulator = registry.getOrPut(definition.name) { MutableObjectTypeAccumulator(definition.name) }
        definition.fields.forEach { field ->
            accumulator.addField(field, schema.service, logger)
        }
    }

    private fun mergeInputObjectType(
        registry: MutableMap<String, MutableInputObjectTypeAccumulator>,
        definition: GraphQLTypeDefinition,
        schema: UpstreamSchema,
    ) {
        val accumulator = registry.getOrPut(definition.name) { MutableInputObjectTypeAccumulator(definition.name) }
        definition.inputFields.forEach { field ->
            accumulator.addField(field, schema.service, logger)
        }
    }
}

private class MutableObjectTypeAccumulator(
    private val typeName: String,
) {
    private val fields: MutableMap<String, GatewayObjectField> = linkedMapOf()

    fun addField(definition: GraphQLFieldDefinition, service: UpstreamService, logger: org.slf4j.Logger) {
        val existing = fields[definition.name]
        val candidate = definition.toGatewayField(service)
        if (existing == null) {
            fields[definition.name] = candidate
            return
        }

        val higherPriority = service.priority < existing.owner.priority
        val definitionsMatch = fieldDefinitionsEqual(existing, definition)

        if (higherPriority || !definitionsMatch) {
            if (definitionsMatch) {
                logger.info(
                    "Service {} (priority={}) takes ownership of field {} on type {} from {} (priority={}) due to higher priority",
                    service.name,
                    service.priority,
                    definition.name,
                    typeName,
                    existing.owner.name,
                    existing.owner.priority,
                )
            } else {
                logger.info(
                    "Service {} (priority={}) overrides field {} on type {} previously declared by {} (priority={})",
                    service.name,
                    service.priority,
                    definition.name,
                    typeName,
                    existing.owner.name,
                    existing.owner.priority,
                )
            }
            fields[definition.name] = candidate
        }
    }

    fun toGatewayType(): GatewayObjectType = GatewayObjectType(typeName, fields.values.toList())
}

private class MutableInputObjectTypeAccumulator(
    private val typeName: String,
) {
    private val fields: MutableMap<String, GatewayInputField> = linkedMapOf()

    fun addField(definition: GraphQLInputValueDefinition, service: UpstreamService, logger: org.slf4j.Logger) {
        val existing = fields[definition.name]
        val candidate = definition.toGatewayField(service)
        if (existing == null) {
            fields[definition.name] = candidate
            return
        }

        val higherPriority = service.priority < existing.owner.priority
        val definitionsMatch = inputFieldDefinitionsEqual(existing, definition)

        if (higherPriority || !definitionsMatch) {
            if (definitionsMatch) {
                logger.info(
                    "Service {} (priority={}) takes ownership of field {} on input type {} from {} (priority={}) due to higher priority",
                    service.name,
                    service.priority,
                    definition.name,
                    typeName,
                    existing.owner.name,
                    existing.owner.priority,
                )
            } else {
                logger.info(
                    "Service {} (priority={}) overrides field {} on input type {} previously declared by {} (priority={})",
                    service.name,
                    service.priority,
                    definition.name,
                    typeName,
                    existing.owner.name,
                    existing.owner.priority,
                )
            }
            fields[definition.name] = candidate
        }
    }

    fun toGatewayType(): GatewayInputObjectType = GatewayInputObjectType(typeName, fields.values.toList())
}

private fun GraphQLFieldDefinition.toGatewayField(service: UpstreamService): GatewayObjectField = GatewayObjectField(
    name = name,
    type = type,
    arguments = arguments.map { arg -> GatewayInputValue(arg.name, arg.type) },
    owner = service,
)

private fun GraphQLInputValueDefinition.toGatewayField(service: UpstreamService): GatewayInputField = GatewayInputField(
    name = name,
    type = type,
    owner = service,
)

private fun inputFieldDefinitionsEqual(
    existing: GatewayInputField,
    candidate: GraphQLInputValueDefinition,
): Boolean = existing.type == candidate.type

private fun fieldDefinitionsEqual(existing: GatewayObjectField, candidate: GraphQLFieldDefinition): Boolean {
    if (existing.type != candidate.type) {
        return false
    }
    if (existing.arguments.size != candidate.arguments.size) {
        return false
    }
    val existingArgs = existing.arguments.map { arg -> arg.name to arg.type }.sortedBy { it.first }
    val candidateArgs = candidate.arguments.map { arg -> arg.name to arg.type }.sortedBy { it.first }
    return existingArgs == candidateArgs
}
