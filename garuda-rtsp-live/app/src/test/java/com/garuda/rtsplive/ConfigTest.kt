package com.garuda.rtsplive

import org.junit.Assert.*
import org.junit.Test

class ConfigTest {
    @Test
    fun c12DefaultUsesItsDocumentedVisibleStream() {
        val c = Config()
        assertEquals(CameraProfiles.SKYDROID_C12, c.cameraModel)
        assertEquals(CameraProfiles.C12_VISIBLE_RTSP, c.source)
        assertEquals(13, CameraProfiles.models.size)
        assertTrue(CameraProfiles.models.contains("SIYI R1M FPV HD Camera"))
        assertTrue(CameraProfiles.models.contains("Skydroid C13"))
    }

    @Test
    fun embeddedRtspServerIsDefaultAndPublishesOnTheTransmitter() {
        val c = Config()
        assertTrue(c.localServer)
        assertEquals("rtsp://127.0.0.1:8555/live/drone0", c.output())
        assertEquals("rtsp://192.168.0.104:8555/live/drone0", c.localViewerUrl("192.168.0.104"))
        c.validate()
    }

    @Test
    fun localStreamNameIsSafeForRtspPath() {
        assertThrows(IllegalArgumentException::class.java) {
            Config(streamPath = "camera/../private").validate()
        }
        assertThrows(IllegalArgumentException::class.java) {
            Config(streamPath = "bad name").validate()
        }
    }

    @Test
    fun legacyC12DefaultsMigrateWithoutChangingCustomCameraUrls() {
        val migratedVisible =
            ConfigMigrations.migrateCameraModel(
                Config(source = CameraProfiles.LEGACY_C12_VISIBLE_RTSP),
                cameraModelWasSaved = false,
            )
        assertEquals(CameraProfiles.C12_VISIBLE_RTSP, migratedVisible.source)

        val migratedThermal =
            ConfigMigrations.migrateCameraModel(
                Config(source = CameraProfiles.LEGACY_C12_THERMAL_RTSP),
                cameraModelWasSaved = false,
            )
        assertEquals(CameraProfiles.C12_THERMAL_RTSP, migratedThermal.source)

        val custom = Config(source = "rtsp://10.0.0.8:554/custom")
        assertEquals(
            custom.source,
            ConfigMigrations.migrateCameraModel(custom, cameraModelWasSaved = false).source,
        )
    }

    @Test
    fun defaultUsesCopyAndNoAudio() {
        val c = Config(key = "test-secret")
        val args =
            Commands.relay(c, c.source, c.output(), VideoInfo("h264", 1920, 1080, 30.0, 100))
                .toList()
        assertTrue(args.windowed(2).contains(listOf("-c:v", "copy")))
        assertTrue("-an" in args)
        assertFalse("-localaddr" in args)
        assertFalse("-vf" in args)
        assertFalse("+genpts" in args)
    }

    @Test
    fun outputModes() {
        assertEquals(
            "rtmp://demo.dronestreamx.com:1945/drone/abc",
            Config(key = "abc", localServer = false).output(),
        )
        assertEquals(
            "rtmps://a.test/live/abc",
            Config(combined = true, fullUrl = "rtmps://a.test/live/abc", localServer = false).output(),
        )
    }

    @Test
    fun cameraProbeUsesRtspTcpAndLeavesRoutingToAndroidSubnetRoutes() {
        val args = Commands.probe(CameraProfiles.C12_VISIBLE_RTSP).toList()
        assertTrue(args.windowed(2).contains(listOf("-rtsp_transport", "tcp")))
        assertFalse("-localaddr" in args)
        assertTrue(args.last() == CameraProfiles.C12_VISIBLE_RTSP)
    }

