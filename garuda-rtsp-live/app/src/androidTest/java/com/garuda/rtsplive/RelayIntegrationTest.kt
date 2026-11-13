package com.garuda.rtsplive

import android.content.Intent
import android.graphics.SurfaceTexture
import android.view.Surface
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFmpegKitConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Requires host RTSP at 18554/source1 and MediaMTX RTMP 19350 -> RTSP 18555. No cloud account. */
@RunWith(AndroidJUnit4::class)
class RelayIntegrationTest {
    private val app
        get() = ApplicationProvider.getApplicationContext<GarudaApp>()

    @Test
    fun probeAndCopyRelay() = runBlocking {
        val repo = app.repo
        NativeEngine.initialize()
        val info = NativeEngine.probe("rtsp://10.0.2.2:18554/source1", repo.networks)
        assertEquals("h264", info.codec)
        assertEquals(640, info.width)
        assertTrue(info.packets > 0)
        val c =
            Config(
                source = "rtsp://10.0.2.2:18554/source1",
                server = "rtmp://10.0.2.2:19350/live/",
                key = "integration-key",
            )
        val progress = CompletableDeferred<Unit>()
        val job =
            launch(Dispatchers.IO) {
                NativeEngine.relay(c, info, repo) { bytes, _ ->
                    if (bytes > 1024) progress.complete(Unit)
                }
            }
        try {
            withTimeout(25000) { progress.await() }
            val received =
                NativeEngine.probe("rtsp://10.0.2.2:18555/live/integration-key", repo.networks)
            assertEquals(info.codec, received.codec)
            assertEquals(info.width, received.width)
            assertTrue(received.packets > 0)
            assertTrue(repo.logs.value.none { "integration-key" in it })
            println(
                "RELAY VERIFIED: ${received.width}x${received.height}, ${received.packets} video packets; FFmpeg ${FFmpegKitConfig.getFFmpegVersion()}"
            )
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test
    fun foregroundContinuesAfterHomeAndStops() = runBlocking {
        val repo = app.repo
        repo.loaded.first { it }
        repo.update(
            Config(
                source = "rtsp://10.0.2.2:18554/source1",
                server = "rtmp://10.0.2.2:19350/live/",
                key = "background-key",
            )
        )
        val instrument = InstrumentationRegistry.getInstrumentation()
        instrument.runOnMainSync {
            app.startActivity(
                Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        delay(1500)
        ContextCompat.startForegroundService(
            app,
            Intent(app, StreamService::class.java).setAction(StreamService.START),
        )
        try {
            withTimeout(30000) { repo.state.first { it.destination } }
            instrument.uiAutomation.executeShellCommand("input keyevent 3").close()
            val before = repo.state.value.bytes
            delay(4000)
            assertTrue(
                "Relay must continue while activity is backgrounded",
                repo.state.value.bytes > before,
            )
            assertTrue(repo.state.value.active)
            val received =
                NativeEngine.probe("rtsp://10.0.2.2:18555/live/background-key", repo.networks)
            assertTrue(received.packets > 0)
            println("BACKGROUND VERIFIED: bytes increasing with activity hidden")
        } finally {
            val notification =
                app.getSystemService(android.app.NotificationManager::class.java)
                    .activeNotifications
                    .first { it.id == 7 }
                    .notification
            notification.actions.first().actionIntent.send()
            withTimeout(10000) { repo.state.first { !it.active } }
        }
        assertEquals("STOPPED", repo.state.value.status)
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @Test
    fun previewRendersVideo() = runBlocking {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val firstFrame = CompletableDeferred<Unit>()
        var player: ExoPlayer? = null
        var texture: SurfaceTexture? = null
        var surface: Surface? = null
        RoutedGateway(Addresses.source("rtsp://10.0.2.2:18554/source1"), app.repo.networks, true)
            .use { gateway ->
                instrument.runOnMainSync {
                    texture = SurfaceTexture(false)
                    surface = Surface(texture)
                    player =
                        ExoPlayer.Builder(app).build().apply {
                            addListener(
                                object : Player.Listener {
                                    override fun onRenderedFirstFrame() {
                                        firstFrame.complete(Unit)
                                    }

                                    override fun onPlayerError(
                                        error: androidx.media3.common.PlaybackException
                                    ) {
                                        firstFrame.completeExceptionally(
                                            AssertionError("Preview failed: ${error.errorCodeName}")
                                        )
                                    }
                                }
                            )
                            setVideoSurface(surface)
                            setMediaSource(
                                RtspMediaSource.Factory()
                                    .setForceUseRtpTcp(true)
                                    .createMediaSource(MediaItem.fromUri(gateway.url))
                            )
                            prepare()
                            playWhenReady = true
                        }
                }
                try {
                    withTimeout(20000) { firstFrame.await() }
                    println("PREVIEW VERIFIED: first decoded frame rendered")
                } finally {
                    instrument.runOnMainSync {
                        player?.release()
                        surface?.release()
                        texture?.release()
                    }
                }
            }
    }

    @Test
    fun digestAuthenticatedCamera() = runBlocking {
        val info =
            NativeEngine.probe(
                "rtsp://fixture:fixture-password@10.0.2.2:18555/secure-camera",
                app.repo.networks,
            )
        assertEquals("h264", info.codec)
        assertTrue(info.packets > 0)
        assertFalse(app.repo.logs.value.joinToString().contains("fixture-password"))
    }

    @Test
    fun nativeBuildIsLgplAndReportsEncoder() = runBlocking {
        NativeEngine.initialize()
        val session =
            com.arthenica.ffmpegkit.FFmpegKit.executeWithArguments(
                arrayOf("-hide_banner", "-buildconf")
            )
        val config = session.getAllLogsAsString(2000)
        assertFalse("GPL codecs must not be included", config.contains("--enable-gpl"))
        assertFalse("Nonfree codecs must not be included", config.contains("--enable-nonfree"))
        println(
            "FFmpeg ${FFmpegKitConfig.getFFmpegVersion()} LGPL configuration; h264_mediacodec listed: ${NativeEngine.hasHardwareEncoder()}"
        )
    }

    @Test
    fun relayRecoversAfterDestinationDisconnect() = runBlocking {
        val repo = app.repo
        repo.loaded.first { it }
        val cm = app.getSystemService(android.net.ConnectivityManager::class.java)
        val address =
            cm.getLinkProperties(cm.activeNetwork)!!
                .linkAddresses
                .first { it.address is java.net.Inet4Address }
                .address
                .hostAddress
        TestFaultBridge(repo.networks).use { bridge ->
            repo.update(
                Config(
                    source = "rtsp://10.0.2.2:18554/source1",
                    server = "rtmp://$address:${bridge.port}/live/",
                    key = "reconnect-key",
                )
            )
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                app.startActivity(
                    Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            delay(1000)
            ContextCompat.startForegroundService(
                app,
                Intent(app, StreamService::class.java).setAction(StreamService.START),
            )
            try {
                withTimeout(30000) { repo.state.first { it.destination } }
                bridge.interrupt()
                withTimeout(30000) { repo.state.first { it.status == "RECONNECTING" } }
                delay(2000)
                bridge.available = true
                withTimeout(45000) { repo.state.first { it.destination && it.retries > 0 } }
                val received =
                    NativeEngine.probe("rtsp://10.0.2.2:18555/live/reconnect-key", repo.networks)
                assertTrue(received.packets > 0)
                println(
                    "RECONNECT VERIFIED: RTMP packets resumed after forced disconnect without pressing START"
                )
            } finally {
                app.startService(
                    Intent(app, StreamService::class.java).setAction(StreamService.STOP)
                )
                withTimeout(10000) { repo.state.first { !it.active } }
            }
        }
    }

    @Test
    fun layoutsRenderInBothOrientations() = runBlocking {
        val repo = app.repo
        repo.loaded.first { it }
        repo.update(Config())
        androidx.test.core.app.ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for ((orientation, label) in
                listOf(
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE to "landscape",
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT to "portrait",
                )) {
                scenario.onActivity { it.requestedOrientation = orientation }
                delay(1300)
                // Test-only screenshot opt-out; production always uses FLAG_SECURE.
                scenario.onActivity {
                    it.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                }
                delay(500)
                val bitmap =
                    InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                assertNotNull(bitmap)
                java.io.File(app.filesDir, "validation-$label.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                scenario.onActivity {
                    it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                }
            }
        }
    }

    @Test
    fun invalidSourceIsUseful() = runBlocking {
        try {
            NativeEngine.probe("rtsp://10.0.2.2:19999/absent", app.repo.networks)
            fail("Expected failure")
        } catch (e: MediaFailure) {
            assertTrue(e.reason.isNotBlank())
            assertFalse(e.reason.contains("rtsp://"))
        }
    }
}

/** Test-only TCP fault injector; no media recording. */
private class TestFaultBridge(private val networks: Networks) : java.io.Closeable {
    private val server = java.net.ServerSocket(0)
    val port
        get() = server.localPort

    @Volatile var available = true
    private val sockets = java.util.concurrent.ConcurrentHashMap.newKeySet<java.net.Socket>()
    private val workers = java.util.concurrent.Executors.newCachedThreadPool()

    init {
        workers.execute {
            try {
                while (!server.isClosed) {
                    val client = server.accept()
                    if (!available) {
                        client.close()
                        continue
                    }
                    val upstream = networks.connect("10.0.2.2", 19350, false)
                    sockets.add(client)
                    sockets.add(upstream)
                    workers.execute { pipe(client, upstream) }
                    workers.execute { pipe(upstream, client) }
                }
            } catch (_: Exception) {}
        }
    }

    private fun pipe(a: java.net.Socket, b: java.net.Socket) {
        try {
            a.getInputStream().copyTo(b.getOutputStream(), 32768)
        } catch (_: Exception) {} finally {
            runCatching { a.close() }
            runCatching { b.close() }
            sockets.remove(a)
            sockets.remove(b)
        }
    }

    fun interrupt() {
        available = false
        sockets.forEach { runCatching { it.close() } }
    }

    override fun close() {
        interrupt()
        server.close()
        workers.shutdownNow()
    }
}
