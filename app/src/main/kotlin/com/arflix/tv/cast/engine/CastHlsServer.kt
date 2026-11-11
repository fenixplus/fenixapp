package com.arflix.tv.cast.engine

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicReference

/**
 * High-level Cast HLS Server managing real-time fMP4 / HLS streams for Chromecast.
 * It uses TMPlayer's MkvHls engine to demux MKV streams into HLS with separate audio renditions,
 * enabling seamless Dual-Audio track switching on Chromecast.
 */
object CastHlsServer {
    private const val TAG = "CastHlsServer"

    private var server: CastServer? = null
    private var streams: CastStreams? = null
    private val currentPublished = AtomicReference<CastStreams.Published?>()
    private var currentSource: CastSource? = null
    private var currentProbe: MediaProbe? = null
    private var currentPlan: CastPlan? = null
    private var currentRoute: CastRoute? = null
    private var currentEffectiveFileName: String = "video.mp4"
    private var currentEffectiveMime: String = "video/mp4"

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @Synchronized
    fun start(bindAddress: InetAddress): CastServer {
        server?.let { existing ->
            if (existing.running) return existing
            existing.stop()
        }
        val s = CastServer(bindAddress)
        s.start()
        server = s
        streams = CastStreams(s, recovery = null) { AndroidAudioConverter() }
        CastLogger.i(TAG, "CastHlsServer running at ${bindAddress.hostAddress}:${s.boundPort}")
        return s
    }

    @Synchronized
    fun stop() {
        currentPublished.set(null)
        currentSource = null
        currentProbe = null
        currentPlan = null
        currentRoute = null
        server?.stop()
        server = null
        streams = null
    }

    fun isRunning(): Boolean = server?.running == true

    @UnstableApi
    suspend fun prepare(
        dataSourceFactory: DataSource.Factory,
        uri: Uri,
        totalLength: Long = -1L,
        fileName: String,
        mimeType: String = "",
        preferredAudioTrack: Int? = null,
        receiverModel: String? = null,
        receiverAddress: InetAddress? = null
    ): Pair<CastPlan, MediaProbe> = withContext(Dispatchers.IO) {
        val bind = LanAddress.forReceiver(receiverAddress)
            ?: throw IOException("No network address available for Chromecast")
        start(bind)

        val src = TdCastSource(dataSourceFactory, uri, totalLength)
        currentSource = src

        val isMatroska = mimeType.contains("matroska", ignoreCase = true) ||
            uri.toString().contains(".mkv", ignoreCase = true) ||
            uri.toString().contains("video/x-matroska", ignoreCase = true)
        val effectiveFileName = if (fileName.contains('.')) fileName else if (isMatroska) "$fileName.mkv" else "$fileName.mp4"
        val effectiveMime = if (isMatroska) "video/x-matroska" else mimeType
        currentEffectiveFileName = effectiveFileName
        currentEffectiveMime = effectiveMime

        val probe = MediaProbe.of(src, effectiveFileName, effectiveMime)
        currentProbe = probe

        val caps = ReceiverCaps.forModel(receiverModel)
        val plan = CastFormats.plan(
            probe,
            caps,
            preferredAudioTrack,
            canConvertAudio = AndroidAudioConverter.available(),
            canTranscodeVideo = false
        )
        currentPlan = plan
        CastLogger.i(TAG, "Prepared CastPlan for $effectiveFileName (probe=$probe): routes=${plan.routes}, audioTrack=${plan.audioTrack}")
        Pair(plan, probe)
    }

    @UnstableApi
    suspend fun publishRoute(
        route: CastRoute,
        startMs: Long
    ): CastStreams.Published = withContext(Dispatchers.IO) {
        val st = streams ?: throw IOException("CastStreams engine not started")
        val src = currentSource ?: throw IOException("CastSource not prepared")
        val probe = currentProbe ?: throw IOException("MediaProbe not prepared")

        val pub = st.publish(src, probe, route, startMs, currentEffectiveFileName)
        currentRoute = route
        currentPublished.set(pub)
        pub
    }

    @UnstableApi
    suspend fun switchRemuxAudio(
        newAudioTrack: Int,
        atMs: Long
    ): CastStreams.Published = withContext(Dispatchers.IO) {
        val probe = currentProbe ?: throw IOException("MediaProbe not available")
        val codec = probe.audio.firstOrNull { it.number == newAudioTrack }?.codec
        val canConvert = AndroidAudioConverter.available()
        val nextRoute = if (codec != null && codec in CastFormats.MP4_AUDIO) {
            CastRoute.Remux(newAudioTrack)
        } else if (canConvert) {
            CastRoute.ConvertAudio(newAudioTrack)
        } else {
            CastRoute.Remux(newAudioTrack)
        }
        publishRoute(nextRoute, atMs)
    }

    @UnstableApi
    suspend fun publish(
        dataSourceFactory: DataSource.Factory,
        uri: Uri,
        totalLength: Long = -1L,
        fileName: String,
        mimeType: String = "",
        preferredAudioTrack: Int? = null,
        receiverModel: String? = null,
        startMs: Long = 0L,
        receiverAddress: InetAddress? = null
    ): CastStreams.Published = withContext(Dispatchers.IO) {
        val (plan, _) = prepare(
            dataSourceFactory = dataSourceFactory,
            uri = uri,
            totalLength = totalLength,
            fileName = fileName,
            mimeType = mimeType,
            preferredAudioTrack = preferredAudioTrack,
            receiverModel = receiverModel,
            receiverAddress = receiverAddress
        )

        val chosenRoute = plan.routes.filterIsInstance<CastRoute.Hls>().firstOrNull()
            ?: plan.routes.firstOrNull()
            ?: CastRoute.Direct(currentEffectiveMime)

        publishRoute(chosenRoute, startMs)
    }

    fun getCurrentRenditions(): List<HlsRendition> =
        currentPublished.get()?.renditions.orEmpty()

    fun getCurrentProbe(): MediaProbe? = currentProbe

    fun getCurrentRoute(): CastRoute? = currentRoute

    fun getCurrentPublished(): CastStreams.Published? = currentPublished.get()
}
