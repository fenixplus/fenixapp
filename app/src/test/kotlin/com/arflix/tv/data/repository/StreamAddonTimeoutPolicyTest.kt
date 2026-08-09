package com.arflix.tv.data.repository

import com.arflix.tv.data.model.Addon
import com.arflix.tv.data.model.AddonManifest
import com.arflix.tv.data.model.AddonType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamAddonTimeoutPolicyTest {
    @Test
    fun `hdhub configured instances use the slow aggregator timeout`() {
        val addon = addon(
            id = "com.stremio.HdHub_configured",
            name = "HdHub",
            manifestId = "com.stremio.HdHub"
        )

        assertTrue(usesSlowAggregatorTimeout(addon))
    }

    @Test
    fun `regular direct addon keeps the default timeout`() {
        val addon = addon(
            id = "example.direct_configured",
            name = "Direct Source",
            manifestId = "example.direct"
        )

        assertFalse(usesSlowAggregatorTimeout(addon))
    }

    private fun addon(id: String, name: String, manifestId: String) = Addon(
        id = id,
        name = name,
        version = "1.0.0",
        description = "",
        isInstalled = true,
        type = AddonType.CUSTOM,
        url = "https://example.invalid/configured/manifest.json",
        manifest = AddonManifest(
            id = manifestId,
            name = name,
            version = "1.0.0",
            description = "",
            types = listOf("movie", "series")
        )
    )
}
