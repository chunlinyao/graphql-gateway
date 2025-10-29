package com.gateway.schema

import com.gateway.config.UpstreamService
import com.gateway.introspection.UpstreamSchema
import org.slf4j.LoggerFactory

/**
 * A routed field on the merged gateway root schema.
 */
data class RoutedField(
    val fieldName: String,
    val service: UpstreamService,
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
            schema.queryFieldNames.forEach { fieldName ->
                val existing = queryFields[fieldName]
                if (existing == null) {
                    queryFields[fieldName] = RoutedField(fieldName, schema.service)
                } else if (existing.service != schema.service) {
                    logger.info(
                        "Query field {} from service {} skipped due to existing owner {}",
                        fieldName,
                        schema.service.name,
                        existing.service.name,
                    )
                }
            }

            schema.mutationFieldNames.forEach { fieldName ->
                val existing = mutationFields[fieldName]
                if (existing == null) {
                    mutationFields[fieldName] = RoutedField(fieldName, schema.service)
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
