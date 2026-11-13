package com.garuda.rtsplive

import android.content.Context
import com.arthenica.ffmpegkit.*
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*

class MediaFailure(val reason: String, val compatibility: Boolean = false) : IOException(reason)

object NativeEngine {
    @Synchronized
    fun initialize() {
        try {
            FFmpegKitConfig.setLogRedirectionStrategy(LogRedirectionStrategy.NEVER_PRINT_LOGS)
            FFmpegKitConfig.setSessionHistorySize(4)
        } catch (_: Throwable) {
            throw MediaFailure(
                "FFmpeg initialization failed; install the APK matching this device ABI"
            )
        }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    suspend fun probe(source: String, context: Context, networks: Networks): VideoInfo =
        withContext(Dispatchers.IO) {
            val uri = Addresses.source(source)
            val gateway = RoutedGateway(uri, networks, camera = true)
            try {
                withContext(Dispatchers.Main.immediate) {
                    val result = CompletableDeferred<VideoInfo>()
                    val player = ExoPlayer.Builder(context.applicationContext).build()
                    fun completeWhenReady(tracks: Tracks) {
                        if (player.playbackState != Player.STATE_READY || result.isCompleted) return
                        val group =
                            tracks.groups.firstOrNull {
                                it.type == C.TRACK_TYPE_VIDEO && it.isSupported
                            } ?: return
                        val format = group.getTrackFormat(0)
                        val codec =
                            when (format.sampleMimeType) {
                                MimeTypes.VIDEO_H264 -> "h264"
                                MimeTypes.VIDEO_H265 -> "hevc"
                                else -> format.sampleMimeType?.substringAfter('/') ?: "unknown"
                            }
                        if (codec == "unknown" || format.width <= 0 || format.height <= 0) return
                        result.complete(
                            VideoInfo(
                                codec = codec,
                                width = format.width,
                                height = format.height,
                                fps = format.frameRate.toDouble().takeIf { it > 0 } ?: 0.0,
                                packets = 0,
                            )
                        )
                    }
                    val listener =
                        object : Player.Listener {
                            override fun onTracksChanged(tracks: Tracks) = completeWhenReady(tracks)

                            override fun onPlaybackStateChanged(playbackState: Int) {
                                if (playbackState == Player.STATE_READY)
                                    completeWhenReady(player.currentTracks)
                            }

                            override fun onPlayerError(error: PlaybackException) {
                                result.completeExceptionally(
                                    MediaFailure(
                                        "RTSP camera did not provide a playable video track; check its URL and stream settings"
                                    )
                                )
                            }
                        }
                    player.addListener(listener)
                    try {
                        player.setMediaSource(
                            RtspMediaSource.Factory()
                                .setForceUseRtpTcp(true)
                                .setTimeoutMs(8000)
                                .createMediaSource(MediaItem.fromUri(gateway.url))
                        )
                        player.prepare()
                        player.playWhenReady = true
                        withTimeout(18000) { result.await() }
                    } catch (_: TimeoutCancellationException) {
                        throw MediaFailure(
                            "RTSP camera timeout or no supported video track; check the camera URL and network"
                        )
                    } finally {
                        player.removeListener(listener)
                        player.release()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: MediaFailure) {
                throw e
            } catch (_: Exception) {
                throw MediaFailure("Unable to connect to RTSP camera; check its address and network")
            } finally {
                gateway.close()
            }
        }

    suspend fun hasHardwareEncoder(): Boolean =
        withContext(Dispatchers.IO) {
            initialize()
            val done = CompletableDeferred<Unit>()
            val s =
                FFmpegSession.create(
                    arrayOf("-hide_banner", "-encoders"),
                    { done.complete(Unit) },
                    null,
                    null,
                )
            SecureSessionRunner.execute(s)
            try {
                withTimeout(5000) { done.await() }
                // Read the completed session output after FFmpegKit has drained its
                // asynchronous log callbacks. Sampling a mutable flag inside the
                // callback raced the completion callback and intermittently hid the
                // encoder even when the device and native build both had it.
                s.getAllLogsAsString().contains("h264_mediacodec")
            } finally {
                if (!done.isCompleted) FFmpegKit.cancel(s.sessionId)
            }
        }

    suspend fun relay(
        c: Config,
        info: VideoInfo,
        repo: Repository,
        onProgress: (Long, Double) -> Unit,
    ): Unit =
        withContext(Dispatchers.IO) {
            initialize()
            val outputScheme = Addresses.output(c.output()).scheme
            // RTSP LAN publish can carry HEVC directly; the RTSP server and VLC
            // viewers handle decoding. Only RTMP requires conversion to H.264.
            val relayMode = if (outputScheme == "rtsp") StreamMode.COPY else c.mode
            val relayConfig = c.copy(mode = relayMode)
            if (relayMode == StreamMode.TRANSCODE && !hasHardwareEncoder())
                throw MediaFailure(
                    "This FFmpeg build has no H.264 MediaCodec encoder. Select COPY and set camera to H.264.",
                    true,
                )
            if (relayMode != StreamMode.TRANSCODE && info.codec != "h264" && outputScheme != "rtsp")
                throw MediaFailure(
                    "Camera is ${info.codec}. Select H.264 in camera settings, or explicitly enable TRANSCODE.",
                    true,
                )
            val encoderName =
                if (relayMode == StreamMode.TRANSCODE)
                    HardwareEncoder.choose(c, info)
                        ?: throw MediaFailure(
                            "No hardware H.264 encoder supports these settings. Use COPY with camera H.264 output, or lower resolution/FPS.",
                            true,
                        )
                else null
            // The transmitter camera is commonly attached through a vendor interface (for
            // example ar_net0) that Android does not expose as the app's default network.
            // The preview already uses RoutedGateway to bind the upstream RTSP socket to that
            // interface; route the FFmpeg input through the same bridge so preview success also
            // works for the actual relay.
            val cameraGateway =
                RoutedGateway(Addresses.source(c.source), repo.networks, camera = true) { event ->
                    repo.log(SafeMessages.nativeDetail(event, c))
                }
            try {
              run {
                val done = CompletableDeferred<Int>()
                var diagnostic = "Camera or publish connection ended"
                var compatibility = false
                val progressAt = AtomicLong(android.os.SystemClock.elapsedRealtime())
                val sentVideo = java.util.concurrent.atomic.AtomicBoolean(false)
                val lastBytes = AtomicLong(0)
                val lastFrame = AtomicLong(0)
                val lastSample = AtomicLong(android.os.SystemClock.elapsedRealtime())
                val lastStatsLog = AtomicLong(0)
                val session =
                    FFmpegSession.create(
                        Commands.relay(relayConfig, cameraGateway.url, c.output(), info, encoderName),
                        { done.complete(it.returnCode?.value ?: -1) },
                        { log ->
                            SafeMessages.native(log.message)?.let { safe ->
                                diagnostic = safe
                                val detail = SafeMessages.nativeDetail(log.message, c)
                                repo.log(if (detail.isBlank()) safe else "$safe — $detail")
                                if ("Unsupported codec" in safe || "Timestamp" in safe)
                                    compatibility = true
                            }
                            FFmpegKitConfig.getSession(log.sessionId)?.let {
                                BoundedSession.clearLogs(it)
                            }
                        },
                        { stats ->
                            val bytes = stats.size
                            val frames = stats.videoFrameNumber.toLong()
                            val previousFrames = lastFrame.getAndSet(frames)
                            val previous = lastBytes.getAndSet(bytes)
                            val now = android.os.SystemClock.elapsedRealtime()
                            val interval = now - lastSample.getAndSet(now)
                            if (
                                outputScheme == "rtsp" &&
                                    now - lastStatsLog.get() >= 5000 &&
                                    lastStatsLog.compareAndSet(lastStatsLog.get(), now)
                            ) {
                                repo.log(
                                    "RTSP relay counters: size=$bytes time=${stats.time} frames=$frames"
                                )
                            }
                            if (
                                RelayProgress.hasVideo(
                                    outputScheme,
                                    bytes,
                                    previous,
                                    stats.time,
                                    frames,
                                    previousFrames,
                                )
                            ) {
                                sentVideo.set(true)
                                progressAt.set(now)
                                val reportedBytes = if (bytes >= 0) bytes else -1L
                                val bitrate =
                                    if (bytes >= 0 && previous >= 0 && interval > 0 && bytes > previous)
                                        (bytes - previous) * 8.0 / interval
                                    else 0.0
                                onProgress(
                                    reportedBytes,
                                    bitrate,
                                )
                            }
                            (FFmpegKitConfig.getSession(stats.sessionId) as? FFmpegSession)?.let {
                                BoundedSession.clearStatistics(it)
                            }
                        },
                    )
                SecureSessionRunner.execute(session)
                val watchdog = launch {
                    while (isActive && !done.isCompleted) {
                        delay(1000)
                        if (android.os.SystemClock.elapsedRealtime() - progressAt.get() > 20000) {
                            diagnostic = "No outgoing video for 20 seconds; reconnecting"
                            FFmpegKit.cancel(session.sessionId)
                            break
                        }
                    }
                }
                try {
                    val returnCode = done.await()
                    if (outputScheme == "rtsp") {
                        repo.log("RTSP relay ended; FFmpeg exit code $returnCode")
                        if (diagnostic == "Camera or publish connection ended" && returnCode == 0)
                            diagnostic = "RTSP input ended; FFmpeg stopped normally"
                    }
                    throw MediaFailure(diagnostic, compatibility && !sentVideo.get())
                } finally {
                    watchdog.cancel()
                    if (!done.isCompleted) FFmpegKit.cancel(session.sessionId)
                    BoundedSession.clearLogs(session)
                }
              }
            } finally {
                cameraGateway.close()
            }
        }
}
