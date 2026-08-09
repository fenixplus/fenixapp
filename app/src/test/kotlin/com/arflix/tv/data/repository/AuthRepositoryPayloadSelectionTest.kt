package com.arflix.tv.data.repository

import org.junit.Assert.assertTrue
import org.junit.Test

class AuthRepositoryPayloadSelectionTest {
    @Test
    fun `real multi profile snapshot outranks empty default profile`() {
        val tvSnapshot = """
            {
              "profiles": [
                {"id":"p1","name":"Arvind","avatarId":2},
                {"id":"p2","name":"Kids","avatarId":3}
              ],
              "profileSettingsById": {},
              "addonsByProfile": {"p1":[{"id":"torrentio"}]},
              "iptvByProfile": {"p1":{"playlists":[{"name":"Main","m3uUrl":"https://example.com/list.m3u"}]}},
              "updatedAt": 1000
            }
        """.trimIndent()
        val emptyPhoneSnapshot = """
            {
              "profiles": [
                {"id":"local","name":"Profile 1","avatarId":0,"avatarImageVersion":0}
              ],
              "profileSettingsById": {},
              "addonsByProfile": {"local":[]},
              "iptvByProfile": {"local":{"playlists":[]}},
              "updatedAt": 999999
            }
        """.trimIndent()

        assertTrue(
            accountSyncPayloadRestoreRank(tvSnapshot) >
                accountSyncPayloadRestoreRank(emptyPhoneSnapshot)
        )
    }

    @Test
    fun `single default profile with configured addon is still useful`() {
        val configuredDefaultSnapshot = """
            {
              "profiles": [
                {"id":"default","name":"Profile 1","avatarId":0,"avatarImageVersion":0}
              ],
              "profileSettingsById": {},
              "addonsByProfile": {"default":[{"id":"torrentio"}]},
              "iptvByProfile": {"default":{"playlists":[]}},
              "updatedAt": 1000
            }
        """.trimIndent()
        val emptyPhoneSnapshot = """
            {
              "profiles": [
                {"id":"local","name":"Profile 1","avatarId":0,"avatarImageVersion":0}
              ],
              "profileSettingsById": {},
              "addonsByProfile": {"local":[]},
              "updatedAt": 999999
            }
        """.trimIndent()

        assertTrue(
            accountSyncPayloadRestoreRank(configuredDefaultSnapshot) >
                accountSyncPayloadRestoreRank(emptyPhoneSnapshot)
        )
    }

    @Test
    fun `three profile fallback snapshot outranks newer single configured account sync snapshot`() {
        val newerAccountSyncSnapshot = """
            {
              "profiles": [
                {"id":"43452392-8300-4643-8139-9d614c3aaaa7","name":"usua","avatarId":0}
              ],
              "activeProfileId":"43452392-8300-4643-8139-9d614c3aaaa7",
              "profileSettingsById": {"43452392-8300-4643-8139-9d614c3aaaa7":{}},
              "addonsByProfile": {"43452392-8300-4643-8139-9d614c3aaaa7":[{"id":"torrentio"}]},
              "iptvByProfile": {"43452392-8300-4643-8139-9d614c3aaaa7":{"playlists":[{"name":"Main"}]}},
              "updatedAt": 999999
            }
        """.trimIndent()
        val olderUserSettingsSnapshot = """
            {
              "profiles": [
                {"id":"1cea44ee-1bf3-4de5-9615-6660cdcc6e6d","name":"New","avatarId":1},
                {"id":"shai","name":"Shai","avatarId":2},
                {"id":"leyla","name":"Leyla","avatarId":3}
              ],
              "activeProfileId":"1cea44ee-1bf3-4de5-9615-6660cdcc6e6d",
              "profileSettingsById": {"1cea44ee-1bf3-4de5-9615-6660cdcc6e6d":{},"shai":{},"leyla":{}},
              "addonsByProfile": {"1cea44ee-1bf3-4de5-9615-6660cdcc6e6d":[],"shai":[],"leyla":[]},
              "iptvByProfile": {"1cea44ee-1bf3-4de5-9615-6660cdcc6e6d":{},"shai":{},"leyla":{}},
              "updatedAt": 1000
            }
        """.trimIndent()

        assertTrue(
            accountSyncPayloadRestoreRank(olderUserSettingsSnapshot) >
                accountSyncPayloadRestoreRank(newerAccountSyncSnapshot)
        )
        assertTrue(
            (accountSyncPayloadProfileCount(olderUserSettingsSnapshot) ?: 0) >
                (accountSyncPayloadProfileCount(newerAccountSyncSnapshot) ?: 0)
        )
        assertTrue(
            accountSyncPayloadScopedCoverage(olderUserSettingsSnapshot) >
                accountSyncPayloadScopedCoverage(newerAccountSyncSnapshot)
        )
    }

    @Test
    fun `fallback mirror save counts when canonical account sync is blocked`() {
        assertTrue(
            accountSyncPayloadSaveSucceeded(
                accountSyncSaved = false,
                userSettingsSaved = true,
                profileAddonsSaved = false
            )
        )
        assertTrue(
            accountSyncPayloadSaveSucceeded(
                accountSyncSaved = false,
                userSettingsSaved = false,
                profileAddonsSaved = true
            )
        )
    }
}
