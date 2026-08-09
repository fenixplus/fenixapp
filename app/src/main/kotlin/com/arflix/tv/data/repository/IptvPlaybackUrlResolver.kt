package com.arflix.tv.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.Locale

internal data class IptvPlaybackTarget(
    val url: String,
    val isHls: Boolean = false,
)

internal class IptvPlaybackUrlResolver(
    private val client: OkHttpClient,
    private val cacheTtlMs: Long = 5 * 60_000L,
    private val maxCacheEntries: Int = 256,
) {
    private data class ProbeResult(
        val target: IptvPlaybackTarget,
        val isConclusive: Boolean,
    )

    private data class CachedTarget(
        val target: IptvPlaybackTarget,
        val resolvedAtMs: Long,
    )

    private val cache = LinkedHashMap<String, CachedTarget>()

    suspend fun resolve(
        rawUrl: String,
        headers: Map<String, String>,
        forceRefresh: Boolean = false,
    ): IptvPlaybackTarget {
        val url = rawUrl.trim()
        val inferredTarget = IptvPlaybackTarget(
            url = url,
            isHls = looksLikeHlsPlaybackUrl(url),
        )
        if (!shouldResolveIptvPlaybackRedirect(url)) return inferredTarget

        val now = System.currentTimeMillis()
        if (!forceRefresh) {
            synchronized(cache) {
                cache[url]
                    ?.takeIf { now - it.resolvedAtMs <= cacheTtlMs }
                    ?.let { return it.target }
            }
        }

        val resolved = withContext(Dispatchers.IO) {
            val headProbe = executeProbe(url, headers, useHead = true)
            if (headProbe?.isConclusive == true) {
                headProbe.target
            } else {
                executeProbe(url, headers, useHead = false)?.target ?: inferredTarget
            }
        }

        synchronized(cache) {
            cache[url] = CachedTarget(resolved, now)
            while (cache.size > maxCacheEntries) {
                val firstKey = cache.keys.firstOrNull() ?: break
                cache.remove(firstKey)
            }
        }
        return resolved
    }

    private fun executeProbe(
        url: String,
        headers: Map<String, String>,
        useHead: Boolean,
    ): ProbeResult? {
        return runCatching {
            val request = Request.Builder()
                .url(url)
                .apply {
                    if (useHead) {
                        head()
                    } else {
                        get()
                        header("Range", "bytes=0-63")
                    }
                }
                .header("Accept", "*/*")
                .header("Accept-Encoding", "identity")
                .apply {
                    headers.forEach { (name, value) ->
                        if (name.isNotBlank() && value.isNotBlank() && !name.equals("Range", ignoreCase = true)) {
                            header(name, value)
                        }
                    }
                }
                .build()

            client.newCall(request).execute().use { response ->
                val finalUrl = response.request.url.toString().ifBlank { url }
                val contentType = response.header("Content-Type")
                val bodyStartsWithM3u = if (!useHead) {
                    response.peekBody(64).string().trimStart().startsWith("#EXTM3U", ignoreCase = true)
                } else {
                    false
                }
                val target = IptvPlaybackTarget(
                    url = finalUrl,
                    isHls = looksLikeHlsPlaybackUrl(finalUrl) ||
                        contentType.isHlsContentType() ||
                        bodyStartsWithM3u,
                )
                ProbeResult(
                    target = target,
                    isConclusive = finalUrl != url ||
                        target.isHls ||
                        contentType.isDirectMediaContentType(),
                )
            }
        }.getOrNull()
    }
}

internal fun shouldResolveIptvPlaybackRedirect(url: String): Boolean {
    val trimmed = url.trim()
    if (!trimmed.startsWith("http://", ignoreCase = true) &&
        !trimmed.startsWith("https://", ignoreCase = true)
    ) {
        return false
    }
    if (looksLikeHlsPlaybackUrl(trimmed)) return false

    val uri = runCatching { URI(trimmed) }.getOrNull() ?: return false
    val path = uri.path.orEmpty().trimEnd('/').lowercase(Locale.US)
    val lastSegment = path.substringAfterLast('/')
    if (lastSegment.isBlank() || lastSegment.contains('.')) return false

    val segments = path.trim('/').split('/').filter { it.isNotBlank() }
    if (segments.size < 4 || !segments.first().equals("live", ignoreCase = true)) return false

    // Standard Xtream numeric IDs are direct MPEG-TS streams. Slug-based providers
    // commonly redirect to HLS, which Media3 cannot infer from the original URL.
    return lastSegment.toLongOrNull() == null
}

internal fun looksLikeHlsPlaybackUrl(url: String): Boolean {
    val lower = url.lowercase(Locale.US)
    val path = lower.substringBefore('?').substringBefore('#')
    return path.endsWith(".m3u8") ||
        path.contains("/hls/") ||
        "output=m3u8" in lower ||
        "format=hls" in lower
}

private fun String?.isHlsContentType(): Boolean {
    val value = this.orEmpty().lowercase(Locale.US)
    return "mpegurl" in value || "vnd.apple.mpegurl" in value
}

private fun String?.isDirectMediaContentType(): Boolean {
    val value = this.orEmpty().lowercase(Locale.US).substringBefore(';').trim()
    return value.startsWith("video/") ||
        value.startsWith("audio/") ||
        value == "application/octet-stream"
}
