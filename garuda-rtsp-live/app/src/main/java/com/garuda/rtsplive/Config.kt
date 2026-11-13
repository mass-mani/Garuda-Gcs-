package com.garuda.rtsplive

import java.net.URI

enum class StreamMode {
    AUTO,
    COPY,
    TRANSCODE,
}

object CameraProfiles {
    const val SKYDROID_C12 = "Skydroid C12"
    const val C12_VISIBLE_RTSP = "rtsp://192.168.144.108:554/stream=1"
    const val C12_THERMAL_RTSP = "rtsp://192.168.144.108:555/stream=2"
    const val LEGACY_C12_VISIBLE_RTSP = "rtsp://192.168.144.25:8554/video1"
    const val LEGACY_C12_THERMAL_RTSP = "rtsp://192.168.144.25:8554/video2"

    val models = listOf(
        "SIYI A8 Mini",
        "SIYI ZR10",
        "SIYI ZR30",
        "SIYI A2 Mini",
        "SIYI ZT6",
        "SIYI ZT30",
        "SIYI UniPod",
        "SIYI MT11",
        "SIYI R1M FPV HD Camera",
        "Skydroid C10",
        "Skydroid C11",
        SKYDROID_C12,
        "Skydroid C13",
    )
}

data class Config(
    val source: String = CameraProfiles.C12_VISIBLE_RTSP,
    val server: String = ConfigMigrations.DRONESTREAMX_SERVER,
    val key: String = "",
    val fullUrl: String = "",
    val combined: Boolean = false,
    val mode: StreamMode = StreamMode.AUTO,
    val resolution: String = "Original",
    val fps: Int = 0,
    val bitrate: Int = 4,
    val genpts: Boolean = false,
    val cameraModel: String = CameraProfiles.SKYDROID_C12,
    val localServer: Boolean = true,
    val streamPath: String = "drone0",
    val viewerUrl: String = "",
) {
    fun output(): String =
        if (localServer) "rtsp://127.0.0.1:8555/live/${streamPath.trim()}"
        else if (combined) fullUrl.trim() else server.trim().trimEnd('/') + "/" + key.trim()

    /** Select a server preset without changing the user's URL-entry mode. */
    fun withDestinationPreset(presetServer: String): Config {
        val keepKey = !combined && server.trimEnd('/') == presetServer.trimEnd('/')
        return copy(
            server = presetServer,
            key = if (keepKey) key else "",
            fullUrl = if (combined) "${presetServer.trimEnd('/')}/stream-key" else "",
            localServer = false,
        )
    }

    fun validate() {
        if (localServer) {
            require(streamPath.matches(Regex("[A-Za-z0-9._~-]{1,48}"))) {
                "Stream name must use 1–48 letters, numbers, dots, dashes, or underscores"
            }
        }
        Addresses.source(source)
        Addresses.output(output())
        require(localServer || combined || key.isNotBlank()) { "Enter a stream key" }
    }

    fun localViewerUrl(wifiAddress: String?): String? =
        wifiAddress?.takeIf { it.matches(Regex("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")) }
            ?.let { "rtsp://$it:8555/live/${streamPath.trim()}" }

    /** RTSP playback URL for LAN viewers of this RTMP publish path, when its host is private. */
    fun lanViewerUrl(): String? {
        val candidate =
            if (combined) fullUrl.trim()
            else server.trimEnd('/') + "/" + key.trim().ifBlank { "camera-key" }
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
        if (uri.scheme !in listOf("rtmp", "rtsp") || uri.host.isNullOrBlank() || uri.rawPath.isNullOrBlank()) return null
        val host = uri.host
        val octets = host.split('.').mapNotNull(String::toIntOrNull)
        val privateIpv4 =
            octets.size == 4 && octets.all { it in 0..255 } &&
                (octets[0] == 10 ||
                    (octets[0] == 172 && octets[1] in 16..31) ||
                    (octets[0] == 192 && octets[1] == 168))
        if (!privateIpv4 && !host.endsWith(".local", ignoreCase = true)) return null
        if (uri.scheme == "rtsp") return candidate
        return "rtsp://$host:8555${uri.rawPath}" + (uri.rawQuery?.let { "?$it" } ?: "")
    }
}

object ConfigMigrations {
    const val DRONESTREAMX_SERVER = "rtmp://demo.dronestreamx.com:1945/drone/"
    const val LAN_RTMP_SERVER = "rtmp://192.168.137.1:1935/live/"
    const val LAN_RTSP_SERVER = "rtsp://192.168.137.1:8555/live/"
    private const val CURRENT_TWITCH_SERVER = "rtmp://aps30.contribute.live-video.net/app/"

