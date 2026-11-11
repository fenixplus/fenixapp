package com.arflix.tv.data.telegram

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Decides which requests the Telegram streaming proxy may answer.
 *
 * The proxy listens on every network interface so that a Chromecast on the local
 * network can fetch the stream. That also makes it reachable from any device on the
 * same Wi-Fi and from any other app on this device, and TDLib can read any file the
 * signed-in account has seen (including media from private chats). Every URL handed
 * out therefore carries a random per-process token, and only file IDs that the app
 * itself turned into stream URLs are served.
 */
internal class TelegramStreamAccess(
    private val token: String = newToken()
) {
    private val allowedFileIds = ConcurrentHashMap.newKeySet<Int>()
    private val allowedLocalFiles = ConcurrentHashMap<String, java.io.File>()

    /** Registers [fileId] as streamable and returns the URL path that serves it. */
    fun pathFor(fileId: Int): String {
        allowedFileIds.add(fileId)
        return "/$token/file/$fileId"
    }

    /** Registers a local disk [file] as streamable and returns the URL path that serves it. */
    fun pathForLocal(file: java.io.File): String {
        val id = java.lang.Integer.toHexString(file.absolutePath.hashCode())
        allowedLocalFiles[id] = file
        return "/$token/local/$id"
    }

    /** True only for the current token and a file ID registered through [pathFor]. */
    fun isAllowed(presentedToken: String?, fileId: Int): Boolean {
        if (presentedToken == null) return false
        val tokenMatches = MessageDigest.isEqual(
            presentedToken.toByteArray(Charsets.UTF_8),
            token.toByteArray(Charsets.UTF_8)
        )
        return tokenMatches && fileId in allowedFileIds
    }

    /** Returns the local file if the token is valid and the file was registered. */
    fun getLocalFile(presentedToken: String?, id: String): java.io.File? {
        if (presentedToken == null) return null
        val tokenMatches = MessageDigest.isEqual(
            presentedToken.toByteArray(Charsets.UTF_8),
            token.toByteArray(Charsets.UTF_8)
        )
        if (!tokenMatches) return null
        return allowedLocalFiles[id]?.takeIf { it.exists() && it.isFile }
    }

    companion object {
        private const val TOKEN_BYTES = 32

        private fun newToken(): String {
            val bytes = ByteArray(TOKEN_BYTES)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}

/** Inclusive byte range inside a file. */
internal data class ByteRange(val start: Long, val end: Long) {
    val length: Long get() = end - start + 1
}

/**
 * Resolves a single-range `Range` header against a file of [totalSize] bytes.
 *
 * A missing or malformed header selects the whole file, as RFC 9110 lets a server
 * ignore a Range header it does not understand. Returns null when the range cannot
 * be satisfied, so the caller can answer 416.
 */
internal fun resolveByteRange(header: String?, totalSize: Long): ByteRange? {
    if (totalSize <= 0L) return null
    val lastByte = totalSize - 1
    val wholeFile = ByteRange(0L, lastByte)

    val spec = header?.trim().orEmpty()
    if (!spec.startsWith("bytes=", ignoreCase = true)) return wholeFile
    val value = spec.substring("bytes=".length).trim()
    if (value.contains(',')) return wholeFile
    val dash = value.indexOf('-')
    if (dash < 0) return wholeFile
    val startText = value.substring(0, dash).trim()
    val endText = value.substring(dash + 1).trim()

    if (startText.isEmpty()) {
        // Suffix range ("bytes=-500"): the last N bytes of the file.
        val suffixLength = endText.toBytePositionOrNull() ?: return wholeFile
        if (suffixLength == 0L) return null
        return ByteRange((totalSize - suffixLength).coerceAtLeast(0L), lastByte)
    }

    val start = startText.toBytePositionOrNull() ?: return wholeFile
    // An open range ("bytes=500-") runs to the end of the file.
    val requestedEnd = if (endText.isEmpty()) null else endText.toBytePositionOrNull() ?: return wholeFile
    // A last position before the first one makes the header invalid, so it is ignored.
    if (requestedEnd != null && requestedEnd < start) return wholeFile
    if (start > lastByte) return null
    return ByteRange(start, minOf(requestedEnd ?: lastByte, lastByte))
}

/** Parses an unsigned decimal byte position; signs, spaces and overflow are rejected. */
private fun String.toBytePositionOrNull(): Long? =
    if (isNotEmpty() && all { it in '0'..'9' }) toLongOrNull() else null
