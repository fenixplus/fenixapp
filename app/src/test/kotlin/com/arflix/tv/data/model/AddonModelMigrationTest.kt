package com.arflix.tv.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class AddonModelMigrationTest {
    @Test
    fun `addon constructor defaults to stremio runtime`() {
        val addon = Addon(
            id = "legacy",
            name = "Legacy Addon",
            version = "1.0.0",
            description = "Old payload",
            isInstalled = true,
            type = AddonType.CUSTOM
        )

        assertEquals(RuntimeKind.STREMIO, addon.runtimeKind)
        assertEquals(AddonInstallSource.DIRECT_URL, addon.installSource)
    }
}
