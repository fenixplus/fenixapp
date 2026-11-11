package io.github.anilbeesetti.nextlib.media3ext.ffdecoder

import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.SimpleDecoder
import androidx.media3.decoder.SimpleDecoderOutputBuffer

@UnstableApi
object FfmpegAudioBridge {

    fun decodes(mimeType: String): Boolean =
        runCatching { FfmpegLibrary.isAvailable() && FfmpegLibrary.supportsFormat(mimeType) }.getOrDefault(false)

    fun decoder(format: Format): SimpleDecoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, *> =
        FfmpegAudioDecoder(format, BUFFERS, BUFFERS, INPUT_BYTES, false)

    fun channelCount(decoder: Any): Int = (decoder as? FfmpegAudioDecoder)?.channelCount?.takeIf { it > 0 } ?: 2

    private const val BUFFERS = 16
    private const val INPUT_BYTES = 64 * 1024
}
