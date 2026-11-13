package com.garuda.rtsplive

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.update

/** Connected-device FGS: continuously receives a live feed from the external IP camera. */
class StreamService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val repo
        get() = (application as GarudaApp).repo

    private var job: Job? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(
                    NotificationChannel("stream", "Camera relay", NotificationManager.IMPORTANCE_LOW)
                )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            stop()
            return START_NOT_STICKY
        }
        if (intent?.action != START) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (job?.isActive == true) return START_NOT_STICKY
        try {
            ServiceCompat.startForeground(
                this,
                7,
                notification("CONNECTING — camera relay"),
                if (Build.VERSION.SDK_INT >= 29)
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                else 0,
            )
        } catch (_: Exception) {
            repo.state.update {
                it.copy(
                    status = "ERROR",
                    detail = "Unable to start foreground service; open the app and try again",
                    active = false,
                )
            }
            stopSelf()
            return START_NOT_STICKY
        }
        val c = repo.config.value
        job = scope.launch { runStream(c) }
        return START_NOT_STICKY
    }

    private suspend fun runStream(c: Config) {
        var outputProtocol = "RTMP"
        var localServerStarted = false
        try {
            outputProtocol =
                if (c.localServer) "LOCAL RTSP"
                else Addresses.output(c.output()).scheme.uppercase()
            c.validate()
            wake =
                getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Garuda:RTSPRelay")
                    .apply { acquire(8 * 60 * 60 * 1000L) }
            val wifiMode =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock =
                getSystemService(WifiManager::class.java)
                    .createWifiLock(wifiMode, "Garuda:RTSPRelayWiFi")
                    .apply {
                        setReferenceCounted(false)
                        acquire()
                    }
            if (c.localServer) {
                try {
                    LocalRtspServer.start(8555, "/live/${c.streamPath}")
                    localServerStarted = true
                } catch (_: Exception) {
                    throw MediaFailure(
                        "Could not start the transmitter RTSP server on port 8555; close another RTSP server and retry"
                    )
                }
                val viewerUrl = c.localViewerUrl(repo.networks.wifiIpv4()).orEmpty()
                repo.state.update {
                    it.copy(
                        viewerUrl = viewerUrl,
                        detail =
                            if (viewerUrl.isBlank()) "Local RTSP server ready; checking camera video packets"
                            else "Local RTSP server ready at $viewerUrl; checking camera video packets",
                    )
                }
                repo.log(
                    if (viewerUrl.isBlank()) "Local RTSP server ready on port 8555"
                    else "Local RTSP server ready: $viewerUrl"
                )
            }
            repo.state.update {
                it.copy(
                    status = "CONNECTING",
                    active = true,
                    detail = "Checking camera video packets",
                    seconds = 0,
                    retries = 0,
                    errors = 0,
                    compatibility = false,
                )
            }
            repo.log("Stream requested — ${c.mode}; $outputProtocol; audio disabled")
            var attempt = 0
            var firstLive = 0L
            while (currentCoroutineContext().isActive) {
                try {
                    firstLive = 0L
                    repo.state.update {
                        it.copy(
                            status = if (attempt == 0) "CONNECTING" else "RECONNECTING",
                            camera = false,
                            destination = false,
                            bitrate = 0.0,
                            bytes = if (outputProtocol == "RTSP") -1 else 0,
                            seconds = 0,
                        )
                    }
                    val info = NativeEngine.probe(c.source, this@StreamService, repo.networks)
                    repo.state.update {
                        it.copy(
                            camera = true,
                            info = info,
                            detail = "Camera connected; connecting $outputProtocol",
                        )
                    }
                    repo.log(
                        "Camera connected: ${info.codec} ${info.width}x${info.height} ${"%.1f".format(info.fps)} fps"
                    )
                    val relayJob = currentCoroutineContext().job
                    NativeEngine.relay(c, info, repo) { bytes, kbps ->
                        scope.launch {
                            if (!relayJob.isActive || !repo.state.value.active) return@launch
                            val now = SystemClock.elapsedRealtime()
                            if (firstLive == 0L) firstLive = now
                            val liveSeconds = (now - firstLive) / 1000
                            wake?.let { if (!it.isHeld) it.acquire(8 * 60 * 60 * 1000L) }
                            val wasLive = repo.state.value.destination
                            repo.state.update {
                                it.copy(
                                    status = "LIVE",
                                    detail = "$outputProtocol streaming active",
                                    destination = true,
                                    camera = true,
                                    bytes = bytes,
                                    bitrate = kbps,
                                    seconds = liveSeconds,
                                )
                            }
                            if (liveSeconds >= 10) attempt = 0
                            if (!wasLive) {
                                repo.log("LIVE — $outputProtocol sending video")
                                getSystemService(NotificationManager::class.java)
                                    .notify(7, notification("LIVE — $outputProtocol streaming active"))
                            }
                            attempt = 0
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: MediaFailure) {
                    if (e.compatibility) {
                        repo.state.update {
                            it.copy(status = "ERROR", detail = e.reason, compatibility = true)
                        }
                        repo.log(e.reason)
                        break
                    }
                    repo.log(e.reason)
                    repo.state.update { it.copy(detail = e.reason, errors = it.errors + 1) }
                } catch (_: Exception) {
                    repo.log("Connection failed; check camera route, DNS, and $outputProtocol address")
                    repo.state.update {
                        it.copy(
                            detail = "Connection failed; check camera and $outputProtocol server",
                            errors = it.errors + 1,
                        )
                    }
                }
                val wait = listOf(1L, 2L, 5L, 10L)[attempt.coerceAtMost(3)]
                attempt++
                repo.state.update {
                    it.copy(
                        status = "RECONNECTING",
                        camera = false,
                        destination = false,
                        retries = it.retries + 1,
                        detail =
                            (if (it.camera) "RECONNECTING $outputProtocol: "
                            else "RECONNECTING CAMERA: ") + it.detail + "; retry in ${wait}s",
                    )
                }
                getSystemService(NotificationManager::class.java)
                    .notify(7, notification("RECONNECTING — camera / $outputProtocol"))
                delay(wait * 1000)
            }
        } catch (e: CancellationException) {
            repo.log("Stream stopped")
        } catch (e: MediaFailure) {
            repo.state.update {
                it.copy(status = "ERROR", detail = e.reason)
            }
            repo.log(e.reason)
        } catch (e: IllegalArgumentException) {
            repo.state.update {
                it.copy(status = "ERROR", detail = e.message ?: "Invalid settings")
            }
        } catch (_: Throwable) {
            repo.state.update {
                it.copy(status = "ERROR", detail = "Streaming initialization failed")
            }
            repo.log("Streaming initialization failed")
        } finally {
            if (localServerStarted) runCatching { LocalRtspServer.stop() }
            wake?.let { if (it.isHeld) it.release() }
            wake = null
            wifiLock?.let { if (it.isHeld) it.release() }
            wifiLock = null
            repo.state.update {
                it.copy(
                    active = false,
                    camera = false,
                    destination = false,
                    bitrate = 0.0,
                    viewerUrl = "",
                    status = if (it.status == "ERROR") "ERROR" else "STOPPED",
                )
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun notification(text: String): Notification {
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val stop =
            PendingIntent.getService(
                this,
                1,
                Intent(this, StreamService::class.java).setAction(STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        return NotificationCompat.Builder(this, "stream")
            .setSmallIcon(R.drawable.ic_notification_garuda)
            .setContentTitle("Garuda Stream")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "STOP STREAM", stop)
            .build()
    }

    private fun stop() {
        repo.state.update { it.copy(status = "STOPPED", detail = "Stopped by operator") }
        job?.cancel()
        if (job == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        wake?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    companion object {
        const val START = "com.garuda.rtsplive.START"
        const val STOP = "com.garuda.rtsplive.STOP"
    }
}
