package com.gateway.introspection

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.gateway.config.UpstreamService
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

private val logger = LoggerFactory.getLogger(IntrospectionService::class.java)

private val jsonMapper: ObjectMapper = ObjectMapper()
    .registerModule(KotlinModule.Builder().build())
    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)

private const val SCHEMA_OVERVIEW_QUERY = """
    query GatewaySchemaOverview {
      __schema {
        queryType {
          name
        }
        mutationType {
          name
        }
        types {
          kind
          name
        }
      }
    }
"""

private const val TYPE_DETAILS_QUERY = """
    query GatewayTypeDetails(${ '$' }typeName: String!) {
      __type(name: ${ '$' }typeName) {
        kind
        name
        fields(includeDeprecated: true) {
          name
          args {
            name
            type {
              ...GatewayTypeRef
            }
          }
          type {
            ...GatewayTypeRef
          }
        }
        inputFields {
          name
          type {
            ...GatewayTypeRef
          }
        }
        enumValues {
          name
        }
      }
    }

    fragment GatewayTypeRef on __Type {
      kind
      name
      ofType {
        kind
        name
        ofType {
          kind
          name
          ofType {
            kind
            name
            ofType {
              kind
              name
              ofType {
                kind
                name
                ofType {
                  kind
                  name
                }
              }
            }
          }
        }
      }
    }
"""

class IntrospectionService(
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build(),
) {

    fun introspectAll(upstreams: List<UpstreamService>): IntrospectionBatchResult {
        val successful = mutableListOf<UpstreamSchema>()
        val failures = mutableListOf<IntrospectionFailure>()

        upstreams.forEach { upstream ->
            try {
                val schema = introspect(upstream)
                logger.info(
                    "Upstream introspection: service={}, queryType={}, queryFields={}, mutationType={}, mutationFields={}",
                    upstream.name,
                    schema.queryTypeName,
                    schema.queryFieldNames.joinToString(","),
                    schema.mutationTypeName,
                    schema.mutationFieldNames.joinToString(","),
                )
                successful += schema
            } catch (ex: Exception) {
                logger.warn(
                    "Failed to introspect upstream service: name={}, url={}, reason={}",
                    upstream.name,
                    upstream.url,
                    ex.message ?: ex.javaClass.simpleName,
                    ex,
                )
                failures += IntrospectionFailure(
                    service = upstream,
                    reason = ex.message ?: ex.javaClass.simpleName,
                )
            }
        }

        return IntrospectionBatchResult(successful, failures)
    }

    fun introspect(upstream: UpstreamService): UpstreamSchema {
        val schemaOverview = fetchSchemaOverview(upstream)
        val queryTypeName = schemaOverview.queryType?.name
        val mutationTypeName = schemaOverview.mutationType?.name

        if (queryTypeName == null) {
            throw IllegalStateException("Introspection for ${upstream.name} did not include a queryType definition")
        }

        val typeDefinitions = linkedMapOf<String, GraphQLTypeDefinition>()

        val typeNamesToFetch = schemaOverview.types.orEmpty()
            .mapNotNull { summary -> summary.toFetchableTypeName() }
            .toMutableSet()

        typeNamesToFetch += queryTypeName
        mutationTypeName?.let { typeNamesToFetch += it }

        typeNamesToFetch.forEach { typeName ->
            val definition = fetchTypeDefinition(upstream, typeName)
            if (definition != null) {
                typeDefinitions[typeName] = definition
            }
        }

        val queryTypeDefinition = typeDefinitions[queryTypeName]
            ?: throw IllegalStateException("Introspection for ${upstream.name} did not return fields for $queryTypeName")
        val mutationTypeDefinition = mutationTypeName?.let { typeDefinitions[it] }

        val queryFieldDefinitions = queryTypeDefinition.fields
        val mutationFieldDefinitions = mutationTypeDefinition?.fields.orEmpty()

        return UpstreamSchema(
            service = upstream,
            queryTypeName = queryTypeName,
            queryFieldNames = queryFieldDefinitions.map { it.name },
            mutationTypeName = mutationTypeName,
            mutationFieldNames = mutationFieldDefinitions.map { it.name },
            typeDefinitions = typeDefinitions,
        )
    }

    private fun fetchSchemaOverview(upstream: UpstreamService): IntrospectionOverviewSchema {
        val payload = executeQuery<IntrospectionOverviewHttpResponse>(
            upstream = upstream,
            query = SCHEMA_OVERVIEW_QUERY,
        )

        if (payload.errors?.isNotEmpty() == true) {
            val message = payload.errors.joinToString(", ") { error -> error.message ?: "unknown error" }
            throw IllegalStateException(
                "Introspection for ${upstream.name} (${upstream.url}) returned errors: $message",
            )
        }

        return payload.data?.schema
            ?: throw IllegalStateException("Introspection for ${upstream.name} returned no schema data")
    }

    private fun fetchTypeDefinition(upstream: UpstreamService, typeName: String): GraphQLTypeDefinition? {
        val payload = executeQuery<TypeDetailsHttpResponse>(
            upstream = upstream,
            query = TYPE_DETAILS_QUERY,
            variables = mapOf("typeName" to typeName),
        )

        if (payload.errors?.isNotEmpty() == true) {
            val message = payload.errors.joinToString(", ") { error -> error.message ?: "unknown error" }
            throw IllegalStateException(
                "Type introspection for ${upstream.name} (${upstream.url}) returned errors: $message",
            )
        }

        return payload.data?.type?.toGraphQLTypeDefinition()
    }

    private inline fun <reified T> executeQuery(
        upstream: UpstreamService,
        query: String,
        variables: Map<String, Any?> = emptyMap(),
    ): T {
        val payload = mutableMapOf<String, Any?>("query" to query.trimIndent())
        if (variables.isNotEmpty()) {
            payload["variables"] = variables
        }

        val requestBody = jsonMapper.writeValueAsString(payload)
        val request = HttpRequest.newBuilder()
            .uri(URI.create(upstream.url))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(30))
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException(
                "Introspection request to ${upstream.name} (${upstream.url}) failed with status ${response.statusCode()}",
            )
        }

        return jsonMapper.readValue(response.body())
    }
}