    fun migrateCameraModel(config: Config, cameraModelWasSaved: Boolean): Config {
        if (cameraModelWasSaved) return config
        return when (config.source) {
            CameraProfiles.LEGACY_C12_VISIBLE_RTSP ->
                config.copy(
                    cameraModel = CameraProfiles.SKYDROID_C12,
                    source = CameraProfiles.C12_VISIBLE_RTSP,
                )
            CameraProfiles.LEGACY_C12_THERMAL_RTSP ->
                config.copy(
                    cameraModel = CameraProfiles.SKYDROID_C12,
                    source = CameraProfiles.C12_THERMAL_RTSP,
                )
            else -> config.copy(cameraModel = CameraProfiles.SKYDROID_C12)
        }
    }

    fun migrateLegacyTwitchPreset(config: Config): Config {
        if (config.combined) {
            val destination = runCatching { Addresses.output(config.fullUrl) }.getOrNull()
                ?: return config
            if (
                destination.scheme == "rtmp" && destination.host == "live.twitch.tv" &&
                    Addresses.app(destination) == "app"
            ) {
                val query = destination.rawQuery?.let { "?$it" }.orEmpty()
                return config.copy(
                    fullUrl = "rtmp://aps30.contribute.live-video.net${destination.rawPath}$query"
                )
            }
            return config
        }

        val legacy =
            runCatching { Addresses.output(config.server.trimEnd('/') + "/placeholder") }
                .getOrNull()
        return if (
            legacy?.scheme == "rtmp" && legacy.host == "live.twitch.tv" &&
                Addresses.app(legacy) == "app"
        ) config.copy(server = CURRENT_TWITCH_SERVER) else config
    }
}

object Addresses {
    private fun parse(value: String): URI =
        try {
            URI(value.trim())
        } catch (_: Exception) {
            throw IllegalArgumentException("Malformed URL; encode spaces and special characters")
        }

    fun source(value: String): URI =
        parse(value).also {
            require(
                it.scheme == "rtsp" &&
                    !it.host.isNullOrBlank() &&
                    it.fragment == null &&
                    (it.port == -1 || it.port in 1..65535)
            ) {
                "Invalid RTSP URL"
            }
        }

    fun output(value: String): URI =
        parse(value).also {
            require(
                it.scheme in listOf("rtmp", "rtmps", "rtsp") &&
                    !it.host.isNullOrBlank() &&
                    it.userInfo == null &&
                    it.fragment == null &&
                    (it.port == -1 || it.port in 1..65535) &&
                    it.rawPath.orEmpty().trim('/').contains('/') &&
                    !it.rawPath.orEmpty().endsWith("/")
            ) {
                "Invalid publish URL; use rtmp(s)://server/app/stream-key or rtsp://server:port/app/stream"
            }
        }

    fun viewer(value: String): URI =
        parse(value).also {
            require(
                it.scheme in listOf("http", "https", "rtsp", "rtsps") &&
                    !it.host.isNullOrBlank() &&
                    it.userInfo == null &&
                    it.fragment == null &&
                    (it.port == -1 || it.port in 1..65535)
            ) { "Invalid viewer URL; use an HTTP(S) player/HLS URL or RTSP(S) playback URL" }
        }

    fun app(uri: URI) = uri.rawPath.trimStart('/').substringBeforeLast('/')

    fun playpath(uri: URI) =
        uri.rawPath.substringAfterLast('/') + (uri.rawQuery?.let { "?$it" } ?: "")

    fun tcUrl(uri: URI) = "${uri.scheme}://${uri.rawAuthority}/${app(uri)}"
}

object Commands {
    fun relay(
        c: Config,
        input: String,
        output: String,
        info: VideoInfo,
        encoderName: String? = null,
    ): Array<String> = buildList {
        val dest = Addresses.output(c.output())
        // RTSP is a useful LAN publish target for cameras that emit HEVC. MediaMTX
        // and VLC can carry/decode that stream without converting it to RTMP/H.264.
        val transcode = c.mode == StreamMode.TRANSCODE && dest.scheme != "rtsp"
        addAll(
            listOf(
                "-hide_banner",
                "-nostdin",
                "-loglevel",
                "warning",
                "-stats_period",
                "1",
                "-rtsp_transport",
                "tcp",
                "-timeout",
                "15000000",
            )
        )
        if (c.genpts) addAll(listOf("-fflags", "+genpts"))
        addAll(listOf("-i", input, "-map", "0:v:0", "-an"))
        if (!transcode) addAll(listOf("-c:v", "copy"))
        else {
            addAll(
                listOf(
                    "-c:v",
                    "h264_mediacodec",
                    "-codec_name",
                    requireNotNull(encoderName) { "A hardware encoder must be selected" },
                    "-pix_fmt",
                    "nv12",
                    "-bf",
                    "0",
                    "-b:v",
                    "${c.bitrate}M",
                    "-g",
                    ((if (c.fps > 0) c.fps.toDouble() else info.fps.coerceAtLeast(25.0)) * 2)
                        .toInt()
                        .toString(),
                )
            )
            if (c.resolution != "Original")
                addAll(
                    listOf(
                        "-vf",
                        "scale=${c.resolution.replace('x', ':')}:force_original_aspect_ratio=decrease:force_divisible_by=2",
                    )
                )
            if (c.fps > 0) addAll(listOf("-r", c.fps.toString()))
        }
        if (dest.scheme == "rtsp") {
            addAll(listOf("-rw_timeout", "15000000", "-rtsp_transport", "tcp", "-f", "rtsp", output))
        } else {
            addAll(
                listOf(
                    "-rw_timeout",
                    "15000000",
                    "-rtmp_tcurl",
                    Addresses.tcUrl(dest),
                    "-rtmp_app",
                    Addresses.app(dest),
                    "-rtmp_playpath",
                    Addresses.playpath(dest),
                    "-f",
                    "flv",
                    "-flvflags",
                    "no_duration_filesize",
                    output,
                )
            )
        }
    }
        .toTypedArray()

