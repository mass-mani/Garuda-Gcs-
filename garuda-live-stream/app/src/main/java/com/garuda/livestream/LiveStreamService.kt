package com.garuda.livestream

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

data class LiveUiState(
    val rtspConnected: Boolean = false,
    val rtspMessage: String = "RTSP disconnected",
    val internetConnected: Boolean = false,
    val live: Boolean = false,
    val liveMessage: String = "Not streaming",
    val viewerUrl: String = "",
    val viewerCount: Int = 0,
    val startedAtMs: Long = 0,
    val stats: StreamStats = StreamStats()
)

class LiveStreamService : Service() {
    inner class LocalBinder : Binder() { fun service() = this@LiveStreamService }
    interface Listener { fun onState(state: LiveUiState) }

    private val binder = LocalBinder()
    private val listeners = CopyOnWriteArraySet<Listener>()
    private val worker = Executors.newSingleThreadExecutor()
    private val liveGeneration = AtomicLong()
    private val main = Handler(Looper.getMainLooper())
    private var engine: VideoEngine? = null
    private var channel: PublisherChannel? = null
    private var session: LiveSession? = null
    private var publisherToken = ""
    private var serverUrl = ""
    private var state = LiveUiState()
    private var wakeLock: PowerManager.WakeLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val localNetworkCallbacks = mutableListOf<ConnectivityManager.NetworkCallback>()
    private var activeRtspUri: String? = null
    private var activeRtspHost: String? = null
    private var pendingSafeRtspUrl: String? = null
    private var activeRtspRoute = RtspRoute(null, null)
    private var destroying = false
    private var lastNotifiedViewerCount = -1

