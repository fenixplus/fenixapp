package com.arflix.tv.cast.engine

import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * The bytes of one video as the cast server hands them to a receiver: its whole length, and a
 * reader opened at any byte. Each platform wraps its own streaming path in one: Android's Media3
 * data sources (`TdDataSource`, `JoinedDataSource` for split pieces), the desktop's `StreamBytes`
 * (`TdStreamBytes`, `JoinedStreamBytes`). Low storage mode and "Save mobile data" are those paths'
 * business, so a cast stream keeps to the same bounds as playing on the device.
 *
 * Reads block, the way a socket write does: the server runs each request on its own thread.
 */
interface CastSource {

    /** The whole file's length in bytes. */
    val length: Long

    /** A reader positioned at [position]; it ends at the end of the file. */
    fun open(position: Long): CastReader

    /**
     * A read at [position] stalled ([RecoveringCastSource]): asks whatever fetches the bytes for
     * them again from there, before the read is opened afresh. Nothing to do for a file on the
     * disk, or for a source whose [open] already asks (Android's Media3 data sources do).
     */
    fun askAgain(position: Long) = Unit
}

/** One open read of a [CastSource], from where it was opened to the end of the file. */
interface CastReader : Closeable {

    /** Up to [length] bytes into [buffer], how many were read, or -1 at the end of the file. */
    fun read(buffer: ByteArray, offset: Int, length: Int): Int
}

/** Exactly [length] bytes at [position] (fewer at the end of the file), for probing and the remuxer. */
fun CastSource.readAt(position: Long, length: Int): ByteArray {
    val total = this.length
    val wanted = if (total > 0L) minOf(length.toLong(), (total - position).coerceAtLeast(0)).toInt() else length
    val out = ByteArray(wanted)
    if (wanted == 0) return out
    open(position).use { reader ->
        var at = 0
        while (at < wanted) {
            val n = reader.read(out, at, wanted - at)
            if (n < 0) {
                if (at > 0) return out.copyOf(at)
                throw EOFException("Short read at ${position + at}")
            }
            at += n
        }
    }
    return out
}

/** A file on the disk: a download, a finished TDLib file, or a test fixture. */
class FileCastSource(private val file: File) : CastSource {

    override val length: Long = file.length()

    override fun open(position: Long): CastReader {
        val raf = RandomAccessFile(file, "r")
        try {
            raf.seek(position)
        } catch (e: IOException) {
            raf.close()
            throw e
        }
        return object : CastReader {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = raf.read(buffer, offset, length)
            override fun close() = raf.close()
        }
    }
}

/** Bytes in memory, for the tests. */
class BytesCastSource(private val bytes: ByteArray) : CastSource {

    override val length: Long get() = bytes.size.toLong()

    override fun open(position: Long): CastReader {
        var at = position.toInt().coerceIn(0, bytes.size)
        return object : CastReader {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (at >= bytes.size) return -1
                val n = minOf(length, bytes.size - at)
                System.arraycopy(bytes, at, buffer, offset, n)
                at += n
                return n
            }

            override fun close() = Unit
        }
    }
}

/**
 * A reader over a source with a cursor that can jump, for the parsers: sequential reads come off
 * one open reader and a buffer, and a jump far away opens the source again there.
 */
class CastCursor(private val source: CastSource, start: Long = 0L) : Closeable {

    val length: Long get() = source.length

    private var reader: CastReader? = null
    private var readerAt = 0L
    private val buffer = ByteArray(BUFFER)
    private var bufferStart = 0L
    private var bufferLength = 0

    /** Where the next byte comes from. */
    var position: Long = start
        private set

    fun seek(to: Long) {
        position = to
    }

    /** One byte, or -1 at the end. */
    fun read(): Int {
        if (!fill()) return -1
        val b = buffer[(position - bufferStart).toInt()].toInt() and 0xFF
        position++
        return b
    }

    /** Exactly [n] bytes, or an EOFException. */
    fun readBytes(n: Int): ByteArray {
        val out = ByteArray(n)
        var at = 0
        while (at < n) {
            if (!fill()) throw EOFException("Short read at $position")
            val offset = (position - bufferStart).toInt()
            val take = minOf(n - at, bufferLength - offset)
            System.arraycopy(buffer, offset, out, at, take)
            at += take
            position += take
        }
        return out
    }

    fun skip(n: Long) {
        position += n
    }

    private fun fill(): Boolean {
        if (position >= bufferStart && position < bufferStart + bufferLength) return true
        if (position >= source.length) return false
        // A forward hop inside one buffer's reach keeps reading the open reader; anything else
        // opens the source again at the new place.
        val open = reader
        if (open == null || position < readerAt || position - readerAt > SKIP_LIMIT) {
            runCatching { open?.close() }
            reader = source.open(position)
            readerAt = position
        }
        val r = reader!!
        while (readerAt < position) {
            val n = r.read(buffer, 0, minOf(BUFFER.toLong(), position - readerAt).toInt())
            if (n < 0) return false
            readerAt += n
        }
        val n = r.read(buffer, 0, BUFFER)
        if (n <= 0) return false
        bufferStart = readerAt
        bufferLength = n
        readerAt += n
        return true
    }

    override fun close() {
        runCatching { reader?.close() }
        reader = null
    }

    private companion object {
        const val BUFFER = 64 * 1024
        const val SKIP_LIMIT = 512L * 1024
    }
}

