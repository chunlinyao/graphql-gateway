package com.gateway.config

import kotlin.io.path.createTempFile
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UpstreamConfigLoaderTest {
    private val loader = UpstreamConfigLoader()

    @Test
    fun `loads and sorts upstreams by priority`() {
        val tempFile = createTempFile(suffix = ".yaml")
        tempFile.writeText(
            """
            upstreams:
              - name: ServiceB
                url: https://b.example.com/graphql
                priority: 5
              - name: ServiceA
                url: https://a.example.com/graphql
                priority: 1
            """.trimIndent(),
        )

        val upstreams = loader.load(tempFile.toString())
        assertEquals(2, upstreams.size)
        assertEquals("ServiceA", upstreams.first().name)
        assertEquals(1, upstreams.first().priority)
    }

    @Test
    fun `throws when configuration file missing`() {
        assertFailsWith<IllegalStateException> {
            loader.load("/non/existent/path.yaml")
        }
    }
}
