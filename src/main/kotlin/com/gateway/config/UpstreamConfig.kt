package com.gateway.config

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

private val logger: Logger = LoggerFactory.getLogger(UpstreamConfigLoader::class.java)

private val mapper: ObjectMapper = ObjectMapper(YAMLFactory())
    .registerModule(KotlinModule.Builder().build())
    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)

/**
 * Representation of a single upstream service entry.
 */
data class UpstreamService @JsonCreator constructor(
    @JsonProperty("name") val name: String,
    @JsonProperty("url") val url: String,
    @JsonProperty("priority") val priority: Int,
)

private data class UpstreamsFile @JsonCreator constructor(
    @JsonProperty("upstreams") val upstreams: List<UpstreamService> = emptyList(),
)

class UpstreamConfigLoader {
    fun load(path: String? = null): List<UpstreamService> {
        val configPath = resolvePath(path)
        if (!Files.exists(configPath)) {
            throw IllegalStateException("Upstream configuration file not found at ${'$'}{configPath.toAbsolutePath()}")
        }

        logger.info("Loading upstream configuration from {}", configPath.toAbsolutePath())
        Files.newBufferedReader(configPath).use {
            val upstreamsFile: UpstreamsFile = mapper.readValue(it)
            if (upstreamsFile.upstreams.isEmpty()) {
                throw IllegalStateException("Upstream configuration file ${'$'}{configPath.toAbsolutePath()} does not contain any upstream definitions")
            }

            return upstreamsFile.upstreams.sortedBy { service -> service.priority }
        }
    }

    private fun resolvePath(path: String?): Path {
        val configured = path ?: System.getenv("UPSTREAMS_CONFIG") ?: DEFAULT_CONFIG_PATH
        return Paths.get(configured)
    }

    companion object {
        private const val DEFAULT_CONFIG_PATH = "config/upstreams.yaml"
    }
}