    @Test
    fun destinationPresetRespectsCompleteUrlMode() {
        val server = ConfigMigrations.LAN_RTMP_SERVER
        val selected = Config(combined = true).withDestinationPreset(server)

        assertTrue(selected.combined)
        assertEquals("rtmp://192.168.137.1:1935/live/stream-key", selected.fullUrl)
        assertEquals(selected.fullUrl, selected.output())
        assertEquals("", selected.key)
        selected.validate()
    }

    @Test
    fun destinationPresetKeepsServerAndKeyModeAndClearsOtherKeys() {
        val selected = Config(server = "rtmp://old.example/live/", key = "old-secret")
            .withDestinationPreset(ConfigMigrations.LAN_RTMP_SERVER)

        assertFalse(selected.combined)
        assertEquals(ConfigMigrations.LAN_RTMP_SERVER, selected.server)
        assertEquals("", selected.key)
    }

    @Test
    fun viewerUrlsAcceptHostedAndDirectPlaybackUrlsButRejectPublishCredentials() {
        listOf(
            "https://player.example/embed/drone-01",
            "https://cdn.example/live/drone-01/index.m3u8",
            "rtsp://192.0.2.4:8555/live/drone-01",
        ).forEach { assertEquals(it, Addresses.viewer(it).toString()) }
        listOf(
            "rtmp://ingest.example/live/secret-key",
            "https://user:password@player.example/embed/drone",
            "file:///tmp/video.m3u8",
        ).forEach { value -> assertThrows(IllegalArgumentException::class.java) { Addresses.viewer(value) } }
    }

    @Test
    fun privateLanRtmpPathMapsToOneSharedRtspViewerPath() {
        val c =
            Config(
                server = "rtmp://192.168.0.20:1935/live/",
                key = "camera2",
                localServer = false,
            )
        assertEquals("rtsp://192.168.0.20:8555/live/camera2", c.lanViewerUrl())

        val placeholder = Config(server = "rtmp://192.168.0.20:1935/live/", localServer = false)
        assertEquals("rtsp://192.168.0.20:8555/live/camera-key", placeholder.lanViewerUrl())
        assertNull(Config(key = "secret-key", localServer = false).lanViewerUrl())
    }

    @Test
    fun localRtspPublisherKeepsHevcAndUsesTcpForAllViewers() {
        val c =
            Config(
                server = "rtsp://192.168.137.67:8555/live/",
                key = "camera2",
                mode = StreamMode.TRANSCODE,
                localServer = false,
            )
        val url = c.output()
        val args =
            Commands.relay(c, c.source, url, VideoInfo("hevc", 1280, 720, 25.0))
                .toList()

        assertEquals("rtsp://192.168.137.67:8555/live/camera2", url)
        assertEquals(url, c.lanViewerUrl())
        assertTrue(args.windowed(2).contains(listOf("-c:v", "copy")))
        assertFalse("h264_mediacodec" in args)
        assertFalse("-vf" in args)
        assertTrue(args.lastIndexOf("-rtsp_transport") > args.indexOf("-i"))
        assertEquals("tcp", args[args.lastIndexOf("-rtsp_transport") + 1])
        c.validate()
    }

    @Test
    fun rtspCopyProgressUsesAdvancingMediaCountersWhenSizeIsUnavailable() {
        assertTrue(RelayProgress.hasVideo("rtsp", -1, 0, 2613.322, 60, 0))
        assertFalse(RelayProgress.hasVideo("rtsp", -1, 0, 0.0, 0, 0))
        assertFalse(RelayProgress.hasVideo("rtsp", -1, 0, 2613.322, 60, 60))
        assertFalse(RelayProgress.hasVideo("rtmp", 2048, 1024, 0.0, 0, 0))
        assertTrue(RelayProgress.hasVideo("rtmp", 2048, 1024, 1000.0, 1, 0))
    }