data class IntrospectionBatchResult(
    val schemas: List<UpstreamSchema>,
    val failures: List<IntrospectionFailure>,
) {
    val hasFailures: Boolean
        get() = failures.isNotEmpty()
}

data class IntrospectionFailure(
    val service: UpstreamService,
    val reason: String,
)

data class UpstreamSchema(
    val service: UpstreamService,
    val queryTypeName: String?,
    val queryFieldNames: List<String>,
    val mutationTypeName: String?,
    val mutationFieldNames: List<String>,
    val typeDefinitions: Map<String, GraphQLTypeDefinition> = emptyMap(),
)

private data class IntrospectionOverviewHttpResponse @JsonCreator constructor(
    @JsonProperty("data") val data: IntrospectionOverviewData?,
    @JsonProperty("errors") val errors: List<GraphQLError>?,
)

private data class IntrospectionOverviewData @JsonCreator constructor(
    @JsonProperty("__schema") val schema: IntrospectionOverviewSchema?,
)

private data class IntrospectionOverviewSchema @JsonCreator constructor(
    @JsonProperty("queryType") val queryType: IntrospectionNamedType?,
    @JsonProperty("mutationType") val mutationType: IntrospectionNamedType?,
    @JsonProperty("types") val types: List<IntrospectionTypeSummary>?,
)

private data class IntrospectionNamedType @JsonCreator constructor(
    @JsonProperty("name") val name: String?,
)

