package com.arflix.tv.data.repository

import com.arflix.tv.data.model.CatalogConfig
import com.arflix.tv.data.model.CatalogKind
import com.arflix.tv.data.model.CollectionGroupKind
import com.arflix.tv.data.model.CollectionSourceConfig
import com.arflix.tv.data.model.CollectionSourceKind
import com.arflix.tv.data.model.CollectionTileShape
import java.util.Locale

internal data class CollectionSourceListMetadata(
    val sourceCatalogId: String,
    val sourceAddonId: String?,
    val sourceName: String,
    val sourceLabel: String,
    val mediaType: String?,
    val itemCount: Int?,
    val author: String?,
    val url: String?
)

internal data class CollectionTemplateEntry(
    val id: String,
    val title: String,
    val group: CollectionGroupKind,
    val coverImageUrl: String,
    val tileShape: CollectionTileShape,
    val hideTitle: Boolean,
    val heroVideoUrl: String?,
    val sources: List<CollectionSourceConfig>,
    val listMetadata: List<CollectionSourceListMetadata>,
    val railKey: String? = null,
    val description: String? = null,
    val heroImageUrl: String? = null,
    val focusGifUrl: String? = null,
    val clearLogoUrl: String? = null,
    val packId: String? = null,
    val packName: String? = null
)

internal object CollectionTemplateManifest {
    private const val STREAMING_ADDON_URL = "https://7a82163c306e-stremio-netflix-catalog-addon.baby-beamup.club/bmZ4LGRucCxhbXAsYXRwLGhibSxwbXAscGNwLGhsdSxzdHo6OlVTOjE3NzYzMjQxMDg4OTM6MDowOkdU/manifest.json"
    private const val MARVEL_ADDON_URL = "https://addon-marvel.onrender.com/catalog/marvel-mcu/manifest.json"
    private const val DC_ADDON_URL = "https://addon-dc-cq85.onrender.com/catalog/dc-chronological/manifest.json"
    private const val STAR_WARS_ADDON_URL = "https://addon-star-wars-u9e3.onrender.com/catalog/sw-movies-series-chronological/manifest.json"

    private val builtInRailOrder = listOf(
        CollectionGroupKind.FEATURED,
        CollectionGroupKind.SERVICE,
        CollectionGroupKind.GENRE,
        CollectionGroupKind.FRANCHISE,
        CollectionGroupKind.DECADE
    )

    val railOrder: List<CollectionGroupKind>
        get() = builtInRailOrder

    val entries: List<CollectionTemplateEntry>
        get() = builtInEntries

    private val builtInEntries: List<CollectionTemplateEntry> = ArvioCollections.entries

    private val builtInEntriesById: Map<String, CollectionTemplateEntry> = builtInEntries.associateBy { it.id }

    fun entryForCatalog(catalogId: String?): CollectionTemplateEntry? {
        val id = catalogId ?: return null
        return builtInEntriesById[id]
    }

    fun listMetadataFor(catalogId: String?): List<CollectionSourceListMetadata> =
        entryForCatalog(catalogId)?.listMetadata.orEmpty()

    fun railCatalogId(group: CollectionGroupKind): String = "collection_rail_${group.name.lowercase(Locale.US)}"

    fun railTitle(group: CollectionGroupKind): String = when (group) {
        CollectionGroupKind.FEATURED -> "Destaques"
        CollectionGroupKind.SERVICE -> "Serviços de Streaming"
        CollectionGroupKind.GENRE -> "Gêneros"
        CollectionGroupKind.DECADE -> "Décadas"
        CollectionGroupKind.FRANCHISE -> "Franchises"
        CollectionGroupKind.NETWORK -> "Canais e Redes"
    }

    fun hasEntriesFor(group: CollectionGroupKind): Boolean =
        builtInEntries.any { it.group == group }

    fun isValidCollectionConfig(config: CatalogConfig): Boolean = when (config.kind) {
        CatalogKind.COLLECTION -> entryForCatalog(config.id) != null
        CatalogKind.COLLECTION_RAIL -> {
            val group = config.collectionGroup ?: return false
            group in railOrder && hasEntriesFor(group)
        }
        else -> true
    }

    fun requiredAddonUrlsFor(entry: CollectionTemplateEntry): List<String> {
        val addonIds = entry.sources.mapNotNull { it.addonId }.toSet()
        return buildList {
            if (addonIds.contains("pw.ers.netflix-catalog")) add(STREAMING_ADDON_URL)
            if (addonIds.contains("com.joaogonp.marveladdon.custom.marvel-mcu")) add(MARVEL_ADDON_URL)
            if (addonIds.contains("com.btmv.addon.dcuniverse.custom.dc-chronological")) add(DC_ADDON_URL)
            if (addonIds.contains("com.starwars.addon.custom.sw-movies-series-chronological")) add(STAR_WARS_ADDON_URL)
        }
    }

    fun autoInstalledAddonUrls(): List<String> = listOf(
        STREAMING_ADDON_URL,
        MARVEL_ADDON_URL,
        DC_ADDON_URL,
        STAR_WARS_ADDON_URL
    )

    fun descriptionFor(entry: CollectionTemplateEntry): String = entry.description ?: when (entry.group) {
        CollectionGroupKind.FEATURED -> "Curated spotlight picks sourced from the latest public lists."
        CollectionGroupKind.SERVICE -> "Browse movies and series grouped by streaming service."
        CollectionGroupKind.GENRE -> "A themed mix of movies and series built around a single genre mood."
        CollectionGroupKind.DECADE -> "Explore movies collected around a specific decade."
        CollectionGroupKind.FRANCHISE -> "A franchise timeline or universe collection gathered from dedicated lists."
        CollectionGroupKind.NETWORK -> "Shows and movies grouped around a specific network or brand."
    }
}
