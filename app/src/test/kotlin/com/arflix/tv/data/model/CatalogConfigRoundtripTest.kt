package com.arflix.tv.data.model

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogConfigRoundtripTest {
    @Test
    fun `collectionHeroVideoUrl survives gson round-trip`() {
        val original = CatalogConfig(
            id = "collection_service_netflix",
            title = "Netflix",
            sourceType = CatalogSourceType.PREINSTALLED,
            isPreinstalled = true,
            kind = CatalogKind.COLLECTION,
            collectionGroup = CollectionGroupKind.SERVICE,
            collectionHeroVideoUrl = "https://example.com/netflix.mp4"
        )
        val gson = Gson()
        val json = gson.toJson(original)
        val restored = gson.fromJson(json, CatalogConfig::class.java)
        assertEquals("https://example.com/netflix.mp4", restored.collectionHeroVideoUrl)
    }

    @Test
    fun `collectionHeroVideoUrl is preserved when serialized to a JSON object map`() {
        // This mirrors how CatalogRepository reads catalogs back from DataStore:
        // gson decodes the stored JSON into List<Map<String, Any?>> first, then
        // reads individual fields by key. The new field must be present in the
        // serialized JSON for that decode path to find it.
        val cfg = CatalogConfig(
            id = "id",
            title = "t",
            sourceType = CatalogSourceType.PREINSTALLED,
            collectionHeroVideoUrl = "https://example.com/v.mp4"
        )
        val json = Gson().toJson(cfg)
        assertTrue("Field missing from JSON: $json", json.contains("collectionHeroVideoUrl"))
        assertTrue("Value missing from JSON: $json", json.contains("https://example.com/v.mp4"))
    }
}
