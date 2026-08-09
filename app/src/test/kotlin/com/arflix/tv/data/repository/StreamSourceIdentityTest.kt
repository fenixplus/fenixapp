package com.arflix.tv.data.repository

import com.arflix.tv.data.model.Addon
import com.arflix.tv.data.model.AddonManifest
import com.arflix.tv.data.model.AddonResource
import com.arflix.tv.data.model.AddonType
import com.arflix.tv.data.model.StreamSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamSourceIdentityTest {
    @Test
    fun `identical links from different addons remain visible`() {
        val torrentio = stream(addonId = "torrentio")
        val flixStreams = stream(addonId = "org.flickystream.addon_configured")

        val deduped = listOf(torrentio, flixStreams)
            .distinctBy(::providerScopedStreamIdentity)

        assertEquals(listOf("torrentio", "org.flickystream.addon_configured"), deduped.map { it.addonId })
    }

    @Test
    fun `duplicate link inside one addon is still collapsed`() {
        val duplicate = stream(addonId = "org.flickystream.addon_configured")

        val deduped = listOf(duplicate, duplicate.copy(quality = "Unknown"))
            .distinctBy(::providerScopedStreamIdentity)

        assertEquals(1, deduped.size)
    }

    @Test
    fun `installing configured addon changes source cache revision`() {
        val existing = addon(
            id = "torrentio_configured",
            url = "https://torrentio.example/config/manifest.json"
        )
        val flixStreams = addon(
            id = "org.flickystream.addon_configured",
            url = "https://flixnest.app/flix-streams/u/configured/manifest.json"
        )

        val before = streamAddonConfigurationRevision(listOf(existing))
        val after = streamAddonConfigurationRevision(listOf(existing, flixStreams))

        assertNotEquals(before, after)
        assertEquals(24, after.length)
        assertTrue(after.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `configured url and enabled state participate in source cache revision`() {
        val first = addon(
            id = "org.flickystream.addon_configured",
            url = "https://flixnest.app/flix-streams/u/first/manifest.json"
        )
        val second = first.copy(
            url = "https://flixnest.app/flix-streams/u/second/manifest.json",
            transportUrl = "https://flixnest.app/flix-streams/u/second"
        )

        assertNotEquals(
            streamAddonConfigurationRevision(listOf(first)),
            streamAddonConfigurationRevision(listOf(second))
        )
        assertNotEquals(
            streamAddonConfigurationRevision(listOf(first)),
            streamAddonConfigurationRevision(listOf(first.copy(isEnabled = false)))
        )
    }

    private fun stream(addonId: String) = StreamSource(
        source = "Example.Release.2160p.mkv",
        addonName = addonId,
        addonId = addonId,
        quality = "4K",
        size = "20 GB",
        url = "https://debrid.example/resolve/shared-file"
    )

    private fun addon(id: String, url: String) = Addon(
        id = id,
        name = "Flix-Streams",
        version = "1.1.20",
        description = "Configured stream addon",
        isInstalled = true,
        isEnabled = true,
        type = AddonType.CUSTOM,
        url = url,
        transportUrl = url.removeSuffix("/manifest.json"),
        manifest = AddonManifest(
            id = "org.flickystream.addon",
            name = "Flix-Streams",
            version = "1.1.20",
            types = listOf("movie", "series", "anime", "tv"),
            resources = listOf(AddonResource(name = "stream")),
            idPrefixes = listOf("tt", "tmdb", "kitsu")
        )
    )
}
