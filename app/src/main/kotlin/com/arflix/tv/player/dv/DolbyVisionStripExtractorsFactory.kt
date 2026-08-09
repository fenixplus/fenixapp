package com.arflix.tv.player.dv

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import com.arflix.tv.player.dvmkv.DolbyVisionCompatibility
import com.arflix.tv.player.dvmkv.MatroskaExtractor as DvMatroskaExtractor

/**
 * Wraps a stock [ExtractorsFactory] and swaps the stock Matroska extractor for the vendored
 * [DvMatroskaExtractor] wired to a [DolbyVisionStripTransformer] — Dolby Vision profile-7
 * remux MKVs then play as their HDR10-compatible HEVC base layer on devices without a DV
 * decoder (see docs in the dvmkv package and docs/dolby-vision-compat.md).
 *
 * MKV-scoped on purpose: DV P7 in the wild is BluRay remuxes, which are Matroska. MP4 DV
 * WEB-DLs are P8/P5 single-layer — P8 already falls back to the plain HEVC decoder via media3's
 * built-in Dolby Vision codec mapping, and P5 has no compatible base layer to strip. MP4/fMP4/TS
 * sample interception would only matter for a future RPU-conversion path, which is out of scope.
 *
 * Every non-MKV extractor passes through untouched, and a non-DV MKV pays only a per-sample
 * no-op transformer call — the vendored extractor is otherwise stock media3 1.9.0.
 */
@UnstableApi
class DolbyVisionStripExtractorsFactory(
    private val delegate: ExtractorsFactory,
    private val stripHdr10PlusSei: Boolean = false,
    /**
     * Consulted at extractor-creation time (i.e. per prepare), so the user toggle and per-URL
     * forcing take effect without rebuilding the media source factories that hold this wrapper.
     */
    private val enabledProvider: () -> Boolean = { true },
) : ExtractorsFactory {

    override fun createExtractors(): Array<Extractor> =
        delegate.createExtractors().map(::wrap).toTypedArray()

    override fun createExtractors(
        uri: Uri,
        responseHeaders: Map<String, List<String>>
    ): Array<Extractor> =
        delegate.createExtractors(uri, responseHeaders).map(::wrap).toTypedArray()

    private fun wrap(extractor: Extractor): Extractor {
        val enabled = enabledProvider()
        // The vendored extractor's format rewrite (video/dolby-vision -> video/H265 with the
        // container's base-layer codec string) is gated on this process-wide flag. Only the
        // vendored extractor consults it, and only this factory instantiates that extractor,
        // so refreshing it per created extractor keeps the flag coherent with the toggle.
        DolbyVisionCompatibility.setHdr10BaseLayerModeActive(enabled)
        if (!enabled) return extractor
        // The DV7 RPU rides in Matroska BlockAdditional, which the stock MatroskaExtractor
        // discards before any TrackOutput — swapping the extractor is the only seam.
        if (extractor.javaClass.name == STOCK_MATROSKA_EXTRACTOR) {
            return DvMatroskaExtractor(
                DefaultSubtitleParserFactory(),
                /* flags= */ 0,
                DolbyVisionStripTransformer(stripHdr10PlusSei)
            )
        }
        return extractor
    }

    private companion object {
        private const val STOCK_MATROSKA_EXTRACTOR = "androidx.media3.extractor.mkv.MatroskaExtractor"
    }
}
