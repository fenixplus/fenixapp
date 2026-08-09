package com.arflix.tv.ui.screens.tv.live

import com.arflix.tv.data.model.IptvChannel

/**
 * Startup decisions for the Live TV screen, kept out of the composable so they
 * can be unit tested. The screen itself is a single very large @Composable, so
 * behaviour expressed inline there can only be verified on a device — these
 * rules are the parts users actually complained about, so they live here.
 */
object LiveTvStartup {

    /**
     * Which channel Live TV should open on.
     *
     * Order: an explicit request (deep link, "continue watching" action) wins;
     * otherwise resume the channel the session last recorded. The session
     * already persisted `lastChannelId` but nothing consumed it on entry, so
     * Live TV always reopened at the top of the list.
     *
     * A remembered id that is no longer in the playlists is ignored, so a
     * removed channel can't pin the screen to something that cannot be shown.
     */
    fun resumeChannelId(
        explicitChannelId: String?,
        lastChannelId: String?,
        availableChannelIds: Set<String>,
    ): String? {
        explicitChannelId?.takeIf { it.isNotBlank() }?.let { return it }
        val remembered = lastChannelId?.trim().orEmpty()
        if (remembered.isEmpty()) return null
        // An empty channel set means the list hasn't loaded yet — keep the
        // remembered id so it can be honoured once channels arrive, rather than
        // discarding it and defaulting to the top of the list.
        if (availableChannelIds.isEmpty()) return remembered
        return remembered.takeIf { it in availableChannelIds }
    }

    /**
     * Whether the sidebar may claim D-pad focus right now.
     *
     * While channels are still loading the list recomposes underneath the
     * focused item, Compose drops focus, and the focus effect used to grab it
     * back — which is what made the selector jump in unrelated directions when
     * a user pressed a direction key during load. Touch devices never take
     * this focus at all.
     */
    fun shouldClaimSidebarFocus(
        isTouchDevice: Boolean,
        isCategoryZoneActive: Boolean,
        channelsLoaded: Boolean,
    ): Boolean = !isTouchDevice && isCategoryZoneActive && channelsLoaded

    /**
     * Whether the channel-search field should be focused.
     *
     * The signal seeds at 0 so opening Live TV does not slam focus into the
     * search box; only an explicit user action (which bumps the signal) does.
     */
    fun shouldFocusSearch(focusSearchSignal: Int): Boolean = focusSearchSignal > 0

    /** Ids of the channels currently available, for [resumeChannelId]. */
    fun channelIds(channels: List<IptvChannel>): Set<String> =
        channels.mapTo(LinkedHashSet(channels.size)) { it.id }
}
