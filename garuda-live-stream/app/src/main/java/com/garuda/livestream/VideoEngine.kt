package com.garuda.livestream

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.HardwareVideoEncoderFactory
import org.webrtc.VideoEncoderFactory
import org.webrtc.VideoEncoder
import org.webrtc.VideoCodecInfo
import org.webrtc.EglBase
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoFrame
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.json.JSONArray
import org.json.JSONObject
import javax.net.SocketFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class StreamStats(
    val fps: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val rtspKbps: Int = 0,
    val uploadKbps: Int = 0,
    val rttMs: Int = 0,
    val packetLoss: Long = 0,
    val peerState: String = "NEW"
)

/** RTSP decode is delegated to Android's MediaCodec decoder; WebRTC uses Android's
 * hardware encoder where the device advertises one. Frames stay on GPU textures. */
class VideoEngine(
    context: Context,
    private val onRtspState: (connected: Boolean, message: String) -> Unit,
    private val onStats: (StreamStats) -> Unit
) {
    private val appContext = context.applicationContext
    private val initialized = PeerConnectionFactory.initialize(
        PeerConnectionFactory.InitializationOptions.builder(appContext).createInitializationOptions()
    )
    private val mainHandler = Handler(Looper.getMainLooper())
    private val egl = EglBase.create()
    fun eglContext() = egl.eglBaseContext
    private val surfaceHelper = SurfaceTextureHelper.create("GarudaVideoFrames", egl.eglBaseContext)
    private val videoSource: VideoSource
    val videoTrack: VideoTrack
    val player: ExoPlayer = ExoPlayer.Builder(appContext).build()
    private val outputSurface: Surface
    private val peers = ConcurrentHashMap<String, Peer>()
    private lateinit var factoryHolder: PeerConnectionFactory
    private var encoderAvailable = false
    fun canPublishVideo() = encoderAvailable
    private val frameCount = AtomicLong()
    private var currentHost: String? = null
    private var currentUri: String? = null
    private var socketFactory: SocketFactory? = null
    private var shouldReconnect = false
    private var connected = false
    private var lastBytesIn = TrafficBytes.rx()
    private var lastBytesOut = TrafficBytes.tx()
    private var lastStatsAt = SystemClock.elapsedRealtime()
    private var lastFrameCount = 0L
    private var peerLabel = "NEW"
    private var latestVideoSize = VideoSize.UNKNOWN
    private val retry = Runnable { retryRtsp() }

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext).createInitializationOptions()
        )
        val hardware = HardwareVideoEncoderFactory(egl.eglBaseContext, true, false)
        val h264Factory = object : VideoEncoderFactory {
            override fun getSupportedCodecs(): Array<VideoCodecInfo> = hardware.supportedCodecs.filter { it.name.equals("H264", true) }.toTypedArray()
            override fun createEncoder(info: VideoCodecInfo): VideoEncoder? = if (info.name.equals("H264", true)) hardware.createEncoder(info) else null
        }
        encoderAvailable = h264Factory.supportedCodecs.isNotEmpty()
        val factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(h264Factory)
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
        factoryHolder = factory
        videoSource = factory.createVideoSource(false)
        videoSource.capturerObserver.onCapturerStarted(true)
        surfaceHelper.setTextureSize(1920, 1080)
        surfaceHelper.startListening { frame: VideoFrame ->
            frameCount.incrementAndGet()
            videoSource.capturerObserver.onFrameCaptured(frame)
        }
        outputSurface = Surface(surfaceHelper.surfaceTexture)
        player.setVideoSurface(outputSurface)
        player.volume = 0f
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
        videoTrack = factory.createVideoTrack("garuda-live-video", videoSource)
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    connected = true
                    onRtspState(true, "RTSP connected")
                } else if (playbackState == Player.STATE_BUFFERING && connected) {
                    connected = false
                    onRtspState(false, "RTSP reconnecting")
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                connected = false
                onRtspState(false, "RTSP CONNECTION LOST · reconnecting")
                scheduleRetry()
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                latestVideoSize = videoSize
                if (videoSize.width > 0 && videoSize.height > 0) {
                    surfaceHelper.setTextureSize(videoSize.width, videoSize.height)
                }
            }
        })
    }

    fun startRtsp(uri: String, host: String, socketFactory: SocketFactory?) {
        currentUri = uri
        currentHost = host
        this.socketFactory = socketFactory
        shouldReconnect = true
        mainHandler.removeCallbacks(retry)
        retryRtsp()
    }

    fun stopRtsp() {
        shouldReconnect = false
        mainHandler.removeCallbacks(retry)
        player.stop()
        player.clearMediaItems()
        connected = false
        onRtspState(false, "RTSP disconnected")
    }

    private fun retryRtsp() {
        if (!shouldReconnect) return
        val uri = currentUri ?: return
        runCatching {
            val factory = RtspMediaSource.Factory().setForceUseRtpTcp(true).setTimeoutMs(5_000)
            socketFactory?.let(factory::setSocketFactory)
            player.setMediaSource(factory.createMediaSource(MediaItem.fromUri(Uri.parse(uri))))
            player.prepare()
            player.playWhenReady = true
            connected = false
        }.onFailure {
            connected = false
            onRtspState(false, "RTSP CONNECTION LOST · reconnecting")
            scheduleRetry()
        }
    }

    private fun scheduleRetry() {
        if (shouldReconnect) {
            mainHandler.removeCallbacks(retry)
            mainHandler.postDelayed(retry, 3_000)
        }
    }

    fun isRtspConnected() = connected

    fun addViewer(viewerId: String, offerSdp: String, iceJson: JSONArray, send: (JSONObject) -> Unit) {
        closePeer(viewerId)
        val iceServers = readIceServers(iceJson)
        val configuration = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val peer = factoryHolder.createPeerConnection(configuration, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                peerLabel = state.name
                if (state == PeerConnection.IceConnectionState.FAILED || state == PeerConnection.IceConnectionState.CLOSED) mainHandler.post { closePeer(viewerId) }
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
            override fun onIceCandidate(candidate: org.webrtc.IceCandidate) {
                send(signal(viewerId, JSONObject().apply {
                    put("type", "candidate")
                    put("candidate", candidate.sdp)
                    put("sdpMid", candidate.sdpMid)
                    put("sdpMLineIndex", candidate.sdpMLineIndex)
                }))
            }
            override fun onIceCandidatesRemoved(candidates: Array<out org.webrtc.IceCandidate>) = Unit
            override fun onAddStream(stream: org.webrtc.MediaStream) = Unit
            override fun onRemoveStream(stream: org.webrtc.MediaStream) = Unit
            override fun onDataChannel(channel: org.webrtc.DataChannel) = Unit
            override fun onRenegotiationNeeded() = Unit
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out org.webrtc.MediaStream>) = Unit
            override fun onTrack(transceiver: RtpTransceiver) = Unit
        }) ?: run {
            onRtspState(connected, "Could not create a WebRTC connection")
            return
        }
        peer.addTrack(videoTrack, listOf("garuda-live"))
        val state = Peer(peer, send)
        peers[viewerId] = state
        val description = SessionDescription(SessionDescription.Type.OFFER, offerSdp)
        peer.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                state.remoteDescriptionSet = true
                state.pendingCandidates.forEach(peer::addIceCandidate)
                state.pendingCandidates.clear()
                peer.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(answer: SessionDescription) {
                        peer.setLocalDescription(object : SdpObserver {
                            override fun onSetSuccess() {
                                send(signal(viewerId, JSONObject().apply {
                                    put("type", "answer")
                                    put("sdp", answer.description)
                                }))
                            }
                            override fun onSetFailure(error: String) { onRtspState(connected, "WebRTC answer failed: $error") }
                            override fun onCreateSuccess(description: SessionDescription) = Unit
                            override fun onCreateFailure(error: String) = Unit
                        }, answer)
                    }
                    override fun onCreateFailure(error: String) { onRtspState(connected, "WebRTC negotiation failed: $error") }
                    override fun onSetSuccess() = Unit
                    override fun onSetFailure(error: String) = Unit
                }, MediaConstraints())
            }
            override fun onSetFailure(error: String) { onRtspState(connected, "WebRTC offer failed: $error") }
            override fun onCreateSuccess(description: SessionDescription) = Unit
            override fun onCreateFailure(error: String) = Unit
        }, description)
    }

    fun addRemoteCandidate(viewerId: String, candidate: JSONObject) {
        val state = peers[viewerId] ?: return
        val item = org.webrtc.IceCandidate(
            candidate.optString("sdpMid").ifEmpty { null },
            candidate.optInt("sdpMLineIndex"),
            candidate.optString("candidate")
        )
        if (state.remoteDescriptionSet) state.pc.addIceCandidate(item) else state.pendingCandidates.add(item)
    }

    fun closePeer(viewerId: String) {
        peers.remove(viewerId)?.let { runCatching { it.pc.close(); it.pc.dispose() } }
    }

    fun closeAllPeers() { peers.keys.toList().forEach(::closePeer) }

    fun sampleStats() {
        val now = SystemClock.elapsedRealtime()
        val seconds = ((now - lastStatsAt).coerceAtLeast(1) / 1000.0)
        val frames = frameCount.get()
        val rx = TrafficBytes.rx()
        val tx = TrafficBytes.tx()
        val rxRate = if (rx >= 0 && lastBytesIn >= 0) (((rx - lastBytesIn) * 8) / seconds / 1000).toInt().coerceAtLeast(0) else 0
        val txRate = if (tx >= 0 && lastBytesOut >= 0) (((tx - lastBytesOut) * 8) / seconds / 1000).toInt().coerceAtLeast(0) else 0
        val fps = ((frames - lastFrameCount) / seconds).toInt().coerceAtLeast(0)
        lastStatsAt = now
        lastFrameCount = frames
        lastBytesIn = rx
        lastBytesOut = tx
        val format = player.videoFormat
        val width = format?.width?.takeIf { it > 0 } ?: latestVideoSize.width
        val height = format?.height?.takeIf { it > 0 } ?: latestVideoSize.height
        onStats(StreamStats(fps, width, height, rxRate, txRate, peerRttMs, peerPacketLoss, peerLabel))
        peers.values.firstOrNull()?.pc?.getStats { report ->
            var rtt = 0
            var lost = 0L
            report.statsMap.values.forEach { stat ->
                val members = stat.members
                if (stat.type == "remote-inbound-rtp") {
                    rtt = ((members["roundTripTime"] as? Number)?.toDouble()?.times(1000)?.toInt() ?: rtt)
                    lost += (members["packetsLost"] as? Number)?.toLong() ?: 0L
                }
                if (stat.type == "candidate-pair" && members["state"] == "succeeded") {
                    rtt = ((members["currentRoundTripTime"] as? Number)?.toDouble()?.times(1000)?.toInt() ?: rtt)
                }
            }
            peerRttMs = rtt
            peerPacketLoss = lost
        }
    }

    @Volatile private var peerRttMs = 0
    @Volatile private var peerPacketLoss = 0L

    fun dispose() {
        shouldReconnect = false
        mainHandler.removeCallbacksAndMessages(null)
        closeAllPeers()
        player.release()
        surfaceHelper.stopListening()
        videoTrack.dispose()
        videoSource.capturerObserver.onCapturerStopped()
        videoSource.dispose()
        outputSurface.release()
        surfaceHelper.dispose()
        factoryHolder.dispose()
        egl.release()
    }

    private fun readIceServers(json: JSONArray): List<PeerConnection.IceServer> = buildList {
        for (i in 0 until json.length()) {
            val item = json.optJSONObject(i) ?: continue
            val urls = item.opt("urls")
            val urlList = when (urls) {
                is JSONArray -> (0 until urls.length()).mapNotNull { urls.optString(it).takeIf(String::isNotBlank) }
                is String -> listOf(urls)
                else -> emptyList()
            }
            if (urlList.isNotEmpty()) add(PeerConnection.IceServer.builder(urlList)
                .setUsername(item.optString("username")).setPassword(item.optString("credential")).createIceServer())
        }
    }

    private fun signal(to: String, data: JSONObject) = JSONObject().put("to", to).put("data", data)

    private data class Peer(
        val pc: PeerConnection,
        val send: (JSONObject) -> Unit,
        val pendingCandidates: MutableList<org.webrtc.IceCandidate> = mutableListOf(),
        var remoteDescriptionSet: Boolean = false
    )
}

private object TrafficBytes {
    fun rx() = android.net.TrafficStats.getUidRxBytes(android.os.Process.myUid())
    fun tx() = android.net.TrafficStats.getUidTxBytes(android.os.Process.myUid())
}
