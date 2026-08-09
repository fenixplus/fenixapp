package com.arflix.tv.util

import android.app.Activity
import android.media.MediaExtractor
import android.net.Uri
import android.os.Build
import android.view.Display
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import java.util.concurrent.ConcurrentHashMap

/**
 * Auto frame rate matching utility.
 * Switches the display refresh rate to match the video frame rate for judder-free playback.
 * Simplified frame-rate helper using MediaExtractor only (no NextLib dependency).
 */
object FrameRateUtils {

    private const val SWITCH_TIMEOUT_MS = 4000L
    private const val REFRESH_MATCH_TOLERANCE_HZ = 0.08f
    private const val NTSC_FILM_FPS = 24000f / 1001f
    private const val CINEMA_24_FPS = 24f
    private const val MIN_VALID_FPS = 10f
    private const val MAX_VALID_FPS = 120f
    private const val POLL_INTERVAL_MS = 60L
    private const val STABLE_POLLS_REQUIRED = 2
    private const val DETECTION_CACHE_TTL_MS = 15 * 60_000L

    private var originalModeId: Int? = null
    private val detectionCache = ConcurrentHashMap<String, CachedDetection>()

    data class FrameRateDetection(
        val raw: Float,
        val snapped: Float
    )

    private data class CachedDetection(
        val detection: FrameRateDetection,
        val createdAtMs: Long
    )

    private fun detectionCacheKey(sourceUrl: String): String {
        val uri = runCatching { Uri.parse(sourceUrl) }.getOrNull() ?: return sourceUrl.substringBefore('?')
        val scheme = uri.scheme.orEmpty()
        val host = uri.host.orEmpty()
        val path = uri.path.orEmpty()
        return if (scheme.isNotBlank() && host.isNotBlank()) {
            "$scheme://$host$path"
        } else {
            sourceUrl.substringBefore('?')
        }
    }

    fun snapToStandardRate(fps: Float): Float {
        if (fps <= 0f) return fps
        return when {
            fps in 23.90f..23.988f -> NTSC_FILM_FPS
            fps in 23.988f..24.1f -> CINEMA_24_FPS
            fps in 24.9f..25.1f -> 25f
            fps in 29.90f..29.985f -> 30000f / 1001f
            fps in 29.985f..30.1f -> 30f
            fps in 49.9f..50.1f -> 50f
            fps in 59.9f..59.97f -> 60000f / 1001f
            fps in 59.97f..60.1f -> 60f
            else -> fps
        }
    }

    private fun matchesTarget(refreshRate: Float, target: Float): Boolean {
        val tolerance = max(REFRESH_MATCH_TOLERANCE_HZ, target * 0.003f)
        return abs(refreshRate - target) <= tolerance
    }

    private fun pickBestMode(modes: List<Display.Mode>, target: Float): Display.Mode? {
        if (target <= 0f) return null
        val closest = modes.minByOrNull { abs(it.refreshRate - target) } ?: return null
        return if (matchesTarget(closest.refreshRate, target)) closest else null
    }

    private fun chooseBestMode(
        activeMode: Display.Mode,
        modes: List<Display.Mode>,
        fps: Float
    ): Display.Mode {
        val exact = pickBestMode(modes, fps)
        val double = pickBestMode(modes, fps * 2f)
        val pulldown = pickBestMode(modes, fps * 2.5f)
        val fallback = modes.minByOrNull {
            val div = it.refreshRate / fps
            val rounded = div.roundToInt()
            if (rounded < 1) (fps - it.refreshRate) / fps
            else abs(div / rounded - 1f)
        }
        return exact ?: double ?: pulldown ?: fallback ?: activeMode
    }

    /**
     * Detect the video frame rate from a stream URL using MediaExtractor.
     * Returns null if detection fails or the URL is a live stream.
     */
    fun detectFrameRate(sourceUrl: String, headers: Map<String, String> = emptyMap()): FrameRateDetection? {
        val lower = sourceUrl.substringBefore('?').lowercase()
        if (lower.endsWith(".m3u8") || lower.contains("/hls") || lower.endsWith(".mpd")) return null

        val extractor = MediaExtractor()
        return try {
            val uri = Uri.parse(sourceUrl)
            when (uri.scheme?.lowercase()) {
                "http", "https" -> extractor.setDataSource(sourceUrl, headers)
                else -> return null
            }

            var videoFormat: android.media.MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                if (format.getString(android.media.MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                    videoFormat = format
                    extractor.selectTrack(i)
                    break
                }
            }
            if (videoFormat == null) return null

            // Try declared frame rate first
            val declared = videoFormat
                .takeIf { it.containsKey(android.media.MediaFormat.KEY_FRAME_RATE) }
                ?.runCatching { getFloat(android.media.MediaFormat.KEY_FRAME_RATE) }
                ?.getOrNull()
            if (declared != null && declared in MIN_VALID_FPS..MAX_VALID_FPS) {
                return FrameRateDetection(raw = declared, snapped = snapToStandardRate(declared))
            }

            // Fall back to sample-based measurement
            val timestamps = ArrayList<Long>(400)
            while (timestamps.size < 350) {
                val ts = extractor.sampleTime
                if (ts < 0) break
                timestamps.add(ts)
                if (!extractor.advance()) break
            }
            if (timestamps.size < 34) return null

            val skip = 3
            var total = 0L
            for (i in (skip + 1) until timestamps.size) {
                total += (timestamps[i] - timestamps[i - 1])
            }
            val count = (timestamps.size - skip - 1).coerceAtLeast(1)
            val avgDuration = total.toFloat() / count
            if (avgDuration <= 0f) return null

            val measured = 1_000_000f / avgDuration
            if (measured !in MIN_VALID_FPS..MAX_VALID_FPS) return null

            FrameRateDetection(raw = measured, snapped = snapToStandardRate(measured))
        } catch (_: Exception) {
            null
        } finally {
            runCatching { extractor.release() }
        }
    }

