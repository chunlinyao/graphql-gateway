package com.gateway.schema

import com.gateway.config.UpstreamService
import com.gateway.introspection.GraphQLFieldDefinition
import com.gateway.introspection.UpstreamSchema
import org.slf4j.LoggerFactory

/**
 * A routed field on the merged gateway root schema.
 */
data class RoutedField(
    val fieldName: String,
    val service: UpstreamService,
    val definition: GraphQLFieldDefinition,
)

/**
 * Representation of a root operation type (Query/Mutation) after merging.
 */
data class RootTypeDefinition(
    val typeName: String,
    val fields: List<RoutedField>,
) {
    fun routing(): Map<String, UpstreamService> = fields.associate { field -> field.fieldName to field.service }
}

/**
 * Container for the merged root schema that the gateway will expose.
 */
data class GatewayRootSchema(
    val query: RootTypeDefinition,
    val mutation: RootTypeDefinition?,
) {
    fun queryRouting(): Map<String, UpstreamService> = query.routing()

    fun mutationRouting(): Map<String, UpstreamService> = mutation?.routing() ?: emptyMap()
}

class RootSchemaMerger {
    private val logger = LoggerFactory.getLogger(RootSchemaMerger::class.java)

    fun merge(upstreamSchemas: List<UpstreamSchema>): GatewayRootSchema {
        val sortedSchemas = upstreamSchemas.sortedBy { schema -> schema.service.priority }

        val queryFields = LinkedHashMap<String, RoutedField>()
        val mutationFields = LinkedHashMap<String, RoutedField>()

        sortedSchemas.forEach { schema ->
            val queryDefinition = schema.queryTypeName?.let { typeName ->
                schema.typeDefinitions[typeName]
            }
            schema.queryFieldNames.forEach { fieldName ->
                val existing = queryFields[fieldName]
                val fieldDefinition = queryDefinition?.fields?.find { it.name == fieldName }
                    ?: throw IllegalStateException(
                        "Missing field definition for query field $fieldName on service ${schema.service.name}",
                    )
                if (existing == null) {
                    queryFields[fieldName] = RoutedField(fieldName, schema.service, fieldDefinition)
                } else if (existing.service != schema.service) {
                    logger.info(
                        "Query field {} from service {} skipped due to existing owner {}",
                        fieldName,
                        schema.service.name,
                        existing.service.name,
                    )
                }
            }

            val mutationDefinition = schema.mutationTypeName?.let { typeName ->
                schema.typeDefinitions[typeName]
            }
            schema.mutationFieldNames.forEach { fieldName ->
                val existing = mutationFields[fieldName]
                val fieldDefinition = mutationDefinition?.fields?.find { it.name == fieldName }
                    ?: throw IllegalStateException(
                        "Missing field definition for mutation field $fieldName on service ${schema.service.name}",
                    )
                if (existing == null) {
                    mutationFields[fieldName] = RoutedField(fieldName, schema.service, fieldDefinition)
                } else if (existing.service != schema.service) {
                    logger.info(
                        "Mutation field {} from service {} skipped due to existing owner {}",
                        fieldName,
                        schema.service.name,
                        existing.service.name,
                    )
                }
            }
        }

        val queryTypeName = sortedSchemas.firstNotNullOfOrNull { schema -> schema.queryTypeName } ?: "Query"
        val mutationTypeName = sortedSchemas.firstNotNullOfOrNull { schema -> schema.mutationTypeName }

        return GatewayRootSchema(
            query = RootTypeDefinition(queryTypeName, queryFields.values.toList()),
            mutation = mutationTypeName?.let { typeName ->
                RootTypeDefinition(typeName, mutationFields.values.toList())
            },
        )
    }
}
