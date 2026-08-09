package com.arflix.tv.data.api

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamAddonMetadataTest {
    @Test
    fun `custom behavior hints preserve strings arrays and numbers`() {
        val hints = Gson().fromJson(
            """{
                "provider": "Usenet Vault",
                "source": ["UV", "Backup"],
                "indexer": 42,
                "language": null
            }""".trimIndent(),
            StreamBehaviorHints::class.java
        )

        assertEquals("Usenet Vault", hints.provider.asAddonMetadataText())
        assertEquals("UV, Backup", hints.source.asAddonMetadataText())
        assertEquals("42", hints.indexer.asAddonMetadataText())
        assertNull(hints.language.asAddonMetadataText())
    }
}
