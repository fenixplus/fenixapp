package com.arflix.tv.cast

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.mediarouter.media.MediaRouteSelector
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.MediaTrack
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.arflix.tv.cast.engine.audioTrackNumber
import javax.inject.Inject
import javax.inject.Singleton

@EntryPoint
@InstallIn(SingletonComponent::class)
interface CastManagerEntryPoint {
    fun castManager(): CastManager
}

@Singleton
class CastManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    sealed class CastState {
        data object NotAvailable : CastState()
        data object NotConnected : CastState()
        data object Connecting : CastState()
        data class Casting(val deviceName: String) : CastState()
    }

    data class CastAudioTrack(
        val id: Long,
        val name: String,
        val language: String?,
        val isSelected: Boolean
    )

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _castState = MutableStateFlow<CastState>(CastState.NotConnected)
    val castState: StateFlow<CastState> = _castState.asStateFlow()

    private val _audioTracks = MutableStateFlow<List<CastAudioTrack>>(emptyList())
    val audioTracks: StateFlow<List<CastAudioTrack>> = _audioTracks.asStateFlow()

    private var castContext: CastContext? = null
    private var currentSession: CastSession? = null
    private val wakeHelper = com.arflix.tv.util.PlaybackWakeHelper(context, "Cast")

    private var playbackEverStartedForCurrentMedia = false
    private var lastLoadTimeMs = 0L
    private var currentLoadedUrl: String? = null
    private var currentTitle: String? = null
    private var currentImageUrl: String? = null
    private var currentDurationMs: Long = 0L
    private var currentWantedAudio: com.arflix.tv.cast.engine.HlsRendition? = null
    private var pendingSeekPositionMs: Long? = null
    private var pendingSeekTimeMs: Long = 0L
    var currentMediaKey: String? = null
        private set

    private val remoteCallback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() {
            val client = getActiveClient() ?: return
            val state = client.playerState
            if (state == com.google.android.gms.cast.MediaStatus.PLAYER_STATE_PLAYING ||
                state == com.google.android.gms.cast.MediaStatus.PLAYER_STATE_BUFFERING
            ) {
                playbackEverStartedForCurrentMedia = true
            }
            if (state == com.google.android.gms.cast.MediaStatus.PLAYER_STATE_IDLE &&
                client.idleReason == com.google.android.gms.cast.MediaStatus.IDLE_REASON_FINISHED
            ) {
                CastForegroundService.stop(context)
            }
            updateAudioTracks()
        }

        override fun onMetadataUpdated() {
            updateAudioTracks()
        }
    }

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) {
            _castState.value = CastState.Connecting
        }

        override fun onSessionStarted(session: CastSession, sessionId: String) {
            currentSession = session
            attachRemoteMediaClient(session)
            _castState.value = CastState.Casting(session.castDevice?.friendlyName ?: "Chromecast")
        }

        override fun onSessionStartFailed(session: CastSession, error: Int) {
            detachRemoteMediaClient()
            currentSession = null
            _castState.value = CastState.NotConnected
        }

        override fun onSessionEnding(session: CastSession) {}

        override fun onSessionEnded(session: CastSession, error: Int) {
            detachRemoteMediaClient()
            currentSession = null
            _castState.value = CastState.NotConnected
        }

        override fun onSessionResuming(session: CastSession, sessionId: String) {
            _castState.value = CastState.Connecting
        }

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            currentSession = session
            attachRemoteMediaClient(session)
            _castState.value = CastState.Casting(session.castDevice?.friendlyName ?: "Chromecast")
        }

        override fun onSessionResumeFailed(session: CastSession, error: Int) {
            detachRemoteMediaClient()
            currentSession = null
            _castState.value = CastState.NotConnected
        }

        override fun onSessionSuspended(session: CastSession, reason: Int) {}
    }

    private fun attachRemoteMediaClient(session: CastSession) {
        val client = session.remoteMediaClient ?: return
        client.registerCallback(remoteCallback)
        wakeHelper.acquire()
        val deviceName = session.castDevice?.friendlyName ?: "TV"
        if (!currentTitle.isNullOrBlank()) {
            CastForegroundService.start(context, deviceName, currentTitle)
        }
        updateAudioTracks()
    }

    private fun detachRemoteMediaClient() {
        CastForegroundService.stop(context)
        wakeHelper.release()
        getActiveClient()?.let { client ->
            runCatching { client.unregisterCallback(remoteCallback) }
        }
        runCatching { com.arflix.tv.cast.engine.CastHlsServer.stop() }
        _audioTracks.value = emptyList()
        currentLoadedUrl = null
        currentMediaKey = null
        currentTitle = null
        currentImageUrl = null
        currentDurationMs = 0L
        currentWantedAudio = null
        pendingSeekPositionMs = null
        pendingSeekTimeMs = 0L
        playbackEverStartedForCurrentMedia = false
    }

    fun initialize(isMobile: Boolean) {
        if (!isMobile) {
            _castState.value = CastState.NotAvailable
            return
        }
        if (castContext != null) return
        val executor = ContextCompat.getMainExecutor(context)
        try {
            CastContext.getSharedInstance(context, executor)
                .addOnSuccessListener(executor) { ctx ->
                    castContext = ctx
                    ctx.sessionManager.addSessionManagerListener(sessionListener, CastSession::class.java)
                    val active = ctx.sessionManager.currentCastSession
                    if (active != null) {
                        currentSession = active
                        attachRemoteMediaClient(active)
                        _castState.value = CastState.Casting(active.castDevice?.friendlyName ?: "Chromecast")
                    }
                }
                .addOnFailureListener(executor) {
                    _castState.value = CastState.NotAvailable
                }
        } catch (_: Exception) {
            _castState.value = CastState.NotAvailable
        }
    }

    private fun getActiveClient(): RemoteMediaClient? =
        (currentSession ?: castContext?.sessionManager?.currentCastSession)?.remoteMediaClient

    fun sendKeepAlive() {
        val client = getActiveClient() ?: return
        runCatching {
            client.requestStatus()
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun loadMedia(
        url: String,
        title: String,
        imageUrl: String?,
        mimeType: String,
        positionMs: Long,
        durationMs: Long = 0L,
        mediaKey: String? = null,
        dataSourceFactory: androidx.media3.datasource.DataSource.Factory? = null,
        fileName: String? = null,
        fileSizeBytes: Long? = null,
        preferredAudioTrack: Int? = null,
        preferredAudioLanguage: String? = null
    ) {
        val client = getActiveClient()
        if (client == null) {
            android.util.Log.w("CastManager", "loadMedia: no active RemoteMediaClient found")
            return
        }

        playbackEverStartedForCurrentMedia = false
        lastLoadTimeMs = System.currentTimeMillis()
        currentLoadedUrl = url
        currentMediaKey = mediaKey
        currentTitle = title
        currentImageUrl = imageUrl
        currentDurationMs = durationMs
        val deviceName = currentSession?.castDevice?.friendlyName ?: "TV"
        CastForegroundService.start(context, deviceName, title)

        val isMatroska = mimeType.contains("matroska", ignoreCase = true) ||
            url.endsWith(".mkv", ignoreCase = true) ||
            url.contains(".mkv?", ignoreCase = true) ||
            url.startsWith("tdfile://", ignoreCase = true)

        val shouldPublishViaCastServer = (isMatroska ||
            url.startsWith("tdfile://", ignoreCase = true) ||
            url.startsWith("file://", ignoreCase = true)) && dataSourceFactory != null

        if (shouldPublishViaCastServer && dataSourceFactory != null) {
            scope.launch {
                try {
                    val receiverAddress = currentSession?.castDevice?.inetAddress
                    val receiverModel = currentSession?.castDevice?.modelName
                    android.util.Log.i(
                        "CastManager",
                        "Probing via TMPlayer engine for $title ($url) to receiver $receiverModel at $receiverAddress"
                    )

                    val (plan, probe) = com.arflix.tv.cast.engine.CastHlsServer.prepare(
                        dataSourceFactory = dataSourceFactory,
                        uri = android.net.Uri.parse(url),
                        totalLength = fileSizeBytes ?: -1L,
                        fileName = fileName ?: "video.mkv",
                        mimeType = mimeType,
                        preferredAudioTrack = preferredAudioTrack,
                        receiverModel = receiverModel,
                        receiverAddress = receiverAddress
                    )

                    android.util.Log.i(
                        "CastManager",
                        "CastPlan computed with ${plan.routes.size} routes: ${plan.routes}, preferredAudio=${plan.audioTrack}"
                    )

                    for ((index, route) in plan.routes.withIndex()) {
                        android.util.Log.i("CastManager", "Attempting route #$index: $route")
                        val published = com.arflix.tv.cast.engine.CastHlsServer.publishRoute(
                            route = route,
                            startMs = positionMs
                        )

                        if (published.hls) {
                            updateAudioTracksFromRenditions(published.renditions, preferredAudioLanguage)
                        } else {
                            val trackNum = route.audioTrackNumber
                            updateAudioTracksFromProbe(probe, trackNum, preferredAudioLanguage)
                        }

                        val receiverStartMs = if (published.seekable) positionMs else 0L
                        val accepted = kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { cont ->
                            doLoadRemote(
                                client = client,
                                targetUrl = published.url,
                                title = title,
                                imageUrl = imageUrl,
                                targetMime = published.mime,
                                positionMs = receiverStartMs,
                                durationMs = if (published.durationMs > 0L) published.durationMs else durationMs,
                                isHls = published.hls
                            ) { ok ->
                                if (cont.isActive) cont.resumeWith(Result.success(ok))
                            }
                        }

                        if (accepted) {
                            android.util.Log.i("CastManager", "✅ Route #$index (${route::class.simpleName}) accepted by Chromecast!")
                            return@launch
                        } else {
                            android.util.Log.w(
                                "CastManager",
                                "⚠️ Route #$index (${route::class.simpleName}) refused by Chromecast, trying next fallback route..."
                            )
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.w(
                        "CastManager",
                        "CastHlsServer fallback loop failed, falling back to direct load: ${e.message}",
                        e
                    )
                }

                // Fallback to direct load only for non-tdfile URIs
                if (!url.startsWith("tdfile://", ignoreCase = true)) {
                    doLoadRemote(
                        client = client,
                        targetUrl = url,
                        title = title,
                        imageUrl = imageUrl,
                        targetMime = mimeType,
                        positionMs = positionMs,
                        durationMs = durationMs,
                        isHls = false
                    )
                } else {
                    android.util.Log.e(
                        "CastManager",
                        "Cannot load internal tdfile URI directly on Chromecast after all routes refused: $url"
                    )
                }
            }
            return
        }

        doLoadRemote(
            client = client,
            targetUrl = url,
            title = title,
            imageUrl = imageUrl,
            targetMime = mimeType,
            positionMs = positionMs,
            durationMs = durationMs,
            isHls = false
        )
    }

    private fun doLoadRemote(
        client: RemoteMediaClient,
        targetUrl: String,
        title: String,
        imageUrl: String?,
        targetMime: String,
        positionMs: Long,
        durationMs: Long,
        isHls: Boolean,
        onResult: ((Boolean) -> Unit)? = null
    ) {
        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, title)
            if (!imageUrl.isNullOrBlank()) {
                addImage(com.google.android.gms.common.images.WebImage(android.net.Uri.parse(imageUrl)))
            }
        }

        val mediaInfoBuilder = MediaInfo.Builder(targetUrl)
            .setContentUrl(targetUrl)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(targetMime)
            .setMetadata(metadata)

        if (isHls) {
            mediaInfoBuilder.setHlsSegmentFormat(com.google.android.gms.cast.HlsSegmentFormat.FMP4)
            mediaInfoBuilder.setHlsVideoSegmentFormat(com.google.android.gms.cast.HlsVideoSegmentFormat.FMP4)
        }

        if (durationMs > 0L) {
            mediaInfoBuilder.setStreamDuration(durationMs)
        }
        val mediaInfo = mediaInfoBuilder.build()

        val requestBuilder = MediaLoadRequestData.Builder()
            .setMediaInfo(mediaInfo)
            .setAutoplay(true)

        if (positionMs > 0L && (durationMs <= 0L || positionMs < durationMs)) {
            requestBuilder.setCurrentTime(positionMs)
        }
        val request = requestBuilder.build()

        android.util.Log.i(
            "CastManager",
            "🚀 Loading on Chromecast: url=$targetUrl, mime=$targetMime, isHls=$isHls, pos=${positionMs}ms, dur=${durationMs}ms"
        )
        client.load(request).setResultCallback { result ->
            val success = result.status.isSuccess
            if (!success) {
                android.util.Log.w(
                    "CastManager",
                    "❌ Chromecast refused load: ${result.status} (code=${result.status.statusCode})"
                )
            } else {
                android.util.Log.i("CastManager", "✅ Chromecast accepted load for '$title'")
                updateAudioTracks()
            }
            onResult?.invoke(success)
        }
    }

    private fun updateAudioTracksFromRenditions(
        renditions: List<com.arflix.tv.cast.engine.HlsRendition>,
        preferredAudioLanguage: String?
    ) {
        val audioRenditions = renditions.filter { it.kind == com.arflix.tv.cast.engine.HlsRendition.Kind.Audio }
        if (audioRenditions.isEmpty()) return

        val prefClean = preferredAudioLanguage?.trim()?.lowercase()
        val defaultRendition = if (!prefClean.isNullOrBlank() && prefClean != "none") {
            audioRenditions.firstOrNull { r ->
                val l = r.language?.lowercase()
                val n = r.name.lowercase()
                l == prefClean || n.contains(prefClean) ||
                    (prefClean in listOf("pt", "por", "portuguese", "português", "pt-br") &&
                        (l in listOf("pt", "por", "pt-br") || n.contains("port") || n.contains("dub")))
            } ?: audioRenditions.firstOrNull { it.isDefault } ?: audioRenditions.first()
        } else {
            audioRenditions.firstOrNull { it.isDefault } ?: audioRenditions.first()
        }
        currentWantedAudio = defaultRendition

        _audioTracks.value = audioRenditions.mapIndexed { idx, r ->
            val id = (r.trackNumber ?: (idx + 1)).toLong()
            val label = listOfNotNull(
                r.name.takeIf { it.isNotBlank() },
                r.language
            ).distinct().joinToString(" · ").ifBlank { "Áudio $id" }
            CastAudioTrack(
                id = id,
                name = label + if (r.converted) " (AAC)" else "",
                language = r.language,
                isSelected = (r == defaultRendition)
            )
        }
    }

    private fun updateAudioTracksFromProbe(
        probe: com.arflix.tv.cast.engine.MediaProbe,
        selectedTrackNumber: Int?,
        preferredAudioLanguage: String? = null
    ) {
        if (probe.audio.isEmpty()) return

        val activeNumber = selectedTrackNumber ?: run {
            val prefClean = preferredAudioLanguage?.trim()?.lowercase()
            if (!prefClean.isNullOrBlank() && prefClean != "none") {
                probe.audio.firstOrNull { t ->
                    val l = t.language?.lowercase()
                    val n = t.name?.lowercase().orEmpty()
                    l == prefClean || n.contains(prefClean) ||
                        (prefClean in listOf("pt", "por", "portuguese", "português", "pt-br") &&
                            (l in listOf("pt", "por", "pt-br") || n.contains("port") || n.contains("dub")))
                }?.number
            } else null
        } ?: probe.audio.firstOrNull { it.isDefault }?.number ?: probe.audio.first().number

        _audioTracks.value = probe.audio.map { t ->
            val label = listOfNotNull(
                t.name?.takeIf { it.isNotBlank() },
                t.language?.takeIf { it.isNotBlank() }
            ).distinct().joinToString(" · ").ifBlank { "Áudio ${t.number}" }
            val codecLabel = if (t.codec.isNotBlank()) " (${t.codec.uppercase()})" else ""
            CastAudioTrack(
                id = t.number.toLong(),
                name = "$label$codecLabel",
                language = t.language,
                isSelected = (t.number == activeNumber)
            )
        }
    }

    fun play() {
        getActiveClient()?.play()
    }

    fun pause() {
        getActiveClient()?.pause()
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun seekTo(positionMs: Long) {
        val pub = com.arflix.tv.cast.engine.CastHlsServer.getCurrentPublished()
        val currentRoute = com.arflix.tv.cast.engine.CastHlsServer.getCurrentRoute()
        pendingSeekPositionMs = positionMs
        pendingSeekTimeMs = System.currentTimeMillis()

        if (pub != null && currentRoute != null && !pub.seekable) {
            scope.launch {
                try {
                    val client = getActiveClient() ?: return@launch
                    val newPub = com.arflix.tv.cast.engine.CastHlsServer.publishRoute(currentRoute, positionMs)
                    val dur = getApproximateDuration()
                    doLoadRemote(
                        client = client,
                        targetUrl = newPub.url,
                        title = currentTitle ?: "Video",
                        imageUrl = currentImageUrl,
                        targetMime = newPub.mime,
                        positionMs = 0L,
                        durationMs = dur,
                        isHls = newPub.hls
                    )
                } catch (e: Exception) {
                    android.util.Log.e("CastManager", "Failed to seek via remux reload: ${e.message}", e)
                }
            }
        } else {
            getActiveClient()?.seek(
                MediaSeekOptions.Builder().setPosition(positionMs).build()
            )
        }
    }

    fun skipForward(amountMs: Long = 10_000L) {
        seekTo(getApproximatePosition() + amountMs)
    }

    fun skipBack(amountMs: Long = 10_000L) {
        seekTo((getApproximatePosition() - amountMs).coerceAtLeast(0L))
    }

    fun getApproximatePosition(): Long {
        val pending = pendingSeekPositionMs
        if (pending != null) {
            val elapsed = System.currentTimeMillis() - pendingSeekTimeMs
            if (elapsed < 3_000L) {
                val client = getActiveClient()
                val basePos = client?.approximateStreamPosition ?: 0L
                val pub = com.arflix.tv.cast.engine.CastHlsServer.getCurrentPublished()
                val realPos = if (pub != null && !pub.seekable) (basePos + pub.offsetMs) else basePos
                if (kotlin.math.abs(realPos - pending) <= 15_000L && basePos > 500L) {
                    pendingSeekPositionMs = null
                    return realPos.coerceAtLeast(0L)
                }
                return pending.coerceAtLeast(0L)
            } else {
                pendingSeekPositionMs = null
            }
        }
        if (!playbackEverStartedForCurrentMedia) return 0L
        val client = getActiveClient() ?: return 0L
        val basePos = client.approximateStreamPosition
        val pub = com.arflix.tv.cast.engine.CastHlsServer.getCurrentPublished()
        return if (pub != null && !pub.seekable) {
            (basePos + pub.offsetMs).coerceAtLeast(0L)
        } else {
            basePos.coerceAtLeast(0L)
        }
    }

    fun getApproximateDuration(): Long {
        val probeDur = com.arflix.tv.cast.engine.CastHlsServer.getCurrentProbe()?.durationMs?.takeIf { it > 0 }
        val pubDur = com.arflix.tv.cast.engine.CastHlsServer.getCurrentPublished()?.durationMs?.takeIf { it > 0 }
        val savedDur = currentDurationMs.takeIf { it > 0 }
        val clientDur = getActiveClient()?.mediaInfo?.streamDuration?.takeIf { it > 0 }
        return probeDur ?: pubDur ?: savedDur ?: clientDur ?: 0L
    }

    fun isRemotePlaying(): Boolean =
        getActiveClient()?.isPlaying == true

    fun isRemoteBuffering(): Boolean {
        val client = getActiveClient() ?: return false
        val state = client.playerState
        val idleReason = client.idleReason
        if (state == com.google.android.gms.cast.MediaStatus.PLAYER_STATE_IDLE &&
            (idleReason == com.google.android.gms.cast.MediaStatus.IDLE_REASON_ERROR ||
             idleReason == com.google.android.gms.cast.MediaStatus.IDLE_REASON_CANCELED ||
             idleReason == com.google.android.gms.cast.MediaStatus.IDLE_REASON_INTERRUPTED)
        ) {
            return false
        }
        if (!playbackEverStartedForCurrentMedia && System.currentTimeMillis() - lastLoadTimeMs < 8_000L) {
            return true
        }
        return state == com.google.android.gms.cast.MediaStatus.PLAYER_STATE_BUFFERING ||
            state == com.google.android.gms.cast.MediaStatus.PLAYER_STATE_LOADING
    }

    fun isRemoteBufferingOrPlaying(): Boolean {
        val client = getActiveClient() ?: return false
        val state = client.playerState
        return state == com.google.android.gms.cast.MediaStatus.PLAYER_STATE_PLAYING ||
            state == com.google.android.gms.cast.MediaStatus.PLAYER_STATE_BUFFERING ||
            client.isPlaying
    }

    fun isPlaybackEnded(): Boolean {
        val client = getActiveClient() ?: return false
        val state = client.playerState
        val idleReason = client.idleReason
        if (!playbackEverStartedForCurrentMedia) return false
        if (System.currentTimeMillis() - lastLoadTimeMs < 10_000L) return false
        return state == com.google.android.gms.cast.MediaStatus.PLAYER_STATE_IDLE &&
            idleReason == com.google.android.gms.cast.MediaStatus.IDLE_REASON_FINISHED
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun stop(onlyIfUrl: String? = null) {
        val client = getActiveClient()
        val remoteContentId = client?.mediaInfo?.contentId ?: currentLoadedUrl
        if (onlyIfUrl != null && remoteContentId != null && remoteContentId != onlyIfUrl) {
            android.util.Log.i(
                "CastManager",
                "Ignorando stop() da tela anterior: receptor já está em outro contentId ($remoteContentId != $onlyIfUrl)"
            )
            return
        }
        playbackEverStartedForCurrentMedia = false
        currentLoadedUrl = null
        currentMediaKey = null
        currentTitle = null
        currentImageUrl = null
        currentWantedAudio = null
        com.arflix.tv.cast.engine.CastHlsServer.stop()
        client?.stop()
    }

    fun disconnect() {
        CastForegroundService.stop(context)
        castContext?.sessionManager?.endCurrentSession(true)
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun updateAudioTracks() {
        val client = getActiveClient() ?: run {
            _audioTracks.value = emptyList()
            return
        }
        val mediaStatus = client.mediaStatus
        val mediaInfo = mediaStatus?.mediaInfo ?: client.mediaInfo
        val activeTrackIds = mediaStatus?.activeTrackIds?.toSet() ?: emptySet()
        val renditions = com.arflix.tv.cast.engine.CastHlsServer.getCurrentRenditions()

        val rawRemoteTracks = mediaInfo?.mediaTracks.orEmpty().filter { it.type == MediaTrack.TYPE_AUDIO }

        if (renditions.isNotEmpty()) {
            val remoteTracks = mediaInfo?.mediaTracks.orEmpty().map { t ->
                com.arflix.tv.cast.engine.RemoteTrack(
                    id = t.id,
                    type = when (t.type) {
                        MediaTrack.TYPE_AUDIO -> com.arflix.tv.cast.engine.RemoteTrack.Type.Audio
                        MediaTrack.TYPE_VIDEO -> com.arflix.tv.cast.engine.RemoteTrack.Type.Video
                        else -> com.arflix.tv.cast.engine.RemoteTrack.Type.Text
                    },
                    name = t.name,
                    language = t.language,
                    contentId = t.contentId
                )
            }
            val offers = com.arflix.tv.cast.engine.HlsTrackMatch.audioOffers(
                renditions = renditions,
                remote = remoteTracks,
                active = activeTrackIds.toList(),
                wanted = currentWantedAudio
            )
            val mapped = offers.map { offer ->
                val r = offer.rendition
                val id = (r.trackNumber ?: 1).toLong()
                val label = listOfNotNull(
                    r.name.takeIf { it.isNotBlank() },
                    r.language
                ).distinct().joinToString(" · ").ifBlank { "Áudio $id" }
                CastAudioTrack(
                    id = id,
                    name = label + if (r.converted) " (AAC)" else "",
                    language = r.language,
                    isSelected = offer.selected
                )
            }
            if (mapped.isNotEmpty()) {
                _audioTracks.value = mapped
                return
            }
        }

        // Mode 2: Remux or ConvertAudio route on CastHlsServer
        val currentRoute = com.arflix.tv.cast.engine.CastHlsServer.getCurrentRoute()
        val probe = com.arflix.tv.cast.engine.CastHlsServer.getCurrentProbe()
        if (com.arflix.tv.cast.engine.CastHlsServer.isRunning() && probe != null &&
            (currentRoute is com.arflix.tv.cast.engine.CastRoute.Remux || currentRoute is com.arflix.tv.cast.engine.CastRoute.ConvertAudio)
        ) {
            val selectedNumber = currentRoute.audioTrackNumber
            updateAudioTracksFromProbe(probe, selectedNumber)
            return
        }

        val tracks = rawRemoteTracks.map { t ->
            val label = t.name?.takeIf { it.isNotBlank() }
                ?: t.language?.takeIf { it.isNotBlank() }
                ?: "Audio ${t.id}"
            CastAudioTrack(
                id = t.id,
                name = label,
                language = t.language,
                isSelected = activeTrackIds.contains(t.id)
            )
        }
        _audioTracks.value = tracks
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun setActiveAudioTrack(trackId: Long) {
        val client = getActiveClient() ?: return
        val renditions = com.arflix.tv.cast.engine.CastHlsServer.getCurrentRenditions()
        val mediaStatus = client.mediaStatus
        val mediaInfo = mediaStatus?.mediaInfo ?: client.mediaInfo
        val activeTrackIds = mediaStatus?.activeTrackIds?.toList().orEmpty()

        // Guard: do not attempt to switch tracks while receiver is idle or loading
        val playerState = mediaStatus?.playerState ?: com.google.android.gms.cast.MediaStatus.PLAYER_STATE_UNKNOWN
        if (playerState == com.google.android.gms.cast.MediaStatus.PLAYER_STATE_IDLE ||
            playerState == com.google.android.gms.cast.MediaStatus.PLAYER_STATE_UNKNOWN
        ) {
            android.util.Log.i("CastManager", "setActiveAudioTrack: ignored while receiver is not active (state=$playerState)")
            return
        }

        val trackNumber = trackId.toInt()

        if (renditions.isNotEmpty()) {
            val remoteTracks = mediaInfo?.mediaTracks.orEmpty().map { t ->
                com.arflix.tv.cast.engine.RemoteTrack(
                    id = t.id,
                    type = when (t.type) {
                        MediaTrack.TYPE_AUDIO -> com.arflix.tv.cast.engine.RemoteTrack.Type.Audio
                        MediaTrack.TYPE_VIDEO -> com.arflix.tv.cast.engine.RemoteTrack.Type.Video
                        else -> com.arflix.tv.cast.engine.RemoteTrack.Type.Text
                    },
                    name = t.name,
                    language = t.language,
                    contentId = t.contentId
                )
            }
            if (remoteTracks.isEmpty()) {
                android.util.Log.i("CastManager", "setActiveAudioTrack: receiver has not reported tracks yet; deferred track $trackNumber")
                currentWantedAudio = renditions.firstOrNull { it.trackNumber == trackNumber }
                return
            }
            val rendition = com.arflix.tv.cast.engine.HlsTrackMatch.renditionFor(trackNumber, renditions, remoteTracks)
            if (rendition != null) {
                currentWantedAudio = rendition
                val map = com.arflix.tv.cast.engine.HlsTrackMatch.match(renditions, remoteTracks)
                val targetRemoteId = map[rendition]
                if (targetRemoteId != null) {
                    val activeIds = com.arflix.tv.cast.engine.HlsTrackMatch.activeIds(
                        map = map,
                        remote = remoteTracks,
                        current = activeTrackIds,
                        audio = rendition,
                        text = null
                    )
                    android.util.Log.i(
                        "CastManager",
                        "Switching Chromecast audio track via HLS to remote ID $targetRemoteId (activeIds=$activeIds)"
                    )
                    client.setActiveMediaTracks(activeIds.toLongArray()).setResultCallback { r ->
                        if (r.status.isSuccess) {
                            android.util.Log.i("CastManager", "Chromecast switched audio track successfully")
                            updateAudioTracks()
                        } else {
                            android.util.Log.w("CastManager", "Chromecast refused setActiveMediaTracks: ${r.status}")
                        }
                    }
                    return
                }
            }
            // For HLS streams with renditions, never fall through to direct stream track ID
            return
        }

        // Mode 2: Remux or ConvertAudio route on CastHlsServer
        val currentRoute = com.arflix.tv.cast.engine.CastHlsServer.getCurrentRoute()
        val probe = com.arflix.tv.cast.engine.CastHlsServer.getCurrentProbe()
        if (com.arflix.tv.cast.engine.CastHlsServer.isRunning() && probe != null &&
            (currentRoute is com.arflix.tv.cast.engine.CastRoute.Remux || currentRoute is com.arflix.tv.cast.engine.CastRoute.ConvertAudio)
        ) {
            val currentPos = getApproximatePosition()
            val currentDuration = getApproximateDuration()
            pendingSeekPositionMs = currentPos
            pendingSeekTimeMs = System.currentTimeMillis()

            android.util.Log.i(
                "CastManager",
                "Switching Chromecast audio track via Remux reload to track $trackNumber at pos=${currentPos}ms"
            )
            scope.launch {
                try {
                    val published = com.arflix.tv.cast.engine.CastHlsServer.switchRemuxAudio(trackNumber, currentPos)
                    updateAudioTracksFromProbe(probe, trackNumber, null)
                    doLoadRemote(
                        client = client,
                        targetUrl = published.url,
                        title = currentTitle ?: "Video",
                        imageUrl = currentImageUrl,
                        targetMime = published.mime,
                        positionMs = 0L,
                        durationMs = currentDuration,
                        isHls = false
                    )
                } catch (e: Exception) {
                    android.util.Log.e("CastManager", "Failed to switch remux audio track: ${e.message}", e)
                }
            }
            return
        }

        // Fallback for direct stream:
        val currentActive = mediaStatus?.activeTrackIds?.toList().orEmpty()
        val nonAudio = mediaInfo?.mediaTracks.orEmpty()
            .filter { it.type != MediaTrack.TYPE_AUDIO }
            .map { it.id }
            .toSet()
        val activeSubtitles = currentActive.filter { nonAudio.contains(it) }
        val newIds = (activeSubtitles + trackId).toLongArray()
        android.util.Log.i("CastManager", "Switching Chromecast direct audio track to $trackId (active total: ${newIds.toList()})")
        client.setActiveMediaTracks(newIds).setResultCallback { result ->
            if (!result.status.isSuccess) {
                android.util.Log.w("CastManager", "Failed to switch audio track on Chromecast: ${result.status}")
            } else {
                android.util.Log.i("CastManager", "Chromecast switched audio track to $trackId successfully")
                updateAudioTracks()
            }
        }
    }

    fun selectAudioTrack(trackLanguage: String?, trackLabel: String?, trackIndex: Int) {
        val remoteTracks = _audioTracks.value
        if (remoteTracks.isEmpty()) return
        val targetLang = trackLanguage?.trim()?.lowercase()
        val targetLabel = trackLabel?.trim()?.lowercase()

        val match = remoteTracks.firstOrNull { remote ->
            val rLang = remote.language?.trim()?.lowercase()
            val rName = remote.name.trim().lowercase()
            (!targetLang.isNullOrBlank() && (rLang == targetLang || (rLang != null && (targetLang.startsWith(rLang) || rLang.startsWith(targetLang))))) ||
                (!targetLabel.isNullOrBlank() && (rName.contains(targetLabel) || targetLabel.contains(rName)))
        } ?: remoteTracks.getOrNull(trackIndex) ?: remoteTracks.firstOrNull()

        if (match != null) {
            android.util.Log.i("CastManager", "Matched audio track ($trackLanguage, $trackLabel) to remote track id ${match.id} (${match.name})")
            setActiveAudioTrack(match.id)
        }
    }

    fun selectAudioTrackByLanguage(lang: String): Boolean {
        val tracks = _audioTracks.value
        if (tracks.isEmpty()) return false
        val clean = lang.trim().lowercase()
        val match = tracks.firstOrNull { t ->
            val l = t.language?.lowercase()
            val n = t.name.lowercase()
            l == clean || n.contains(clean) ||
                (clean in listOf("pt", "por", "portuguese", "português", "pt-br") &&
                    (l in listOf("pt", "por", "pt-br") || n.contains("port") || n.contains("dub")))
        } ?: return false
        setActiveAudioTrack(match.id)
        return true
    }

    fun getRouteSelector(): MediaRouteSelector =
        MediaRouteSelector.Builder()
            .addControlCategory(
                CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            )
            .build()
}
