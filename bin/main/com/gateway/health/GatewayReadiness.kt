package com.gateway.health

import com.gateway.introspection.IntrospectionFailure

/**
 * Represents the readiness state of the gateway.
 */
data class GatewayReadiness(
    val expectedUpstreams: Int,
    val schemasAvailable: Int,
    val introspectionFailures: List<IntrospectionFailure>,
    val graphQLAvailable: Boolean,
) {
    val isReady: Boolean
        get() = introspectionFailures.isEmpty() && schemasAvailable == expectedUpstreams && graphQLAvailable

    fun reasons(): List<String> {
        if (isReady) {
            return emptyList()
        }

        val reasons = mutableListOf<String>()
        introspectionFailures.forEach { failure ->
            reasons += "Introspection failed for upstream '${failure.service.name}': ${failure.reason}"
        }
        if (schemasAvailable != expectedUpstreams) {
            val missing = expectedUpstreams - schemasAvailable
            if (missing > 0) {
                reasons += "${missing} upstream schema(s) missing from readiness check"
            }
        }
        if (!graphQLAvailable) {
            reasons += "Gateway schema composition unavailable"
        }
        return reasons
    }
}
