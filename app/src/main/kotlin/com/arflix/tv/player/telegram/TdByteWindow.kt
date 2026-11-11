package com.arflix.tv.player.telegram

import android.util.Log
import com.arflix.tv.data.telegram.TelegramClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.TdApi
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Manages the streaming window of a Telegram file directly through TDLib without an HTTP proxy.
 *
 * TDLib downloads chunks to disk in its cache directory, and this window reads bytes
 * directly using [FileChannel] (NIO) as soon as TDLib confirms them via [TdApi.UpdateFile].
 */
class TdByteWindow(
    private val client: TelegramClient,
    private val isDownloadingCheck: ((Int) -> Boolean)? = null,
) {

    var fileId = -1
        private set

    var remoteId: String? = null
        private set

    /** The file's full size, as of the last [lookUp]. */
    var size = 0L
        private set

    private var handle: FileChannel? = null
    private var fileInputStream: FileInputStream? = null
    private var localPath: String? = null

    @Volatile
    private var window = Window.EMPTY

    @Volatile
    private var side = Window.EMPTY

    @Volatile
    private var playhead: Playhead? = null

    @Volatile
    var completed = false
        private set

    @Volatile
    private var downloading = false

    @Volatile
    private var downloadedTotal = 0L

    private val rollLock = Mutex()

    private var updates: Job? = null
    private var scope: CoroutineScope? = null

    private val revision = MutableStateFlow(0L)

    @Volatile
    private var shut = false

    private fun isOfflineDownload(): Boolean = isDownloadingCheck?.invoke(fileId) == true

    private data class Window(val start: Long, val end: Long) {
        val isEmpty: Boolean get() = end <= start

        companion object {
            val EMPTY = Window(0, 0)
        }
    }

    fun watch(fileId: Int, remoteId: String? = null) {
        shut = false
        val isSameFile = this.fileId == fileId && fileId > 0
        this.fileId = fileId
        this.remoteId = remoteId
        if (!isSameFile) {
            playhead?.let(::letGo)
            playhead = if (fileId > 0) takePlayhead(fileId) else null
        } else if (playhead == null && fileId > 0) {
            playhead = takePlayhead(fileId)
        }
        stopWatching()
        if (fileId > 0) {
            startWatcher(fileId)
        }
    }

    private fun startWatcher(targetFileId: Int) {
        val watcher = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = watcher
        updates = watcher.launch {
            client.fileUpdates.filter { it.file.id == targetFileId }.collect { update ->
                absorb(update.file)
                revision.value += 1
            }
        }
    }

    suspend fun lookUp(): Long {
        val initialFile: TdApi.File? = if (fileId > 0) {
            try {
                client.getFile(fileId)
            } catch (e: Exception) {
                Log.w(TAG, "Failed looking up fileId $fileId: ${e.message}")
                null
            }
        } else {
            null
        }

        val resolvedFile: TdApi.File = initialFile ?: run {
            val rid = remoteId
            if (!rid.isNullOrBlank()) {
                Log.i(TAG, "Resolving file via remoteId: $rid")
                val rFile = try {
                    client.getRemoteFile(rid)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed looking up remoteId $rid: ${e.message}")
                    null
                }
                if (rFile != null) {
                    Log.i(TAG, "Resolved remoteId $rid to new fileId ${rFile.id} (was $fileId)")
                    fileId = rFile.id
                    stopWatching()
                    startWatcher(rFile.id)
                    rFile
                } else {
                    null
                }
            } else {
                null
            }
        } ?: throw IOException("Telegram lost track of file (id=$fileId, remote=$remoteId)")

        var currentFile = resolvedFile
        val local = currentFile.local
        val path = local?.path
        val diskFile = path?.takeIf { it.isNotBlank() }?.let { File(it) }
        val isGhost = (local != null && (local.isDownloadingCompleted || local.downloadedPrefixSize > 0L || local.downloadedSize > 0L)) &&
                (diskFile == null || !diskFile.exists() || diskFile.length() == 0L)
        if (isGhost) {
            Log.w(TAG, "Ghost file detected for fileId=$fileId at $path. Resetting TDLib via deleteFile.")
            client.deleteFile(currentFile.id)
            val refreshed = client.getFile(currentFile.id)
            if (refreshed != null) {
                currentFile = refreshed
            }
        }

        size = if (currentFile.size > 0) currentFile.size else currentFile.expectedSize
        if (size <= 0) throw IOException("Unknown size for file $fileId")
        absorb(currentFile)
        return size
    }

    fun cachedAvailable(target: Long): Long? {
        if (localPath == null) return null
        if (shouldRollDue(target)) return null
        val known = window
        val main = DownloadWindow.availableAt(
            position = target,
            size = size,
            downloadOffset = known.start,
            downloadedPrefixSize = known.end - known.start,
            completed = completed,
        )
        if (main > 0) return main
        if (promotionDue(target)) return null
        val aside = side
        return (if (target >= aside.start && target < aside.end) aside.end - target else 0L).takeIf { it > 0 }
    }

    fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        val read = synchronized(this) {
            val known = window
            val aside = side
            val fits = when {
                completed -> (size - position).coerceAtLeast(0)
                position >= known.start && position < known.end -> known.end - position
                position >= aside.start && position < aside.end -> aside.end - position
                else -> 0L
            }
            if (fits <= 0) throw EOFException("Window moved away from $position of file $fileId")
            openHandle().read(ByteBuffer.wrap(buffer, offset, minOf(length.toLong(), fits).toInt()), position)
        }
        playhead?.let { ph ->
            val p = ph.probe
            if (read > 0 && (position < p.start || position >= p.end)) ph.mainAt = position + read
        }
        if (read <= 0) {
            closeHandle()
            window = Window.EMPTY
            side = Window.EMPTY
            completed = false
            throw EOFException("Short read at $position of file $fileId")
        }
        return read
    }

    fun close() {
        shut = true
        stopWatching()
        closeHandle()
        window = Window.EMPTY
        side = Window.EMPTY
        completed = false
        playhead?.let(::letGo)
        playhead = null
    }

    private fun absorb(file: TdApi.File) {
        val local = file.local ?: return
        val path = local.path
        val diskFile = path?.takeIf { it.isNotBlank() }?.let { File(it) }
        val usablePath = path?.takeIf { diskFile?.isFile == true }
        downloading = local.isDownloadingActive
        downloadedTotal = local.downloadedSize

        synchronized(this) {
            if (usablePath != localPath) {
                closeHandle()
                localPath = usablePath
                side = Window.EMPTY
            }
            if (local.isDownloadingCompleted) {
                completed = true
                window = Window(0, size)
            } else {
                completed = false
                val start = local.downloadOffset
                window = Window(start, start + local.downloadedPrefixSize)
            }
        }
    }

    private fun stopWatching() {
        updates = null
        scope?.cancel()
        scope = null
    }

    suspend fun awaitBytesAt(target: Long): Long {
        rollIfDue(target)
        promoteIfDue(target)
        cachedAvailable(target)?.let { return it }

        val file = client.getFile(fileId) ?: throw IOException("File $fileId disappeared")
        var available = available(file, target)
        if (available > 0) return available
        onDiskAt(target).takeIf { it > 0 }?.let { run ->
            followSide(target, run)
            return run
        }
        if (isFar(target)) probeAt(target)?.let { return it }

        var stallAttempt = 1
        var deadline = System.currentTimeMillis() + INACTIVITY_TIMEOUT_MS
        var nudgeAt = System.currentTimeMillis() + NUDGE_AFTER_MS
        var totalAtAsk = downloadedTotal
        var nudges = 0

        while (available == 0L) {
            if (shut) throw IOException("File $fileId was closed while a read waited")
            val known = window

            // Se bytes estão sendo baixados continuamente pela TDLib, estendemos o prazo
            if (downloadedTotal > totalAtAsk) {
                val growth = downloadedTotal - totalAtAsk
                deadline = System.currentTimeMillis() + INACTIVITY_TIMEOUT_MS

                // Como no TMPlayer: só fazemos nudge se a TDLib estiver baixando ativamente dados
                // em outro lugar distante do arquivo (ex: após busca de metadados) sem avançar no offset pedido.
                // Se o download já estiver no mesmo bloco do target (diferença <= 4MB), NÃO reiniciamos o pedido
                // para não interromper a chegada dos pacotes do próprio bloco em andamento.
                val isDownloadingElsewhere = downloading && kotlin.math.abs(known.start - target) > DownloadWindow.FORWARD_SLACK_BYTES
                if (isDownloadingElsewhere && System.currentTimeMillis() >= nudgeAt && growth >= NUDGE_ELSEWHERE_BYTES) {
                    nudges++
                    Log.i(TAG, "TDLib entregando em outro offset (atual=${known.start}, target=$target); solicitando novamente ($nudges)")
                    if (!isOfflineDownload() && nudges >= 1) {
                        Log.i(TAG, "Cancelando download anterior preso em outro offset para destravar offset $target")
                        runCatching { client.cancelDownloadFile(fileId, onlyIfPending = false) }
                    }
                    requestDownloadFrom(target)
                    nudgeAt = System.currentTimeMillis() + NUDGE_AFTER_MS
                    totalAtAsk = downloadedTotal
                }
            }

            val inActiveProbe = playhead?.let { inProbe(it, target) } == true
            if (!inActiveProbe && DownloadWindow.needsRestart(
                    position = target,
                    downloadOffset = known.start,
                    downloadedPrefixSize = known.end - known.start,
                    active = downloading,
                    completed = completed,
                )
            ) {
                val isRealSeek = !isOfflineDownload() && downloading && isFar(target) && !isNearEnd(target)
                if (isRealSeek) {
                    Log.i(TAG, "Seek real detectado em awaitBytesAt para $target (anterior=${playhead?.mainAt}): cancelando download anterior")
                    runCatching { client.cancelDownloadFile(fileId, onlyIfPending = false) }
                }
                markSeek(target)
                requestDownloadFrom(target)
            } else if (!downloading && !completed && !inActiveProbe && nudges < 3 && System.currentTimeMillis() >= nudgeAt) {
                nudges++
                Log.w(TAG, "TDLib inativa detectada no offset $target. Re-solicitando download ($nudges)...")
                requestDownloadFrom(target)
                nudgeAt = System.currentTimeMillis() + 4_000L
            }

            val seen = revision.value
            val heard = withTimeoutOrNull(POLL_INTERVAL_MS) {
                revision.first { it != seen }
            } != null

            available = if (heard) {
                cachedAvailable(target) ?: 0L
            } else {
                val fresh = client.getFile(fileId) ?: throw IOException("File $fileId disappeared")
                available(fresh, target)
            }

            if (available == 0L && System.currentTimeMillis() >= deadline) {
                if (stallAttempt == 1 && !isOfflineDownload()) {
                    stallAttempt = 2
                    Log.w(TAG, "⏳ Nenhum byte recebido após 15s para fileId=$fileId no offset $target. Cancelando e aguardando backoff de 1.5s antes de retentar...")
                    runCatching { client.cancelDownloadFile(fileId, onlyIfPending = false) }
                    kotlinx.coroutines.delay(BACKOFF_DELAY_MS)
                    requestDownloadFrom(target)
                    totalAtAsk = downloadedTotal
                    deadline = System.currentTimeMillis() + INACTIVITY_TIMEOUT_MS
                } else {
                    throw IOException("Telegram stopped sending file $fileId at byte $target (inactivity timeout)")
                }
            }
        }
        if (available > 0) {
            Log.d(TAG, "📦 TDLib entregou dados: ${available / 1024}KB prontos no offset $target [fileId=$fileId]")
        }
        return available
    }

    private suspend fun onDiskAt(target: Long): Long {
        if (localPath == null) return 0
        val here = client.getFileDownloadedPrefixSize(fileId, target)
        if (here <= 0) return 0
        synchronized(this) { side = Window(target, minOf(size, target + here)) }
        return here
    }

    private fun available(file: TdApi.File, target: Long): Long {
        absorb(file)
        val local = file.local ?: return 0
        if (localPath == null) return 0
        return DownloadWindow.availableAt(
            position = target,
            size = size,
            downloadOffset = local.downloadOffset,
            downloadedPrefixSize = local.downloadedPrefixSize,
            completed = completed,
        )
    }

    private suspend fun deleteForLowStorage(offset: Long) {
        if (isOfflineDownload()) return
        Log.i(TAG, "Low storage: deleting old chunk for file $fileId before offset $offset")
        synchronized(this) {
            closeHandle()
            window = Window.EMPTY
            side = Window.EMPTY
        }
        runCatching { client.deleteFile(fileId) }
    }

    private suspend fun rollIfDue(target: Long) = rollLock.withLock {
        if (isOfflineDownload()) return@withLock
        val ranges = fileRanges[fileId] ?: emptyList()
        if (!LowStorageWindow.shouldRoll(target, ranges, size)) return@withLock
        val from = LowStorageWindow.rollStart(target)
        Log.i(TAG, "Low storage: rolling file $fileId at $target from $from")
        requestDownloadFrom(from, roll = true)
    }

    private fun shouldRollDue(target: Long): Boolean {
        if (isOfflineDownload()) return false
        val ranges = fileRanges[fileId] ?: emptyList()
        return LowStorageWindow.shouldRoll(target, ranges, size)
    }

    suspend fun requestDownloadFrom(offset: Long, roll: Boolean = false) {
        if (shut) throw IOException("File $fileId is closed")
        if (isOfflineDownload()) {
            client.downloadFile(fileId = fileId, priority = 32, offset = offset, limit = 0L, synchronous = false)
            return
        }

        val mutex = downloadLocks.getOrPut(fileId) { Mutex() }
        mutex.withLock {
            val now = System.currentTimeMillis()
            val lastOffset = lastRequestedOffsets[fileId] ?: -1L
            val lastTime = lastRequestedTimes[fileId] ?: 0L

            // Se for o mesmo offset pedido há menos de 1000ms, ignora o re-pedido redundante
            if (!roll && offset == lastOffset && (now - lastTime < 1_000L)) {
                return@withLock
            }

            // Se for offset diferente pedido há menos de 250ms por outra thread,
            // evita ping-pong catastrófico se a TDLib já estiver baixando
            if (!roll && offset != lastOffset && (now - lastTime < 250L)) {
                Log.w(TAG, "Deduplicating rapid download switch: ignoring offset=$offset because offset=$lastOffset was requested ${now - lastTime}ms ago")
                return@withLock
            }

            val ranges = fileRanges[fileId] ?: emptyList()
            val bounded = LowStorageWindow.plan(
                ranges = ranges,
                offset = offset,
                size = size,
                window = LowStorageWindow.WINDOW_BYTES,
                roll = roll,
            )

            if (bounded.deleteFirst) {
                deleteForLowStorage(offset)
            }

            Log.d(TAG, "Low storage: requesting download fileId=$fileId offset=$offset limit=${bounded.limit} roll=$roll deleteFirst=${bounded.deleteFirst}")
            client.downloadFile(fileId = fileId, priority = 32, offset = offset, limit = bounded.limit, synchronous = false)

            lastRequestedOffsets[fileId] = offset
            lastRequestedTimes[fileId] = now
            fileRanges[fileId] = bounded.ranges

            if (bounded.deleteFirst) {
                client.getFile(fileId)?.let(::absorb)
            }
        }
    }

    suspend fun requestUnlessOnDisk(offset: Long) {
        val known = window
        if (!DownloadWindow.needsRestart(
                position = offset,
                downloadOffset = known.start,
                downloadedPrefixSize = known.end - known.start,
                active = downloading,
                completed = completed,
            )
        ) {
            return
        }
        val run = onDiskAt(offset)
        if (run > 0) {
            followSide(offset, run)
            return
        }
        // Se a leitura for um probe distante no FINAL do arquivo (ex: Cues/moov),
        // NÃO reapontamos o download principal aqui: deixamos para o read() buscar como probe,
        // mantendo o fluxo do vídeo no início da reprodução!
        // No entanto, leituras próximas ao início do arquivo (como os primeiros frames de vídeo/áudio)
        // NUNCA podem ser ignoradas.
        if (isFar(offset) && isNearEnd(offset)) return

        // Se é um seek distante real (fora do buffer ativo e não é probe no fim),
        // cancelamos o download anterior para que a TDLib não fique gastando banda com o trecho velho
        val isRealSeek = !isOfflineDownload() && downloading && isFar(offset) && !isNearEnd(offset)
        if (isRealSeek) {
            Log.i(TAG, "Seek real detectado para $offset (anterior=${playhead?.mainAt}): cancelando download anterior para focar na nova posição")
            runCatching { client.cancelDownloadFile(fileId, onlyIfPending = false) }
        }

        markSeek(offset)
        requestDownloadFrom(offset)
    }

    private fun isNearEnd(offset: Long): Boolean =
        size > 0 && offset > size - LowStorageWindow.INDEX_TAIL_BYTES

    private fun followSide(target: Long, run: Long) {
        val ph = playhead ?: return
        if (inProbe(ph, target)) return
        val end = minOf(size, target + run)
        if (isFar(target) && target > ph.mainAt) {
            ph.probe = Window(target, end)
            return
        }
        markSeek(target)
    }

    private fun isFar(target: Long): Boolean {
        if (completed) return false
        val ph = playhead ?: return false
        val at = ph.mainAt
        if (at < 0) return false
        if (size > 0 && window.end >= size) return false
        if (inProbe(ph, target)) return false
        return kotlin.math.abs(target - at) > DownloadWindow.FORWARD_SLACK_BYTES
    }

    private fun inProbe(ph: Playhead, target: Long): Boolean {
        val p = ph.probe
        return !p.isEmpty && target >= p.start && target < p.end
    }

    private fun markSeek(target: Long) {
        val ph = playhead ?: return
        ph.mainAt = target
        ph.probe = Window.EMPTY
    }

    private fun promotionDue(target: Long): Boolean {
        val ph = playhead ?: return false
        val p = ph.probe
        if (p.isEmpty || p.end >= size) return false
        return target >= p.end - PROMOTE_MARGIN_BYTES && target <= p.end + PROBE_ALIGN_BYTES
    }

    private suspend fun promoteIfDue(target: Long) {
        if (!promotionDue(target)) return
        val p = playhead?.probe ?: return
        Log.d(TAG, "File $fileId read past probe at ${p.start}: following seek from $target")
        markSeek(target)
        requestDownloadFrom(target)
    }

    private suspend fun probeAt(target: Long): Long? {
        val ph = playhead ?: return null
        val start = target - target % PROBE_ALIGN_BYTES
        var end = start + PROBE_BYTES
        if (end - target < PROBE_BYTES / 2) end += PROBE_BYTES
        if (size > 0) end = minOf(size, end)
        if (end <= target) return null

        ph.probe = Window(start, end)
        Log.i(TAG, "File $fileId probed at $target: $start to $end on its own, stream stays at ${ph.mainAt}")
        if (shut) throw IOException("File $fileId is closed")

        suspend fun ask() {
            client.downloadFile(
                fileId = fileId,
                priority = 32,
                offset = start,
                limit = end - start,
                synchronous = false
            )
        }
        ask()

        val wait = ProbeWait(System.currentTimeMillis())
        val probe = Window(start, end)
        val ready = PROBE_READY_BYTES
        val have = awaitProbe(target, start, ready, wait) { ask() }
        if (have < 0) {
            Log.i(TAG, "File $fileId: probe at $target brought too little; reading as seek")
            ph.probe = Window.EMPTY
            return null
        }
        val run = onDiskAt(target)
        val rest = scope
        if (have >= end - start || run <= 0 || rest == null) {
            val finished = have >= end - start || awaitProbe(target, start, end - start, wait) { ask() } >= 0
            if (!finished) {
                Log.i(TAG, "File $fileId: probe at $target did not finish; reading as seek")
                ph.probe = Window.EMPTY
                return null
            }
            val whole = if (run > 0 && have >= end - start) run else onDiskAt(target)
            putBack(ph)
            return whole.takeIf { it > 0 }
        }
        rest.launch {
            try {
                val whole = awaitProbe(target, start, end - start, wait) { ask() }
                if (ph.probe != probe) return@launch
                if (whole < 0) {
                    Log.i(TAG, "File $fileId: probe at $target did not finish in background")
                    return@launch
                }
                onDiskAt(target)
                putBack(ph)
            } catch (e: Exception) {
                Log.w(TAG, "File $fileId: probe rest error: ${e.message}")
            }
        }
        return run
    }

    private class ProbeWait(val started: Long) {
        var lastHave = -1L
        var lastGrowth = started
        var asked = 1
    }

    private suspend fun awaitProbe(target: Long, start: Long, need: Long, wait: ProbeWait, ask: suspend () -> Unit): Long {
        while (true) {
            if (shut) throw IOException("File $fileId was closed while a read waited")
            val have = client.getFileDownloadedPrefixSize(fileId, start)
            if (have >= need) return have
            val now = System.currentTimeMillis()
            if (have > wait.lastHave) {
                wait.lastHave = have
                wait.lastGrowth = now
            }
            if (now - wait.started >= PROBE_GIVE_UP_MS) {
                Log.i(TAG, "File $fileId: probe at $target timed out waiting for $need bytes")
                return -1
            }
            if (now - wait.lastGrowth >= PROBE_NUDGE_MS && wait.asked < 2) {
                Log.i(TAG, "File $fileId: no bytes for probe at $target; asking again")
                if (!isOfflineDownload()) {
                    client.cancelDownloadFile(fileId, onlyIfPending = false)
                }
                ask()
                wait.asked++
                wait.lastGrowth = now
            }
            val seen = revision.value
            withTimeoutOrNull(POLL_INTERVAL_MS) {
                revision.first { it != seen }
            }
        }
    }

    private suspend fun putBack(ph: Playhead) {
        val back = ph.mainAt
        // Se a reprodução está no início do vídeo (primeiros megabytes), garantimos que o download cubra
        // os primeiros frames a partir do byte 0 se eles ainda não estiverem completos no disco.
        val target = if (back in 0 until 4L * 1024L * 1024L) {
            val prefix = client.getFileDownloadedPrefixSize(fileId, 0L)
            if (prefix < back) 0L else back
        } else {
            back
        }
        if (target in 0 until size && !completed) {
            Log.d(TAG, "Returning download to main stream at $target (mainAt was $back) [fileId=$fileId]")
            requestDownloadFrom(target)
        }
    }

    private fun openHandle(): FileChannel = handle ?: run {
        val path = localPath ?: throw IOException("No local file for $fileId yet")
        val fis = FileInputStream(File(path)).also { fileInputStream = it }
        fis.channel.also { handle = it }
    }

    private fun closeHandle() = synchronized(this) {
        runCatching { handle?.close() }
        handle = null
        runCatching { fileInputStream?.close() }
        fileInputStream = null
    }

    companion object {
        private const val TAG = "TdByteWindow"
        const val INACTIVITY_TIMEOUT_MS = 15_000L
        const val BACKOFF_DELAY_MS = 1_500L
        const val STALL_TIMEOUT_MS = 40_000L
        private const val NUDGE_AFTER_MS = 3_000L
        private const val NUDGE_ELSEWHERE_BYTES = 2L * 1024L * 1024L
        private const val POLL_INTERVAL_MS = 600L
        private const val PROBE_BYTES = 2L * 1024L * 1024L
        private const val PROBE_ALIGN_BYTES = 1024L * 1024L
        private const val PROBE_READY_BYTES = 64L * 1024L
        private const val PROBE_NUDGE_MS = 2_000L
        private const val PROBE_GIVE_UP_MS = 8_000L
        private const val PROMOTE_MARGIN_BYTES = 256L * 1024L

        private class Playhead {
            @Volatile
            var mainAt = -1L

            @Volatile
            var probe = Window.EMPTY

            var open = 0
            var closedAt = 0L
        }

        private val playheads = LinkedHashMap<Int, Playhead>()
        private val fileRanges = java.util.concurrent.ConcurrentHashMap<Int, List<LowStorageWindow.Range>>()
        private val downloadLocks = java.util.concurrent.ConcurrentHashMap<Int, Mutex>()
        private val lastRequestedOffsets = java.util.concurrent.ConcurrentHashMap<Int, Long>()
        private val lastRequestedTimes = java.util.concurrent.ConcurrentHashMap<Int, Long>()

        private fun takePlayhead(fileId: Int): Playhead = synchronized(playheads) {
            val ph = playheads.getOrPut(fileId) { Playhead() }
            if (ph.open == 0 && System.currentTimeMillis() - ph.closedAt > 5_000L) {
                ph.mainAt = -1L
                ph.probe = Window.EMPTY
            }
            ph.open++
            if (playheads.size > 16) {
                playheads.entries.firstOrNull { it.value.open == 0 && it.key != fileId }?.let { playheads.remove(it.key) }
            }
            ph
        }

        private fun letGo(ph: Playhead) = synchronized(playheads) {
            ph.open = (ph.open - 1).coerceAtLeast(0)
            if (ph.open == 0) ph.closedAt = System.currentTimeMillis()
        }

        fun isStreaming(fileId: Int): Boolean = synchronized(playheads) { (playheads[fileId]?.open ?: 0) > 0 }
    }
}
