package com.gateway

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.jackson.jackson
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import org.slf4j.LoggerFactory

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 4000
    val logger = LoggerFactory.getLogger("Gateway")

    logger.info("Starting GraphQL gateway on port {} using Ktor(Netty) + graphql-java stack", port)

    embeddedServer(Netty, port = port, module = Application::gatewayModule)
        .start(wait = true)
}

@Suppress("unused")
fun Application.gatewayModule() {
    install(ContentNegotiation) {
        jackson()
    }

    routing {
        get("/healthz") {
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }
    }
}