private data class IntrospectionTypeSummary @JsonCreator constructor(
    @JsonProperty("kind") val kind: String?,
    @JsonProperty("name") val name: String?,
) {
    fun toFetchableTypeName(): String? {
        val typeName = name ?: return null
        if (typeName.startsWith("__")) {
            return null
        }
        val kindEnum = kind?.let { runCatching { GraphQLTypeKind.valueOf(it) }.getOrNull() }
        return when (kindEnum) {
            GraphQLTypeKind.OBJECT, GraphQLTypeKind.INPUT_OBJECT, GraphQLTypeKind.ENUM -> typeName
            else -> null
        }
    }
}

private data class TypeDetailsHttpResponse @JsonCreator constructor(
    @JsonProperty("data") val data: TypeDetailsData?,
    @JsonProperty("errors") val errors: List<GraphQLError>?,
)

private data class TypeDetailsData @JsonCreator constructor(
    @JsonProperty("__type") val type: IntrospectionFullType?,
)

private data class GraphQLError @JsonCreator constructor(
    @JsonProperty("message") val message: String?,
)

private data class IntrospectionEnumValue @JsonCreator constructor(
    @JsonProperty("name") val name: String?,
)

private data class IntrospectionFullType @JsonCreator constructor(
    @JsonProperty("kind") val kind: String?,
    @JsonProperty("name") val name: String?,
    @JsonProperty("fields") val fields: List<IntrospectionField>?,
    @JsonProperty("inputFields") val inputFields: List<IntrospectionInputValue>?,
    @JsonProperty("enumValues") val enumValues: List<IntrospectionEnumValue>?,
)

private data class IntrospectionField @JsonCreator constructor(
    @JsonProperty("name") val name: String?,
    @JsonProperty("type") val type: IntrospectionTypeRef?,
    @JsonProperty("args") val args: List<IntrospectionInputValue>?,
)

private data class IntrospectionInputValue @JsonCreator constructor(
    @JsonProperty("name") val name: String?,
    @JsonProperty("type") val type: IntrospectionTypeRef?,
)

private data class IntrospectionTypeRef @JsonCreator constructor(
    @JsonProperty("kind") val kind: String?,
    @JsonProperty("name") val name: String?,
    @JsonProperty("ofType") val ofType: IntrospectionTypeRef?,
)

private fun IntrospectionField.toGraphQLFieldDefinition(): GraphQLFieldDefinition? {
    val fieldName = name ?: return null
    val fieldType = type?.toGraphQLTypeRef() ?: return null
    val arguments = args.orEmpty().mapNotNull { it.toGraphQLInputValueDefinition() }
    return GraphQLFieldDefinition(fieldName, fieldType, arguments)
}

private fun IntrospectionInputValue.toGraphQLInputValueDefinition(): GraphQLInputValueDefinition? {
    val inputName = name ?: return null
    val inputType = type?.toGraphQLTypeRef() ?: return null
    return GraphQLInputValueDefinition(inputName, inputType)
}

private fun IntrospectionTypeRef.toGraphQLTypeRef(): GraphQLTypeRef {
    val kindEnum = kind?.let { GraphQLTypeKind.valueOf(it) }
        ?: throw IllegalStateException("Unknown GraphQL type kind: $kind")
    val ofTypeRef = ofType?.toGraphQLTypeRef()
    return GraphQLTypeRef(kindEnum, name, ofTypeRef)
}

private fun IntrospectionFullType.toGraphQLTypeDefinition(): GraphQLTypeDefinition? {
    val typeName = name ?: return null
    val kindEnum = kind?.let { GraphQLTypeKind.valueOf(it) } ?: return null
    val fieldDefinitions = fields.orEmpty().mapNotNull { it.toGraphQLFieldDefinition() }
    val inputDefinitions = inputFields.orEmpty().mapNotNull { it.toGraphQLInputValueDefinition() }
    val enumValueNames = enumValues.orEmpty().mapNotNull { it.name }
    return GraphQLTypeDefinition(
        name = typeName,
        kind = kindEnum,
        fields = fieldDefinitions,
        inputFields = inputDefinitions,
        enumValues = enumValueNames,
    )
}
