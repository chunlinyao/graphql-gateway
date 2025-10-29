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
          fields {
            name
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

    fun introspectAll(upstreams: List<UpstreamService>): List<UpstreamSchema> {
        return upstreams.map { upstream ->
            val schema = introspect(upstream)
            logger.info(
                "Upstream introspection: service={}, queryType={}, fields={}",
                upstream.name,
                schema.queryTypeName,
                schema.queryFieldNames.joinToString(","),
            )
            schema
        }
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

        return UpstreamSchema(
            service = upstream,
            queryTypeName = queryType.name,
            queryFieldNames = queryFields,
        )
    }
}

data class UpstreamSchema(
    val service: UpstreamService,
    val queryTypeName: String?,
    val queryFieldNames: List<String>,
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
    @JsonProperty("queryType") val queryType: IntrospectionQueryType?,
)

private data class IntrospectionQueryType @JsonCreator constructor(
    @JsonProperty("name") val name: String?,
    @JsonProperty("fields") val fields: List<IntrospectionField>?,
)

private data class IntrospectionField @JsonCreator constructor(
    @JsonProperty("name") val name: String?,
)
