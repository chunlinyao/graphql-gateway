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

private const val INTROSPECTION_QUERY = """
    query GatewayIntrospectionQuery {
      __schema {
        queryType {
          name
          fields(includeDeprecated: true) {
            name
          }
        }
        mutationType {
          name
          fields(includeDeprecated: true) {
            name
          }
        }
        types {
          kind
          name
          fields(includeDeprecated: true) {
            name
            args {
              name
              type {
                ...TypeRef
              }
            }
            type {
              ...TypeRef
            }
          }
          inputFields {
            name
            type {
              ...TypeRef
            }
          }
        }
      }
    }

    fragment TypeRef on __Type {
      kind
      name
      ofType {
        ...TypeRef
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
        val requestBody = jsonMapper.writeValueAsString(mapOf("query" to INTROSPECTION_QUERY.trimIndent()))
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

        val payload: IntrospectionHttpResponse = jsonMapper.readValue(response.body())
        val schema = payload.data?.schema
        if (payload.errors?.isNotEmpty() == true) {
            val message = payload.errors.joinToString(", ") { error -> error.message ?: "unknown error" }
            throw IllegalStateException(
                "Introspection for ${upstream.name} (${upstream.url}) returned errors: $message",
            )
        }

        val queryType = schema?.queryType
            ?: throw IllegalStateException("Introspection for ${upstream.name} did not include a queryType definition")

        val queryFields = queryType.fields?.mapNotNull { it.name } ?: emptyList()
        val mutationType = schema.mutationType
        val mutationFields = mutationType?.fields?.mapNotNull { it.name } ?: emptyList()
        val typeDefinitions = schema.types
            ?.mapNotNull { type -> type.toDefinition() }
            ?.associateBy { definition -> definition.name }
            ?: emptyMap()

        return UpstreamSchema(
            service = upstream,
            queryTypeName = queryType.name,
            queryFieldNames = queryFields,
            mutationTypeName = mutationType?.name,
            mutationFieldNames = mutationFields,
            typeDefinitions = typeDefinitions,
        )
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

private fun IntrospectionType.toDefinition(): GraphQLTypeDefinition? {
    val typeName = name ?: return null
    val kindEnum = kind.toTypeKind() ?: return null
    val fieldDefinitions = fields?.mapNotNull { field -> field.toDefinition() } ?: emptyList()
    val inputDefinitions = inputFields?.mapNotNull { input -> input.toDefinition() } ?: emptyList()
    return GraphQLTypeDefinition(typeName, kindEnum, fieldDefinitions, inputDefinitions)
}

private fun IntrospectionField.toDefinition(): GraphQLFieldDefinition? {
    val fieldName = name ?: return null
    val typeRef = type?.toTypeRef() ?: return null
    val argumentDefinitions = args?.mapNotNull { arg -> arg.toDefinition() } ?: emptyList()
    return GraphQLFieldDefinition(fieldName, typeRef, argumentDefinitions)
}

private fun IntrospectionInputValue.toDefinition(): GraphQLInputValueDefinition? {
    val inputName = name ?: return null
    val typeRef = type?.toTypeRef() ?: return null
    return GraphQLInputValueDefinition(inputName, typeRef)
}

private fun IntrospectionTypeRef.toTypeRef(): GraphQLTypeRef? {
    val kindEnum = kind.toTypeKind() ?: return null
    val nested = ofType?.toTypeRef()
    return GraphQLTypeRef(kindEnum, name, nested)
}

private fun String?.toTypeKind(): GraphQLTypeKind? {
    if (this == null) {
        return null
    }
    return runCatching { GraphQLTypeKind.valueOf(this) }.getOrNull()
}

private data class IntrospectionHttpResponse @JsonCreator constructor(
    @JsonProperty("data") val data: IntrospectionData?,
    @JsonProperty("errors") val errors: List<GraphQLError>?,
)

private data class GraphQLError @JsonCreator constructor(
    @JsonProperty("message") val message: String?,
)

private data class IntrospectionData @JsonCreator constructor(
    @JsonProperty("__schema") val schema: IntrospectionSchema?,
)

private data class IntrospectionSchema @JsonCreator constructor(
    @JsonProperty("queryType") val queryType: IntrospectionRootType?,
    @JsonProperty("mutationType") val mutationType: IntrospectionRootType?,
    @JsonProperty("types") val types: List<IntrospectionType>?,
)

private data class IntrospectionRootType @JsonCreator constructor(
    @JsonProperty("name") val name: String?,
    @JsonProperty("fields") val fields: List<IntrospectionField>?,
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

private data class IntrospectionType @JsonCreator constructor(
    @JsonProperty("kind") val kind: String?,
    @JsonProperty("name") val name: String?,
    @JsonProperty("fields") val fields: List<IntrospectionField>?,
    @JsonProperty("inputFields") val inputFields: List<IntrospectionInputValue>?,
)

private data class IntrospectionTypeRef @JsonCreator constructor(
    @JsonProperty("kind") val kind: String?,
    @JsonProperty("name") val name: String?,
    @JsonProperty("ofType") val ofType: IntrospectionTypeRef?,
)
