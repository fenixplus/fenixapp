package com.arflix.tv.cast.engine

import java.nio.charset.Charset

object SubtitleText {
    val FORMATS = setOf("srt", "ass", "ssa", "vtt")
    const val MAX_BYTES = 4 * 1024 * 1024

    fun decode(bytes: ByteArray, language: String? = null): String {
        val text = withBom(bytes)
            ?: utf16WithoutBom(bytes)?.let { charset -> String(bytes, charset) }
            ?: strictUtf8(bytes)
            ?: String(bytes, Charsets.ISO_8859_1)
        return text.trimStart('\uFEFF')
    }

    private fun withBom(bytes: ByteArray): String? = when {
        bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
            String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
            String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
            String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        else -> null
    }

    private fun utf16WithoutBom(bytes: ByteArray): Charset? {
        if (bytes.size < 4) return null
        val sample = bytes.take(256)
        val zerosEven = sample.filterIndexed { i, b -> i % 2 == 0 && b == 0.toByte() }.size
        val zerosOdd = sample.filterIndexed { i, b -> i % 2 == 1 && b == 0.toByte() }.size
        val pairs = sample.size / 2
        return when {
            zerosEven > pairs / 3 && zerosOdd == 0 -> Charsets.UTF_16BE
            zerosOdd > pairs / 3 && zerosEven == 0 -> Charsets.UTF_16LE
            else -> null
        }
    }

    private fun strictUtf8(bytes: ByteArray): String? {
        return try {
            val decoder = Charsets.UTF_8.newDecoder()
            decoder.onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            decoder.onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) {
            null
        }
    }
}