    fun probe(input: String) =
        arrayOf(
            "-v",
            "quiet",
            "-rtsp_transport",
            "tcp",
            "-analyzeduration",
            "5000000",
            "-probesize",
            "5000000",
            "-timeout",
            "15000000",
            "-read_intervals",
            "%+5",
            "-count_packets",
            "-select_streams",
            "v:0",
            "-show_entries",
            "stream=codec_name,width,height,r_frame_rate,nb_read_packets",
            "-of",
            "json",
            input,
        )
}

/** Network muxers can report size=-1, so RTSP copy progress uses advancing media timestamps/frames. */
object RelayProgress {
    fun hasVideo(
        outputScheme: String,
        bytes: Long,
        previousBytes: Long,
        time: Double,
        frames: Long,
        previousFrames: Long,
    ): Boolean =
        if (outputScheme == "rtsp") time > 0 && frames > previousFrames
        else bytes > previousBytes && time > 0 && frames > previousFrames
}

data class VideoInfo(
    val codec: String = "—",
    val width: Int = 0,
    val height: Int = 0,
    val fps: Double = 0.0,
    val packets: Long = 0,
)

object SafeMessages {
    // Deliberate allowlist: arbitrary native text can contain credentials split across callbacks.
    fun native(message: String): String? {
        val m = message.lowercase()
        return when {
            "option not found" in m || "unrecognized option" in m || "unknown option" in m ->
                "FFmpeg does not recognize a relay option"
            "invalid stream key" in m || "publish.badname" in m ||
                "publish.rejected" in m || ("publish" in m && ("reject" in m || "denied" in m)) ->
                "Publish server rejected the stream; check the destination path and access settings"
            "401" in m || "unauthorized" in m || "authentication" in m ->
                "Authentication rejected; check camera credentials or stream key"
            "403" in m || "forbidden" in m ->
                "Server rejected access; check stream key and account permissions"
            "tls" in m || "ssl" in m || "certificate" in m || "handshake failure" in m ->
                "Secure RTMP handshake failed; check TLS support and network time"
            "timed out" in m || "timeout" in m -> "Connection timeout"
            "resolve" in m || "name or service" in m -> "DNS lookup failed"
            "connection refused" in m -> "Connection refused; check server address and port"
            "network is unreachable" in m || "no route" in m -> "Network unreachable"
            "protocol not found" in m || "protocol is not supported" in m ->
                "This FFmpeg build does not support the selected streaming transport"
            "could not write header" in m || "error writing header" in m ->
                "Publish server rejected the video header or stream settings"
            "not supported" in m || "unsupported" in m || "not compatible" in m ->
                "Unsupported codec or format; check H.264 camera output and compatibility mode"
            "non-monoton" in m || "timestamp" in m ->
                "Timestamp discontinuity; consider Generate timestamps in Advanced"
            "broken pipe" in m || "connection reset" in m -> "Remote connection lost"
            "error" in m || "failed" in m -> "FFmpeg reported a media or connection error"
            else -> null
        }
    }

    /** Keep useful FFmpeg context in Diagnostics while removing all configured secret URLs. */
    fun nativeDetail(message: String, config: Config): String {
        var safe = message
        listOf(config.source, config.output(), config.server, config.key, config.fullUrl)
            .filter(String::isNotBlank)
            .distinct()
            .forEach { value -> safe = safe.replace(value, "[redacted]", ignoreCase = true) }
        safe =
            Regex("(?i)\\b(?:rtsp|rtmp|rtmps)://[^\\s'\"<>]+")
                .replace(safe, "[stream URL]")
        safe = Regex("(?i)\\b(password|pass|stream[_-]?key|token)=([^\\s&]+)")
            .replace(safe, "$1=[redacted]")
        return safe.replace(Regex("\\s+"), " ").trim().take(200)
    }
}
