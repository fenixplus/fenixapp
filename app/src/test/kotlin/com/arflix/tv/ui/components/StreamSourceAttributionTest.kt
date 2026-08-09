package com.arflix.tv.ui.components

import com.arflix.tv.data.model.StreamBehaviorHints
import com.arflix.tv.data.model.StreamSource
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamSourceAttributionTest {
    @Test
    fun `structured provider and indexer are shown without internal codes`() {
        val stream = source(
            rawLabel = "Usenet Vault 4K WEB-DL HEVC",
            hints = StreamBehaviorHints(
                provider = "Usenet Vault",
                providerCode = "UV",
                sourceLabel = "UV",
                indexer = "altHUB"
            )
        )

        assertEquals(
            listOf("Usenet Vault", "altHUB"),
            sourceAttributionLabels(stream, "Flix-Streams")
        )
    }

    @Test
    fun `signal and debrid vault labels remain visible`() {
        val signal = source(
            rawLabel = "Signal Vault 1080p",
            hints = StreamBehaviorHints(provider = "Signal Vault", sourceLabel = "SV")
        )
        val debrid = source(
            rawLabel = "Debrid Vault",
            hints = StreamBehaviorHints(provider = "Debrid Vault", sourceLabel = "DV")
        )

        assertEquals(listOf("Signal Vault"), sourceAttributionLabels(signal, "Flix-Streams"))
        assertEquals(listOf("Debrid Vault"), sourceAttributionLabels(debrid, "Flix-Streams"))
    }

    @Test
    fun `raw addon label is a generic provider fallback without duplicate tech tags`() {
        val stream = source(rawLabel = "Usenet Vault 4K WEB-DL HEVC")

        assertEquals(
            listOf("Usenet Vault"),
            sourceAttributionLabels(stream, "Example Addon")
        )
    }

    @Test
    fun `addon name is not repeated as source attribution`() {
        val stream = source(rawLabel = "Torrentio 4K WEB-DL")

        assertEquals(emptyList<String>(), sourceAttributionLabels(stream, "Torrentio TB"))
    }

    private fun source(
        rawLabel: String,
        hints: StreamBehaviorHints? = null
    ) = StreamSource(
        source = "Example.Release.2160p.mkv",
        addonName = "Flix-Streams",
        quality = "4K",
        size = "8.5 GB",
        rawLabel = rawLabel,
        behaviorHints = hints
    )
}
