package com.gateway.graphql

import graphql.GraphQL
import graphql.schema.GraphQLSchema
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import org.slf4j.LoggerFactory

class GatewayGraphQLFactory(
    private val schemaParser: SchemaParser = SchemaParser(),
    private val schemaGenerator: SchemaGenerator = SchemaGenerator(),
) {
    private val logger = LoggerFactory.getLogger(GatewayGraphQLFactory::class.java)

    fun create(mergedSdl: String): GraphQL? {
        if (mergedSdl.isBlank()) {
            logger.warn("Merged SDL is blank; GraphQL introspection executor will be unavailable")
            return null
        }

        return try {
            val typeRegistry = schemaParser.parse(mergedSdl)
            val runtimeWiring = RuntimeWiring.newRuntimeWiring().build()
            val graphQLSchema: GraphQLSchema = schemaGenerator.makeExecutableSchema(typeRegistry, runtimeWiring)
            GraphQL.newGraphQL(graphQLSchema).build()
        } catch (ex: Exception) {
            logger.warn(
                "Failed to construct GraphQL schema for introspection handling: {}",
                ex.message ?: ex.javaClass.simpleName,
                ex,
            )
            null
        }
    }
}
