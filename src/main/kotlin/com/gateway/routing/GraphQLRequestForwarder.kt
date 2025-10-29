package com.gateway.routing

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.gateway.graphql.GraphQLRequest
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ForwardedGraphQLResponse(
    val statusCode: Int,
    val body: String,
)

class GraphQLRequestForwarder(
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build(),
) {
    private val mapper = jacksonObjectMapper()

    suspend fun forward(
        request: GraphQLRequest,
        routedRequest: RoutedGraphQLRequest,
        authorizationHeader: String?,
    ): ForwardedGraphQLResponse {
        val payload = linkedMapOf<String, Any?>()
        payload["query"] = request.query
        if (request.operationName != null) {
            payload["operationName"] = request.operationName
        }
        if (request.variables != null) {
            payload["variables"] = request.variables
        }

        val body = mapper.writeValueAsString(payload)

        val builder = HttpRequest.newBuilder()
            .uri(URI.create(routedRequest.service.url))
            .header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            .POST(HttpRequest.BodyPublishers.ofString(body))

        if (!authorizationHeader.isNullOrBlank()) {
            builder.header(HttpHeaders.Authorization, authorizationHeader)
        }

        val upstreamRequest = builder.build()

        val response = withContext(Dispatchers.IO) {
            httpClient.send(upstreamRequest, HttpResponse.BodyHandlers.ofString())
        }

        return ForwardedGraphQLResponse(
            statusCode = response.statusCode(),
            body = response.body(),
        )
    }
}
