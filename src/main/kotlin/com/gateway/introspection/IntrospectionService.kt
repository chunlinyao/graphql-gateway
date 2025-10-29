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
        }
        mutationType {
          name
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

        val mutationType = schema.mutationType
        // Fetch root field names using standard GraphQL __type query per spec, in two lightweight calls
        val queryFieldNames = queryType.name?.let { name -> fetchRootFieldNames(upstream, name) } ?: emptyList()
        val mutationFieldNames = mutationType?.name?.let { name -> fetchRootFieldNames(upstream, name) } ?: emptyList()

        return UpstreamSchema(
            service = upstream,
            queryTypeName = queryType.name,
            queryFieldNames = queryFieldNames,
            mutationTypeName = mutationType?.name,
            mutationFieldNames = mutationFieldNames,
            typeDefinitions = emptyMap(),
        )
    }

    private fun fetchRootFieldNames(upstream: UpstreamService, typeName: String): List<String> {
        val query = """
            query GatewayTypeFieldsQuery(${ '$' }typeName: String!) {
              __type(name: ${ '$' }typeName) {
                fields(includeDeprecated: true) { name }
              }
            }
        """.trimIndent()

        val variables = mapOf("typeName" to typeName)
        val requestBody = jsonMapper.writeValueAsString(mapOf("query" to query, "variables" to variables))

        val request = HttpRequest.newBuilder()
            .uri(URI.create(upstream.url))
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(30))
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            return emptyList()
        }

        val payload: TypeFieldsHttpResponse = try {
            jsonMapper.readValue(response.body(), TypeFieldsHttpResponse::class.java)
        } catch (e: Exception) {
            return emptyList()
        }
        if (payload.errors?.isNotEmpty() == true) {
            return emptyList()
        }

        return payload.data?.type?.fields?.mapNotNull { it.name } ?: emptyList()
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
)

private data class IntrospectionRootType @JsonCreator constructor(
    @JsonProperty("name") val name: String?,
)

// Response types for __type(name: ...) { fields { name } }
private data class TypeFieldsHttpResponse @JsonCreator constructor(
    @JsonProperty("data") val data: TypeFieldsData?,
    @JsonProperty("errors") val errors: List<GraphQLError>?,
)

private data class TypeFieldsData @JsonCreator constructor(
    @JsonProperty("__type") val type: TypeWithFields?,
)

private data class TypeWithFields @JsonCreator constructor(
    @JsonProperty("fields") val fields: List<FieldNameOnly>?,
)

private data class FieldNameOnly @JsonCreator constructor(
    @JsonProperty("name") val name: String?,
)
