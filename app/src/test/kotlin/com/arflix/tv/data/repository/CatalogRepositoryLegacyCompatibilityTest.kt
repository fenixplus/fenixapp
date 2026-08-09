package com.arflix.tv.data.repository

import android.content.Context
import com.arflix.tv.data.api.TraktApi
import com.arflix.tv.data.model.CatalogConfig
import com.arflix.tv.data.model.CatalogKind
import io.mockk.mockk
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogRepositoryLegacyCompatibilityTest {
    @Test
    fun `catalog without kind defaults to standard`() {
        val repository = CatalogRepository(
            context = mockk<Context>(relaxed = true),
            profileManager = mockk<ProfileManager>(relaxed = true),
            traktApi = mockk<TraktApi>(relaxed = true),
            okHttpClient = mockk<OkHttpClient>(relaxed = true),
            invalidationBus = mockk<CloudSyncInvalidationBus>(relaxed = true)
        )
        val legacyJson = """
            [
              {
                "id": "legacy_catalog",
                "title": "Legacy Catalog",
                "sourceType": "PREINSTALLED"
              }
            ]
        """.trimIndent()

        val parseMethod = CatalogRepository::class.java.getDeclaredMethod(
            "parseCatalogsJson",
            String::class.java
        ).apply {
            isAccessible = true
        }

        @Suppress("UNCHECKED_CAST")
        val parsed = parseMethod.invoke(repository, legacyJson) as List<CatalogConfig>

        assertEquals(1, parsed.size)
        assertEquals(CatalogKind.STANDARD, parsed.single().kind)
    }
}
