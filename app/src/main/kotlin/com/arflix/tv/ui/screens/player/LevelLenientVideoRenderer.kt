package com.arflix.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererCapabilities

/**
 * The device's video renderer, trusting the picture's size over the level its file claims.
 * Ported from TMPlayer.
 *
 * Encoders often stamp a 1080p HEVC or H.264 file with a level far above what its size needs
 * (`hvc1.2.4.L153`, level 5.1, on a plain 1920x1080 film). A TV chip that declares level 4.1, as
 * the Amlogic decoder in the 1 GB Mi TV / Fire TV Stick does, is then reported unable to take it,
 * Media3 hands the picture to software instead or fails with FORMAT_EXCEEDS_CAPABILITIES.
 * The chip plays such files without trouble: the level is a ceiling on the stream, and the stream
 * is well under it. Kodi and Jellyfin ignore the claimed level for the same reason.
 *
 * So a format the device's decoder turns down only as exceeding its capabilities is asked again
 * with the level the picture's size actually needs ([CodecLevel.relaxed]); when the decoder takes
 * that, the device's decoder is what the track selector sees as fit. The renderer itself still
 * gets the real format. Profiles are left as they are: a chip without 10-bit HEVC stays without it.
 */
@UnstableApi
class LevelLenientVideoRenderer(renderer: Renderer) : ForwardingRenderer(renderer) {

    private val lenient: RendererCapabilities by lazy {
        val inner = super.getCapabilities()
        object : RendererCapabilities by inner {
            override fun supportsFormat(format: Format): Int {
                val support = inner.supportsFormat(format)
                if (RendererCapabilities.getFormatSupport(support) != C.FORMAT_EXCEEDS_CAPABILITIES) return support
                val relaxed = CodecLevel.relaxed(format) ?: return support
                val again = inner.supportsFormat(relaxed)
                return if (RendererCapabilities.getFormatSupport(again) == C.FORMAT_HANDLED) again else support
            }
        }
    }

    override fun getCapabilities(): RendererCapabilities = lenient
}

/** The codec level a picture's size needs, written back into its codecs string. See [LevelLenientVideoRenderer]. */
object CodecLevel {

    /**
     * [format] with its claimed level lowered to 4.1 when the picture is no larger than 1080p and
     * the claim is higher; null when nothing would change (no size, larger than 1080p, a codec
     * other than HEVC or H.264, an unreadable string, or a claim already at 4.1 or under).
     */
    fun relaxed(format: Format): Format? {
        if (format.width <= 0 || format.height <= 0) return null
        if (maxOf(format.width, format.height) > FULL_HD_LONG || minOf(format.width, format.height) > FULL_HD_SHORT) return null
        val codecs = format.codecs ?: return null
        val lowered = when (format.sampleMimeType) {
            MimeTypes.VIDEO_H265 -> hevc(codecs)
            MimeTypes.VIDEO_H264 -> avc(codecs)
            else -> null
        } ?: return null
        return format.buildUpon().setCodecs(lowered).build()
    }

    /** `hvc1.2.4.L153.90` to `hvc1.2.4.L123.90`; high tier (`H153`) to main tier at 4.1 as well. */
    internal fun hevc(codecs: String): String? {
        val parts = codecs.split('.').toMutableList()
        if (parts.size < 4 || (parts[0] != "hvc1" && parts[0] != "hev1")) return null
        val tier = parts[3]
        if (tier.length < 2 || (tier[0] != 'L' && tier[0] != 'H')) return null
        val level = tier.substring(1).toIntOrNull() ?: return null
        if (level <= HEVC_LEVEL_41 && tier[0] == 'L') return null
        parts[3] = "L$HEVC_LEVEL_41"
        return parts.joinToString(".")
    }

    /** `avc1.640033` (High at 5.1) to `avc1.640029` (High at 4.1). */
    internal fun avc(codecs: String): String? {
        val parts = codecs.split('.')
        if (parts.size < 2 || (parts[0] != "avc1" && parts[0] != "avc3")) return null
        val profile = parts[1]
        if (profile.length != 6) return null
        val level = profile.substring(4).toIntOrNull(16) ?: return null
        if (level <= AVC_LEVEL_41) return null
        return parts[0] + "." + profile.substring(0, 4) + "%02X".format(AVC_LEVEL_41) +
            parts.drop(2).joinToString("") { ".$it" }
    }

    /** HEVC's level 4.1 is written as 30 times the level; it covers 1080p at 60 frames. */
    private const val HEVC_LEVEL_41 = 123

    /** AVC's level 4.1 in hex notation (41 decimal = 0x29). */
    private const val AVC_LEVEL_41 = 0x29

    private const val FULL_HD_LONG = 1920
    private const val FULL_HD_SHORT = 1088
}
