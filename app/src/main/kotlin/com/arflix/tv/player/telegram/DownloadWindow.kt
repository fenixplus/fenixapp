package com.arflix.tv.player.telegram

/**
 * How much of a partially downloaded Telegram file can be read right now.
 *
 * TDLib fills one contiguous window at a time: bytes [downloadOffset, downloadOffset +
 * downloadedPrefixSize) of the file are on disk and valid, everything else is a hole. Seeking
 * moves that window, which is the whole reason playback needs this arithmetic rather than
 * just trusting the file length.
 */
object DownloadWindow {

    /**
     * Number of bytes readable starting at [position], or 0 if that byte is not downloaded yet.
     *
     * @param size total file size, or 0 when TDLib does not know it yet
     */
    fun availableAt(
        position: Long,
        size: Long,
        downloadOffset: Long,
        downloadedPrefixSize: Long,
        completed: Boolean,
    ): Long {
        if (position < 0) return 0
        if (completed) return (size - position).coerceAtLeast(0)

        val windowEnd = downloadOffset + downloadedPrefixSize
        if (position < downloadOffset || position >= windowEnd) return 0
        return windowEnd - position
    }

    /**
     * Whether the download has to be restarted at [position].
     *
     * True when the byte we want sits outside the current window, either behind it (the user
     * seeked back) or far enough ahead that waiting for the window to reach it would stall.
     */
    fun needsRestart(
        position: Long,
        downloadOffset: Long,
        downloadedPrefixSize: Long,
        active: Boolean,
        completed: Boolean,
    ): Boolean {
        if (completed) return false
        if (position < downloadOffset) return true
        val windowEnd = downloadOffset + downloadedPrefixSize
        if (position > windowEnd + FORWARD_SLACK_BYTES) return true
        // Sitting exactly at the edge with nothing downloading means TDLib gave up on us.
        return !active && position >= windowEnd
    }

    /**
     * How much of a probe of [start, end) has to be on disk before the read at [target] is served.
     */
    fun probeReady(start: Long, end: Long, target: Long, readyBytes: Long): Long {
        val at = target.coerceIn(start, end)
        return minOf(end, at + readyBytes) - start
    }

    /**
     * A seek within this many bytes ahead of what is already downloaded is left alone: the
     * running download will reach it sooner than a restart would.
     */
    const val FORWARD_SLACK_BYTES = 4L * 1024 * 1024
}

/**
 * Arithmetic for bounded streaming windows so huge videos don't overwhelm device disk.
 */
object LowStorageWindow {
    const val WINDOW_BYTES = 200L * 1024 * 1024
    const val MIN_WINDOW_BYTES = 32L * 1024 * 1024
    const val BACK_BYTES = 2L * 1024 * 1024
    const val ALIGN_BYTES = 1024L * 1024
    const val ROLL_MARGIN_BYTES = 4L * 1024 * 1024
    const val SLACK_BYTES = 16L * 1024 * 1024
    const val INDEX_TAIL_BYTES = 16L * 1024 * 1024

    data class Range(val start: Long, val end: Long) {
        val length: Long get() = end - start
    }

    data class Request(val limit: Long, val deleteFirst: Boolean, val ranges: List<Range>)

    fun merge(ranges: List<Range>): List<Range> {
        val sorted = ranges.filter { it.length > 0 }.sortedBy { it.start }
        val out = mutableListOf<Range>()
        for (r in sorted) {
            val last = out.lastOrNull()
            if (last != null && r.start <= last.end) {
                out[out.lastIndex] = Range(last.start, maxOf(last.end, r.end))
            } else {
                out += r
            }
        }
        return out
    }

    fun bytes(ranges: List<Range>): Long = merge(ranges).sumOf { it.length }

    fun plan(ranges: List<Range>, offset: Long, size: Long, window: Long, roll: Boolean = false): Request {
        val isTail = nearTheEnd(offset, size)
        val effectiveWindow = if (isTail) minOf(window, INDEX_TAIL_BYTES) else window
        val end = if (size > 0) minOf(size, offset + effectiveWindow) else offset + effectiveWindow
        val limit = (end - offset).coerceAtLeast(1)
        val wanted = Range(offset, end)

        // Reading metadata/index at the end of the file: never delete previous ranges!
        if (isTail) {
            return Request(limit, deleteFirst = false, ranges = merge(ranges + wanted))
        }

        val kept = merge(ranges + wanted)
        val budget = window + SLACK_BYTES
        if (roll) return Request(limit, deleteFirst = true, ranges = listOf(wanted))
        if (bytes(kept) <= budget) return Request(limit, deleteFirst = false, ranges = kept)

        val held = merge(ranges)
        val here = held.firstOrNull { offset >= it.start && offset < it.end }
        if (here != null) {
            val spare = (budget - bytes(held)).coerceAtLeast(0)
            val shortEnd = if (size > 0) minOf(size, here.end + spare) else here.end + spare
            if (shortEnd - offset > ROLL_MARGIN_BYTES) {
                return Request(shortEnd - offset, deleteFirst = false, ranges = merge(held + Range(offset, shortEnd)))
            }
        }
        // Only trigger deleteFirst when explicitly rolling forward after consuming a window
        return Request(limit, deleteFirst = false, ranges = listOf(wanted))
    }

    fun nearTheEnd(position: Long, size: Long): Boolean =
        size > 0 && position < size && size - position <= INDEX_TAIL_BYTES

    fun shouldRoll(position: Long, ranges: List<Range>, size: Long): Boolean {
        val here = merge(ranges).firstOrNull { position >= it.start && position < it.end } ?: return false
        if (size > 0 && here.end >= size) return false
        return position >= here.end - ROLL_MARGIN_BYTES
    }

    fun rollStart(position: Long): Long {
        val back = (position - BACK_BYTES).coerceAtLeast(0)
        return back - back % ALIGN_BYTES
    }
}
