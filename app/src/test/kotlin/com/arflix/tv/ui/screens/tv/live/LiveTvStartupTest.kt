package com.arflix.tv.ui.screens.tv.live

import com.arflix.tv.data.model.IptvChannel
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Covers the Live TV startup behaviour users reported as broken: the selector
 * being trapped in the search field, focus jumping while the list was still
 * loading, and the page always reopening at the top instead of on the channel
 * they left.
 */
class LiveTvStartupTest {

    private fun channel(id: String) = IptvChannel(
        id = id,
        name = id,
        group = "General",
        streamUrl = "http://example.test/$id",
    )

    // ── Resuming the last channel ──────────────────────────────────────────

    @Test
    fun resumesTheChannelTheSessionRemembered() {
        val resumed = LiveTvStartup.resumeChannelId(
            explicitChannelId = null,
            lastChannelId = "chan-42",
            availableChannelIds = setOf("chan-1", "chan-42", "chan-99"),
        )

        assertThat(resumed).isEqualTo("chan-42")
    }

    @Test
    fun explicitChannelWinsOverTheRememberedOne() {
        val resumed = LiveTvStartup.resumeChannelId(
            explicitChannelId = "deep-link",
            lastChannelId = "chan-42",
            availableChannelIds = setOf("chan-42", "deep-link"),
        )

        assertThat(resumed).isEqualTo("deep-link")
    }

    @Test
    fun ignoresARememberedChannelThatIsNoLongerInThePlaylists() {
        val resumed = LiveTvStartup.resumeChannelId(
            explicitChannelId = null,
            lastChannelId = "removed-channel",
            availableChannelIds = setOf("chan-1", "chan-2"),
        )

        assertThat(resumed).isNull()
    }

    @Test
    fun keepsTheRememberedChannelWhileTheListIsStillEmpty() {
        // Channels arrive asynchronously; discarding the id here would defeat
        // the whole feature on a cold start.
        val resumed = LiveTvStartup.resumeChannelId(
            explicitChannelId = null,
            lastChannelId = "chan-42",
            availableChannelIds = emptySet(),
        )

        assertThat(resumed).isEqualTo("chan-42")
    }

    @Test
    fun blankRememberedIdsAreTreatedAsAbsent() {
        assertThat(
            LiveTvStartup.resumeChannelId(
                explicitChannelId = "   ",
                lastChannelId = "  ",
                availableChannelIds = setOf("chan-1"),
            )
        ).isNull()
    }

    @Test
    fun channelIdsAreDerivedFromTheSnapshot() {
        val ids = LiveTvStartup.channelIds(listOf(channel("a"), channel("b")))

        assertThat(ids).containsExactly("a", "b")
    }

    // ── Focus while loading ────────────────────────────────────────────────

    @Test
    fun sidebarDoesNotClaimFocusWhileChannelsAreStillLoading() {
        // The reported bug: pressing a direction key during load sent the
        // selector jumping, because the effect kept re-grabbing focus from a
        // list that was still recomposing.
        val claims = LiveTvStartup.shouldClaimSidebarFocus(
            isTouchDevice = false,
            isCategoryZoneActive = true,
            channelsLoaded = false,
        )

        assertThat(claims).isFalse()
    }

    @Test
    fun sidebarClaimsFocusOnceChannelsAreLoaded() {
        val claims = LiveTvStartup.shouldClaimSidebarFocus(
            isTouchDevice = false,
            isCategoryZoneActive = true,
            channelsLoaded = true,
        )

        assertThat(claims).isTrue()
    }

    @Test
    fun touchDevicesNeverTakeDpadFocus() {
        val claims = LiveTvStartup.shouldClaimSidebarFocus(
            isTouchDevice = true,
            isCategoryZoneActive = true,
            channelsLoaded = true,
        )

        assertThat(claims).isFalse()
    }

    @Test
    fun otherFocusZonesAreLeftAlone() {
        val claims = LiveTvStartup.shouldClaimSidebarFocus(
            isTouchDevice = false,
            isCategoryZoneActive = false,
            channelsLoaded = true,
        )

        assertThat(claims).isFalse()
    }

    // ── Search focus ───────────────────────────────────────────────────────

    @Test
    fun openingLiveTvDoesNotFocusTheSearchField() {
        // The signal seeds at 0. It used to seed at 1, and the sidebar focuses
        // search for any value > 0, so every entry trapped the selector there.
        assertThat(LiveTvStartup.shouldFocusSearch(0)).isFalse()
    }

    @Test
    fun askingForSearchFocusesIt() {
        assertThat(LiveTvStartup.shouldFocusSearch(1)).isTrue()
        assertThat(LiveTvStartup.shouldFocusSearch(7)).isTrue()
    }
}