    @Test
    fun migrateLegacyTwitchPresetAndKeepSavedKey() {
        val previous = Config(server = "rtmp://live.twitch.tv/app/", key = "abc", localServer = false)
        val migrated = ConfigMigrations.migrateLegacyTwitchPreset(previous)
        assertEquals("rtmp://aps30.contribute.live-video.net/app/abc", migrated.output())
        assertEquals("abc", migrated.key)

        val combined =
            Config(
                combined = true,
                fullUrl = "rtmp://live.twitch.tv/app/abc?bandwidthtest=true",
                localServer = false,
            )
        assertEquals(
            "rtmp://aps30.contribute.live-video.net/app/abc?bandwidthtest=true",
            ConfigMigrations.migrateLegacyTwitchPreset(combined).output(),
        )
        val customSecure = Config(server = "rtmps://live.twitch.tv/app/", key = "abc", localServer = false)
        assertEquals(customSecure, ConfigMigrations.migrateLegacyTwitchPreset(customSecure))
    }

    @Test
    fun rejectBadSources() {
        listOf("https://camera/x", "rtsp:///x", "rtsp://a:0/x", "rtsp://a:70000/x").forEach {
            assertThrows(IllegalArgumentException::class.java) { Addresses.source(it) }
        }
    }

    @Test
    fun rejectBadOutputs() {
        listOf("rtmp://host/app", "file:///tmp/x", "rtmp://user:pw@a/live/k").forEach {
            assertThrows(IllegalArgumentException::class.java) { Addresses.output(it) }
        }
    }

    @Test
    fun keepQueryAndNestedApp() {
        val u = Addresses.output("rtmp://host:1935/a/b/key?token=xyz")
        assertEquals("a/b", Addresses.app(u))
        assertEquals("key?token=xyz", Addresses.playpath(u))
        assertEquals("rtmp://host:1935/a/b", Addresses.tcUrl(u))
    }

    @Test
    fun noSecretsInDiagnostics() {
        listOf(
                "Error opening rtmp://a/app/secret",
                "secret",
                "Authorization: abc",
                "rtsp://user:pass@camera/video: Connection refused",
            )
            .forEach {
                val safe = SafeMessages.native(it)
                assertFalse(safe.orEmpty().contains("secret"))
                assertFalse(safe.orEmpty().contains("pass"))
                assertFalse(safe.orEmpty().contains("rtmp://"))
            }
        assertEquals(
            "Authentication rejected; check camera credentials or stream key",
            SafeMessages.native("RTSP response 401 Unauthorized"),
        )
        assertEquals(
            "Publish server rejected the stream; check the destination path and access settings",
            SafeMessages.native("NetStream.Publish.BadName"),
        )
    }

    @Test
    fun nativeDiagnosticDetailRedactsConfiguredUrlsAndKeys() {
        val config =
            Config(
                source = "rtsp://user:camera-pass@camera.local:554/video",
                server = "rtmp://laptop.local:1935/live/",
                key = "drone-secret",
            )
        val detail =
            SafeMessages.nativeDetail(
                "Could not write header to rtmp://laptop.local:1935/live/drone-secret " +
                    "from rtsp://user:camera-pass@camera.local:554/video",
                config,
            )
        assertTrue(detail.contains("Could not write header"))
        listOf("drone-secret", "camera-pass", "rtmp://", "rtsp://").forEach {
            assertFalse(detail.contains(it, ignoreCase = true))
        }
    }

    @Test
    fun transcodeIsExplicit() {
        val c = Config(key = "test", mode = StreamMode.TRANSCODE, fps = 30, resolution = "1280x720", localServer = false)
        val a =
            Commands.relay(
                    c,
                    c.source,
                    "rtmp://localhost/live/test",
                    VideoInfo("hevc", 1920, 1080, 25.0),
                    "fixture.hardware.encoder",
                )
                .toList()
        assertTrue(a.windowed(2).contains(listOf("-c:v", "h264_mediacodec")))
        assertTrue(a.windowed(2).contains(listOf("-g", "60")))
        assertFalse("-flags:v" in a)
        assertTrue("-vf" in a)
    }
}