    fun detectFrameRateCached(sourceUrl: String, headers: Map<String, String> = emptyMap()): FrameRateDetection? {
        val key = detectionCacheKey(sourceUrl)
        val now = System.currentTimeMillis()
        detectionCache[key]?.let { cached ->
            if (now - cached.createdAtMs <= DETECTION_CACHE_TTL_MS) {
                return cached.detection
            }
            detectionCache.remove(key)
        }
        return detectFrameRate(sourceUrl, headers)?.also { detection ->
            detectionCache[key] = CachedDetection(detection, now)
        }
    }

    fun getCachedFrameRate(sourceUrl: String): FrameRateDetection? {
        val key = detectionCacheKey(sourceUrl)
        val now = System.currentTimeMillis()
        return detectionCache[key]?.let { cached ->
            if (now - cached.createdAtMs <= DETECTION_CACHE_TTL_MS) {
                cached.detection
            } else {
                detectionCache.remove(key)
                null
            }
        }
    }

    /**
     * Apply the best display mode immediately without waiting for the switch to settle.
     * Used on the playback hot path so frame-rate matching never blocks first frame.
     */
    fun applyFrameRateMode(activity: Activity, frameRate: Float): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        if (frameRate <= 0f) return false

        return try {
            val window = activity.window ?: return false
            val display = window.decorView.display ?: return false
            val activeMode = display.mode
            val sameSizeModes = display.supportedModes.filter {
                it.physicalWidth == activeMode.physicalWidth &&
                    it.physicalHeight == activeMode.physicalHeight
            }
            if (sameSizeModes.size <= 1) return false

            val best = chooseBestMode(activeMode, sameSizeModes, frameRate)
            if (best.modeId == activeMode.modeId) return false

            if (originalModeId == null) {
                originalModeId = activeMode.modeId
            }

            val params = window.attributes
            params.preferredDisplayModeId = best.modeId
            window.attributes = params
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Switch the display to the best mode for the given frame rate.
     * Blocks until the switch stabilizes or times out.
     */
    suspend fun matchFrameRateAndWait(
        activity: Activity,
        frameRate: Float
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        if (frameRate <= 0f) return false

        val targetMode = withContext(Dispatchers.Main) {
            val window = activity.window ?: return@withContext null
            val display = window.decorView.display ?: return@withContext null
            val activeMode = display.mode

            val sameSizeModes = display.supportedModes.filter {
                it.physicalWidth == activeMode.physicalWidth &&
                    it.physicalHeight == activeMode.physicalHeight
            }
            if (sameSizeModes.size <= 1) return@withContext null

            val best = chooseBestMode(activeMode, sameSizeModes, frameRate)
            if (best.modeId == activeMode.modeId) return@withContext null

            // Record original mode for restoration
            if (originalModeId == null) {
                originalModeId = activeMode.modeId
            }

            val params = window.attributes
            params.preferredDisplayModeId = best.modeId
            window.attributes = params
            best
        } ?: return false

        // Poll until display mode stabilizes
        var stablePolls = 0
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < SWITCH_TIMEOUT_MS) {
            val current = withContext(Dispatchers.Main) {
                activity.window?.decorView?.display?.mode
            } ?: break
            if (current.modeId == targetMode.modeId || matchesTarget(current.refreshRate, targetMode.refreshRate)) {
                stablePolls++
                if (stablePolls >= STABLE_POLLS_REQUIRED) return true
            } else {
                stablePolls = 0
            }
            delay(POLL_INTERVAL_MS)
        }
        return false
    }

    /**
     * Restore the original display mode that was active before frame rate matching.
     */
    fun restoreOriginalMode(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val modeId = originalModeId ?: return
        try {
            val window = activity.window ?: return
            val params = window.attributes
            params.preferredDisplayModeId = modeId
            window.attributes = params
            originalModeId = null
        } catch (_: Exception) {}
    }

    fun clearOriginalMode() {
        originalModeId = null
    }
}
