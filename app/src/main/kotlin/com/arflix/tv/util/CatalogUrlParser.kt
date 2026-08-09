package com.arflix.tv.util

import com.arflix.tv.data.model.CatalogSourceType
import java.net.URI

sealed class ParsedCatalogUrl {
    data class TraktUserList(val username: String, val listId: String) : ParsedCatalogUrl()
    data class TraktList(val listId: String) : ParsedCatalogUrl()
    data class Mdblist(val url: String) : ParsedCatalogUrl()
}

data object CatalogUrlParser {
    fun normalize(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return trimmed
        val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }
        return withScheme.removeSuffix("/")
    }

    fun detectSource(url: String): CatalogSourceType? {
        val normalized = normalize(url)
        return when {
            isTraktHost(normalized) -> CatalogSourceType.TRAKT
            isMdblistHost(normalized) -> CatalogSourceType.MDBLIST
            else -> null
        }
    }

    fun parse(url: String): ParsedCatalogUrl? {
        val normalized = normalize(url)
        return when (detectSource(normalized)) {
            CatalogSourceType.TRAKT -> parseTrakt(normalized)
            CatalogSourceType.MDBLIST -> ParsedCatalogUrl.Mdblist(normalized)
            else -> null
        }
    }

    fun parseTrakt(url: String): ParsedCatalogUrl? {
        val uri = runCatching { URI(normalize(url)) }.getOrNull() ?: return null
        if (!isTraktHost(uri.host ?: return null)) return null
        val parts = uri.path.trim('/').split('/').filter { it.isNotBlank() }
        if (parts.size >= 4 && parts[0] == "users" && parts[2] == "lists") {
            return ParsedCatalogUrl.TraktUserList(parts[1], parts[3])
        }
        if (parts.size >= 2 && parts[0] == "lists") {
            return ParsedCatalogUrl.TraktList(parts[1])
        }
        return null
    }

    private fun isTraktHost(urlOrHost: String): Boolean {
        val host = if (urlOrHost.contains("://")) {
            runCatching { URI(urlOrHost).host.orEmpty() }.getOrDefault("")
        } else {
            urlOrHost
        }.lowercase()
        return host == "trakt.tv" || host.endsWith(".trakt.tv")
    }

    private fun isMdblistHost(urlOrHost: String): Boolean {
        val host = if (urlOrHost.contains("://")) {
            runCatching { URI(urlOrHost).host.orEmpty() }.getOrDefault("")
        } else {
            urlOrHost
        }.lowercase()
        return host == "mdblist.com" || host.endsWith(".mdblist.com")
    }
}
