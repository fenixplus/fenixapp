package com.arflix.tv.player.dv

import android.util.Log
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.DolbyVisionConfig
import com.arflix.tv.player.dvmkv.MatroskaExtractor
import java.util.concurrent.atomic.AtomicLong

/**
 * Strip-only implementation of the vendored Matroska extractor's
 * [MatroskaExtractor.DolbyVisionSampleTransformer] seam. A DV8.1 RPU-conversion path is
 * intentionally not implemented — this app plays the HDR10-compatible base layer; the conversion
 * seams remain in the vendored extractor for a future phase.
 *
 * Per sample: drops the Matroska BlockAdditional RPU, strips in-band DV RPU NALs (type 62) and
 * every enhancement-layer NAL (nuh_layer_id > 0) via [HevcDvRpuStripper]. The format-level
 * rewrite (video/dolby-vision → video/H265 with the container's base-layer codec string) happens
 * inside the vendored extractor, gated on
 * [com.arflix.tv.player.dvmkv.DolbyVisionCompatibility.isHdr10BaseLayerModeActive].
 *
 * Graceful degradation is load-bearing: any failure path returns the ORIGINAL sample (the
 * vendored extractor additionally catches transformer exceptions per sample) — a strip problem
 * must degrade to today's behavior, never to a hard playback error.
 */
@UnstableApi
internal class DolbyVisionStripTransformer(
    private val stripHdr10PlusSei: Boolean = false,
) : MatroskaExtractor.DolbyVisionSampleTransformer {

    private var lastTransformedLength = 0
    private var loggedFirstStrip = false

    override fun onDolbyVisionBlockAdditionalData(
        blockAdditionalData: ByteArray?,
        blockAddIdType: Int,
        dolbyVisionConfigBytes: ByteArray?
    ): ByteArray? {
        // Strip mode: never retain the BlockAdditional RPU.
        if (blockAdditionalData == null) return null
        return ByteArray(0)
    }

    override fun onHevcSample(
        sampleSizeBytes: Int,
        blockAdditionalData: ByteArray?,
        dolbyVisionConfigBytes: ByteArray?
    ) {
        // Telemetry-only seam; nothing to do.
    }

    override fun lastTransformedSampleLength(): Int = lastTransformedLength

    override fun transformHevcSample(
        sampleLengthDelimitedData: ByteArray?,
        sampleLength: Int,
        nalUnitLengthFieldLength: Int,
        blockAdditionalData: ByteArray?,
        dolbyVisionConfigBytes: ByteArray?
    ): ByteArray? {
        val sample = sampleLengthDelimitedData ?: return null
        lastTransformedLength = sampleLength

        // Profile 5 has NO backward-compatible base layer (IPTPQc2) — stripping its RPU would
        // leave undisplayable purple/green video. Pass through untouched.
        if (resolveProfile(dolbyVisionConfigBytes) == 5) {
            return stripHdr10PlusIfEnabled(sample, sampleLength, nalUnitLengthFieldLength) ?: sample
        }

        val stripped = HevcDvRpuStripper.stripRpuLengthDelimited(
            sample, sampleLength, nalUnitLengthFieldLength
        )
        if (stripped != null) {
            samplesStripped.incrementAndGet()
            if (!loggedFirstStrip) {
                loggedFirstStrip = true
                Log.i(TAG, "DV strip active: first sample rewritten ($sampleLength -> ${stripped.size} bytes)")
            }
            lastTransformedLength = stripped.size
            return stripHdr10PlusIfEnabled(stripped, stripped.size, nalUnitLengthFieldLength) ?: stripped
        }
        return stripHdr10PlusIfEnabled(sample, sampleLength, nalUnitLengthFieldLength) ?: sample
    }

    override fun onDolbyVisionCodecString(
        codecs: String?,
        dolbyVisionConfigBytes: ByteArray?
    ): String? {
        // Strip mode never advertises a DV codec rewrite — the extractor's
        // isHdr10BaseLayerModeActive branch swaps the whole format to base-layer HEVC instead.
        return null
    }

    private fun stripHdr10PlusIfEnabled(
        data: ByteArray,
        len: Int,
        nalLengthFieldLength: Int
    ): ByteArray? {
        if (!stripHdr10PlusSei) return null
        val stripped = HevcHdr10PlusStripper.stripHdr10PlusLengthDelimited(data, len, nalLengthFieldLength)
        if (stripped != null) {
            lastTransformedLength = stripped.size
            return stripped
        }
        return null
    }

    private fun resolveProfile(configBytes: ByteArray?): Int? {
        if (configBytes == null || configBytes.isEmpty()) return null
        return runCatching {
            DolbyVisionConfig.parse(ParsableByteArray(configBytes))?.profile
        }.getOrNull()
    }

    companion object {
        private const val TAG = "DvCompat"

        /** Process-wide count of rewritten samples — verification/diagnostics only. */
        val samplesStripped = AtomicLong(0)
    }
}