    private val ticker = object : Runnable {
        override fun run() {
            engine?.sampleStats()
            publish()
            if (!destroying) main.postDelayed(this, 1_000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureEngine()
        observeInternet()
        main.post(ticker)
    }

    override fun onBind(intent: Intent?): IBinder = binder
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") stopLive()
        return START_NOT_STICKY
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (!state.live) engine?.stopRtsp()
        return true
    }

    fun observe(listener: Listener) {
        listeners.add(listener)
        listener.onState(state)
    }

    fun unobserve(listener: Listener) { listeners.remove(listener) }
    fun videoTrack() = engine?.videoTrack
    fun eglContext() = engine?.eglContext()
    fun mediaPlayer() = engine?.player

    fun testRtsp(mediaUri: String, safeUrl: String, host: String, route: RtspRoute) {
        ensureEngine()
        activeRtspUri = mediaUri
        activeRtspHost = host
        activeRtspRoute = route
        pendingSafeRtspUrl = safeUrl
        state = state.copy(rtspConnected = false, rtspMessage = "Connecting to RTSP…")
        publish()
        engine?.startRtsp(mediaUri, host, route.socketFactory)
    }

    fun startLive(server: String, token: String, pin: String?) {
        if (state.live) return
        if (engine?.isRtspConnected() != true) {
            state = state.copy(liveMessage = "Connect and test RTSP before going live")
            publish()
            return
        }
        if (engine?.canPublishVideo() != true) {
            state = state.copy(liveMessage = "No supported hardware H.264 encoder is available on this device.")
            publish()
            return
        }
        if (server.isBlank() || token.isBlank()) {
            state = state.copy(liveMessage = "Enter the self-hosted server URL and publisher token")
            publish()
            return
        }
        serverUrl = server.trimEnd('/')
        publisherToken = token
        val generation = liveGeneration.incrementAndGet()
        state = state.copy(live = true, liveMessage = "Creating secure session…", viewerUrl = "", viewerCount = 0, startedAtMs = System.currentTimeMillis())
        startService(Intent(this, LiveStreamService::class.java))
        startForeground(STREAM_NOTIFICATION_ID, notification("Preparing encrypted WebRTC live stream"))
        acquireWakeLock()
        publish()
        val capturedServer = serverUrl
        val capturedToken = publisherToken
        worker.execute {
            val result = runCatching { SignalingApi().createSession(capturedServer, capturedToken, pin) }
            main.post { result
                .onSuccess { created ->
                    if (generation != liveGeneration.get()) {
                        worker.execute { runCatching { SignalingApi().stopSession(capturedServer, capturedToken, created.id) } }
                        return@onSuccess
                    }
                    session = created
                    state = state.copy(viewerUrl = created.viewerUrl, liveMessage = "Connecting publisher…")
                    channel?.close()
                    channel = PublisherChannel(serverUrl, created.id, publisherToken, ::onSignalMessage) { online, message ->
                        if (online) state = state.copy(liveMessage = "LIVE · encrypted WebRTC")
                        else state = state.copy(liveMessage = message)
                        publish()
                    }
                    publish()
                }
                .onFailure { error ->
                    if (generation != liveGeneration.get()) return@onFailure
                    state = state.copy(live = false, liveMessage = error.message ?: "Could not create live session")
                    releaseWakeLock()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    publish()
                }
            }
        }
    }

    fun stopLive() {
        if (!state.live && session == null) return
        liveGeneration.incrementAndGet()
        val oldSession = session
        val oldServer = serverUrl
        val oldToken = publisherToken
        channel?.close(revoke = true)
        channel = null
        engine?.closeAllPeers()
        engine?.stopRtsp()
        activeRtspUri = null
        activeRtspHost = null
        session = null
        state = state.copy(live = false, liveMessage = "Live session stopped", viewerUrl = "", viewerCount = 0, startedAtMs = 0)
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        publish()
        if (oldSession != null) worker.execute { runCatching { SignalingApi().stopSession(oldServer, oldToken, oldSession.id) } }
    }

    private fun onSignalMessage(message: JSONObject) {
        if (!state.live) return
        when (message.optString("type")) {
            "session_ended" -> {
                stopLive()
                state = state.copy(liveMessage = "Session ended or expired. Test RTSP and start again.")
                publish()
            }
            "authenticated" -> {
                message.optJSONArray("iceServers")?.let { ice -> session = session?.copy(iceServersJson = ice.toString()) }
            }
            "viewer_count" -> {
                state = state.copy(viewerCount = message.optInt("count"))
                publish()
            }
            "viewer_joined" -> Unit
            "viewer_left" -> {
                val id = message.optString("viewerId")
                engine?.closePeer(id)
            }
            "signal" -> {
                val viewerId = message.optString("from")
                val data = message.optJSONObject("data") ?: return
                when (data.optString("type")) {
                    "offer" -> {
                        val ice = runCatching { JSONArray(session?.iceServersJson ?: "[]") }.getOrDefault(JSONArray())
                        engine?.addViewer(viewerId, data.optString("sdp"), ice) { envelope -> channel?.send(
                            JSONObject().put("type", "signal").put("to", viewerId).put("data", envelope.optJSONObject("data"))
                        ) }
                    }
                    "candidate" -> engine?.addRemoteCandidate(viewerId, data)
                }
            }
            "server_error" -> {
                state = state.copy(liveMessage = message.optString("message", "Signaling error"))
                publish()
            }
        }
    }

    private fun ensureEngine() {
        if (engine != null) return
        engine = VideoEngine(this, { connected, message ->
            if (connected) pendingSafeRtspUrl?.let { AppPrefs(this).lastRtspUrl = it }
            state = state.copy(rtspConnected = connected, rtspMessage = message)
            publish()
        }, { stats ->
            state = state.copy(stats = stats)
            publish()
        })
    }

    private fun observeInternet() {
        val manager = getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { main.post { refreshInternet(manager) } }
            override fun onLost(network: Network) { main.post { refreshInternet(manager) } }
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) { main.post { refreshInternet(manager) } }
        }
        networkCallback = callback
        runCatching { manager.registerDefaultNetworkCallback(callback) }
        for (transport in listOf(NetworkCapabilities.TRANSPORT_WIFI, NetworkCapabilities.TRANSPORT_ETHERNET)) {
            val localCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { main.post { refreshRtspRoute() } }
                override fun onLost(network: Network) { main.post { refreshRtspRoute() } }
                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) { main.post { refreshRtspRoute() } }
            }
            localNetworkCallbacks.add(localCallback)
            runCatching { manager.registerNetworkCallback(NetworkRequest.Builder().addTransportType(transport).build(), localCallback) }
        }
        refreshInternet(manager)
        refreshRtspRoute()
    }

    private fun refreshRtspRoute() {
        val uri = activeRtspUri ?: return
        val host = activeRtspHost ?: return
        val route = RtspNetwork.select(this, host)
        if (route.networkKey == activeRtspRoute.networkKey) return
        activeRtspRoute = route
        main.post {
            state = state.copy(rtspConnected = false, rtspMessage = "RTSP network changed · reconnecting")
            publish()
            engine?.startRtsp(uri, host, route.socketFactory)
        }
    }

    private fun refreshInternet(manager: ConnectivityManager) {
        val caps = manager.getNetworkCapabilities(manager.activeNetwork)
        val online = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        if (online != state.internetConnected) {
            state = state.copy(internetConnected = online)
            if (!online && state.live) state = state.copy(liveMessage = "INTERNET CONNECTION LOST · reconnecting")
            publish()
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GarudaLive:Publisher")
            .apply { setReferenceCounted(false); acquire() }
    }

    private fun releaseWakeLock() { runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }; wakeLock = null }

    private fun notification(message: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
            NotificationChannel(NOTIFICATION_CHANNEL, "Garuda Live Stream", NotificationManager.IMPORTANCE_LOW)
        )
        val openApp = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("Garuda Live Stream")
            .setContentText(message)
            .setOngoing(true)
            .addAction(0, "STOP LIVE", PendingIntent.getService(this, 1, Intent(this, LiveStreamService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setContentIntent(openApp)
            .build()
    }

    private fun publish() {
        if (state.live && state.liveMessage.contains("LIVE") && lastNotifiedViewerCount != state.viewerCount) {
            lastNotifiedViewerCount = state.viewerCount
            val text = "${state.viewerCount} viewer${if (state.viewerCount == 1) "" else "s"} · streaming"
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(STREAM_NOTIFICATION_ID, notification(text))
        }
        main.post { listeners.forEach { it.onState(state) } }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // The foreground service deliberately keeps the stream alive when its Activity is backgrounded.
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        liveGeneration.incrementAndGet()
        destroying = true
        main.removeCallbacks(ticker)
        networkCallback?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        localNetworkCallbacks.forEach { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        channel?.close()
        releaseWakeLock()
        engine?.dispose()
        worker.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_CHANNEL = "garuda_live_stream"
        private const val STREAM_NOTIFICATION_ID = 7401
    }
}
