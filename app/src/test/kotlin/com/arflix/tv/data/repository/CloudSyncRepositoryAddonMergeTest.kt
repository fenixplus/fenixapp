package com.arflix.tv.data.repository

import com.arflix.tv.data.model.Addon
import com.arflix.tv.data.model.AddonType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CloudSyncRepositoryAddonMergeTest {
    @Test
    fun `cloud addons win when ids match`() {
        val local = addon(id = "flix", name = "Local Flix")
        val cloud = addon(id = "flix", name = "Cloud Flix", isEnabled = false)

        val (merged, preserved) = reconcileAddonsWithCloud(
            cloudAddons = listOf(cloud),
            localAddons = listOf(local)
        )

        assertFalse(preserved)
        assertEquals(listOf("Cloud Flix"), merged.map { it.name })
        assertEquals(false, merged.single().isEnabled)
    }

    @Test
    fun `local addon absent from cloud is removed (removal propagates)`() {
        // The other device removed "flix" — the cloud no longer lists it. Reconcile must drop it
        // locally instead of re-adding it (the old union bug).
        val cloud = addon(id = "torrentio", name = "Torrentio")
        val localFlix = addon(id = "flix", name = "FlixStreams")

        val (merged, preserved) = reconcileAddonsWithCloud(
            cloudAddons = listOf(cloud),
            localAddons = listOf(cloud, localFlix)
        )

        assertFalse(preserved)
        assertEquals(listOf("torrentio"), merged.map { it.id })
    }

    @Test
    fun `empty cloud preserves local addons (empty-guard)`() {
        val localFlix = addon(id = "flix", name = "FlixStreams")

        val (merged, preserved) = reconcileAddonsWithCloud(
            cloudAddons = emptyList(),
            localAddons = listOf(localFlix)
        )

        assertFalse(preserved)
        assertEquals(listOf("flix"), merged.map { it.id })
    }

    @Test
    fun `intentional empty cloud (newer set-timestamp) removes all local addons`() {
        val localFlix = addon(id = "flix", name = "FlixStreams")

        val (merged, preserved) = reconcileAddonsWithCloud(
            cloudAddons = emptyList(),
            localAddons = listOf(localFlix),
            cloudAddonsUpdatedAt = 100L,
            localAddonsUpdatedAt = 50L
        )

        assertFalse(preserved)
        assertEquals(emptyList<Addon>(), merged)
    }

    @Test
    fun `newer local set is kept over a stale cloud (unpushed local change)`() {
        val cloud = addon(id = "torrentio", name = "Torrentio")
        val localFlix = addon(id = "flix", name = "FlixStreams")

        val (merged, preserved) = reconcileAddonsWithCloud(
            cloudAddons = listOf(cloud),
            localAddons = listOf(localFlix),
            cloudAddonsUpdatedAt = 50L,
            localAddonsUpdatedAt = 100L
        )

        assertFalse(preserved)
        assertEquals(listOf("flix"), merged.map { it.id })
    }

    private fun addon(
        id: String,
        name: String,
        type: AddonType = AddonType.CUSTOM,
        isEnabled: Boolean = true
    ) = Addon(
        id = id,
        name = name,
        version = "1.0.0",
        description = "",
        isInstalled = true,
        isEnabled = isEnabled,
        type = type,
        url = "https://example.com/$id/manifest.json"
    )
}
