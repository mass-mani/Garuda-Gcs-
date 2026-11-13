package com.garuda.rtsplive

import android.Manifest
import android.content.*
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.update

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Credentials must not appear in recents screenshots or screen recordings.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent {
            MaterialTheme(
                colorScheme =
                    darkColorScheme(
                        primary = Color(0xff64e3c0),
                        background = Color(0xff10171e),
                        surface = Color(0xff19252d),
                    )
            ) {
                val repo = (application as GarudaApp).repo
                val vm: LiveViewModel =
                    viewModel(
                        factory =
                            object : ViewModelProvider.Factory {
                                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                                    @Suppress("UNCHECKED_CAST")
                                    return LiveViewModel(repo) as T
                                }
                            }
                    )
                Surface(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    LiveScreen(repo, vm)
                }
            }
        }
    }
}

class LiveViewModel(val repo: Repository) : ViewModel() {
    private var test: Job? = null

    fun test(context: Context) {
        if (test?.isActive == true || repo.state.value.active) return
        val source = repo.config.value.source
        test = viewModelScope.launch {
            repo.state.update {
                it.copy(
                    testing = true,
                    status = "CONNECTING",
                    detail = "Checking RTSP video track",
                )
            }
            try {
                val info = NativeEngine.probe(source, context.applicationContext, repo.networks)
                repo.state.update {
                    it.copy(
                        status = "CAMERA CONNECTED",
                        camera = true,
                        info = info,
                        detail =
                            "RTSP VIDEO READY — ${info.codec.uppercase()} ${info.width}×${info.height}",
                    )
                }
                repo.log("RTSP CONNECTION SUCCESSFUL: ${info.codec} ${info.width}x${info.height}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: MediaFailure) {
                repo.state.update { it.copy(status = "ERROR", camera = false, detail = e.reason) }
                repo.log(e.reason)
            } catch (_: Exception) {
                repo.state.update {
                    it.copy(
                        status = "ERROR",
                        camera = false,
                        detail = "Invalid RTSP URL or camera unreachable",
                    )
                }
            } finally {
                repo.state.update { it.copy(testing = false) }
            }
        }
    }
}

@Composable
fun LiveScreen(repo: Repository, vm: LiveViewModel) {
    val c by repo.config.collectAsStateWithLifecycle()
    val state by repo.state.collectAsStateWithLifecycle()
    val loaded by repo.loaded.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var page by rememberSaveable { mutableStateOf("Live") }
    var preview by rememberSaveable { mutableStateOf(false) }
    var keyVisible by remember { mutableStateOf(false) }
    var consent by remember { mutableStateOf(false) }
    var cameraMenuExpanded by rememberSaveable { mutableStateOf(false) }
    val notificationPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    fun start() {
        // SIYI camera firmware commonly permits only one RTSP client. Release
        // the preview before the relay takes ownership of the camera socket.
        preview = false
        try {
            c.validate()
            if (Build.VERSION.SDK_INT >= 33)
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            ContextCompat.startForegroundService(
                context,
                Intent(context, StreamService::class.java).setAction(StreamService.START),
            )
        } catch (_: Exception) {
            repo.state.update {
                it.copy(status = "ERROR", detail = "Check the camera URL and publish destination settings")
            }
        }
    }
    fun selectDestination(server: String) {
        repo.update(c.withDestinationPreset(server).copy(localServer = false))
    }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(R.drawable.ic_garuda_logo),
                    contentDescription = "Garuda logo",
                    modifier = Modifier.size(48.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        "GARUDA STREAM",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text("RTSP → LIVE STREAM", color = MaterialTheme.colorScheme.primary)
                }
            }
            Row {
                listOf("Live", "Advanced", "Diagnostics").forEach {
                    TextButton(onClick = { page = it }) { Text(it) }
                }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 10.dp))
        if (!loaded) {
            CircularProgressIndicator()
            return@Column
        }
        when (page) {
            "Advanced" ->
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Streaming mode", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "AUTO starts with stream copy. Transcoding requires your explicit selection and a supported hardware encoder."
                    )
                    Row {
                        StreamMode.entries.forEach { m ->
                            FilterChip(
                                selected = c.mode == m,
                                onClick = {
                                    if (!state.active) {
                                        if (m == StreamMode.TRANSCODE) consent = true
                                        else repo.update(c.copy(mode = m))
                                    }
                                },
                                label = { Text(m.name) },
                                modifier = Modifier.padding(end = 8.dp),
                            )
                        }
                    }
                    Text("Transcode resolution")
                    Choices(
                        listOf("Original", "1920x1080", "1280x720"),
                        c.resolution,
                        !state.active,
                    ) {
                        repo.update(c.copy(resolution = it))
                    }
                    Text("Transcode FPS")
                    Choices(
                        listOf("Original", "30", "25"),
                        if (c.fps == 0) "Original" else c.fps.toString(),
                        !state.active,
                    ) {
                        repo.update(c.copy(fps = it.toIntOrNull() ?: 0))
                    }
                    Text("Transcode bitrate (Mbps)")
                    Choices(listOf("2", "3", "4", "6"), c.bitrate.toString(), !state.active) {
                        repo.update(c.copy(bitrate = it.toInt()))
                    }
                    Text("Keyframe interval: 2 seconds. No audio is transmitted.")
                    Row {
                        Checkbox(
                            checked = c.genpts,
                            onCheckedChange = { repo.update(c.copy(genpts = it)) },
                            enabled = !state.active,
                        )
                        Text(
                            "Generate missing timestamps (+genpts). Enable only to resolve timestamp problems.",
                            Modifier.padding(top = 12.dp),
                        )
                    }
                }
            "Diagnostics" -> {
                val logs by repo.logs.collectAsStateWithLifecycle()
                Row {
                    Button(
                        onClick = {
                            context
                                .getSystemService(android.content.ClipboardManager::class.java)
                                .setPrimaryClip(
                                    ClipData.newPlainText(
                                        "Garuda Stream diagnostics",
                                        logs.joinToString("\n"),
                                    )
                                )
                        }
                    ) {
                        Text("COPY LOG")
                    }
                    TextButton(onClick = { repo.logs.value = emptyList() }) { Text("CLEAR LOG") }
                }
                Text(state.network, Modifier.padding(vertical = 8.dp))
                Text("RTSP camera reachable: ${if(state.camera)"YES" else "NO / not tested"}")
                Text(
                    "Bounded, sanitized session messages. Raw URLs and keys are never included.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 12.dp)
                ) {
                    logs.forEach {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = 3.dp),
                        )
                    }
                }
            }
            else ->
                BoxWithConstraints(Modifier.weight(1f)) {
                    val wide = maxWidth > 700.dp
                    val sourcePanel: @Composable () -> Unit = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "01  RTSP INPUT",
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                            )
                            Box(Modifier.fillMaxWidth()) {
                                OutlinedButton(
                                    onClick = { cameraMenuExpanded = true },
                                    enabled = !state.active && !state.testing,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text("CAMERA MODEL  ·  ${c.cameraModel}", maxLines = 1)
                                        Text("▼")
                                    }
                                }
                                DropdownMenu(
                                    expanded = cameraMenuExpanded,
                                    onDismissRequest = { cameraMenuExpanded = false },
                                    modifier = Modifier.heightIn(max = 480.dp),
                                ) {
                                    CameraProfiles.models.forEach { model ->
                                        DropdownMenuItem(
                                            text = { Text(model) },
                                            onClick = {
                                                cameraMenuExpanded = false
                                                repo.update(c.copy(cameraModel = model))
                                            },
                                            enabled = !state.active && !state.testing,
                                        )
                                    }
                                }
                            }
                            OutlinedTextField(
                                value = c.source,
                                onValueChange = { repo.update(c.copy(source = it)) },
                                label = { Text("Camera URL") },
                                singleLine = true,
                                enabled = !state.active && !state.testing,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            if (c.cameraModel == CameraProfiles.SKYDROID_C12) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    OutlinedButton(
                                        onClick = {
                                            repo.update(c.copy(source = CameraProfiles.C12_VISIBLE_RTSP))
                                        },
                                        enabled = !state.active && !state.testing,
                                    ) {
                                        Text("C12 VISIBLE")
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            repo.update(c.copy(source = CameraProfiles.C12_THERMAL_RTSP))
                                        },
                                        enabled = !state.active && !state.testing,
                                    ) {
                                        Text("C12 THERMAL")
                                    }
                                }
                            } else {
                                Text(
                                    "Enter this camera model’s RTSP address below.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Row {
                                Button(
                                    onClick = {
                                        preview = false
                                        vm.test(context)
                                    },
                                    enabled = !state.active && !state.testing,
                                ) {
                                    Text(if (state.testing) "TESTING…" else "TEST RTSP")
                                }
                                TextButton(onClick = { preview = !preview }) {
                                    Text(if (preview) "HIDE PREVIEW" else "SHOW PREVIEW")
                                }
                            }
                            if (preview) Preview(c.source, repo)
                            else
                                Box(
                                    Modifier.fillMaxWidth()
                                        .height(120.dp)
                                        .background(Color(0xff0a1015))
                                        .padding(20.dp)
                                ) {
                                    Text(
                                        "Preview hidden\nRelay continues independently",
                                        color = Color.Gray,
                                    )
                                }
                            OutlinedButton(
                                onClick = {
                                    preview = false
                                    vm.test(context)
                                },
                                enabled = !state.active && !state.testing,
                            ) {
                                Text("CONNECT CAMERA")
                            }
                        }
                    }
                    val destinationPanel: @Composable () -> Unit = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "02  STREAM OUTPUT",
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = c.localServer,
                                    onClick = { if (!state.active) repo.update(c.copy(localServer = true)) },
                                    enabled = !state.active,
                                    label = { Text("TRANSMITTER RTSP") },
                                )
                                FilterChip(
                                    selected = !c.localServer,
                                    onClick = { if (!state.active) repo.update(c.copy(localServer = false)) },
                                    enabled = !state.active,
                                    label = { Text("REMOTE SERVER") },
                                )
                            }
                            if (c.localServer) {
                                Text(
                                    "This transmitter hosts the RTSP stream. No receiver laptop or internet is required. " +
                                        "Viewers on the same Wi-Fi can connect while the stream is running; the router must allow devices to communicate.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                OutlinedTextField(
                                    value = c.streamPath,
                                    onValueChange = { repo.update(c.copy(streamPath = it)) },
                                    label = { Text("Stream name") },
                                    supportingText = { Text("URL path: /live/${c.streamPath}") },
                                    singleLine = true,
                                    enabled = !state.active,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                val configuredViewerUrl = c.localViewerUrl(repo.networks.wifiIpv4())
                                Text("Viewer RTSP URL", fontWeight = FontWeight.SemiBold)
                                Text(
                                    state.viewerUrl.ifBlank {
                                        configuredViewerUrl ?: "Connect the transmitter to Wi-Fi to show its address"
                                    },
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(
                                    if (state.active) "The RTSP server stays available when viewers disconnect."
                                    else "Press START STREAM to open this address and publish the camera feed.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                Row {
                                    FilterChip(
                                        selected = !c.combined,
                                        onClick = {
                                            if (!state.active) repo.update(c.copy(combined = false))
                                        },
                                        label = { Text("Server + key") },
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    FilterChip(
                                        selected = c.combined,
                                        onClick = {
                                            if (!state.active) {
                                                val fullUrl =
                                                    if (c.key.isNotBlank()) c.output()
                                                    else "${c.server.trimEnd('/')}/stream-key"
                                                repo.update(c.copy(combined = true, fullUrl = fullUrl))
                                            }
                                        },
                                        label = { Text("Complete URL") },
                                    )
                                }
                                Row {
                                    TextButton(
                                        onClick = { selectDestination(ConfigMigrations.DRONESTREAMX_SERVER) },
                                        enabled = !state.active,
                                    ) { Text("DRONESTREAMX") }
                                    TextButton(
                                        onClick = { selectDestination("rtmp://aps30.contribute.live-video.net/app/") },
                                        enabled = !state.active,
                                    ) { Text("TWITCH PRESET") }
                                }
                                Row {
                                    TextButton(
                                        onClick = { selectDestination(ConfigMigrations.LAN_RTMP_SERVER) },
                                        enabled = !state.active,
                                    ) { Text("LAN RTMP") }
                                    TextButton(
                                        onClick = {
                                            if (!state.active) repo.update(c.copy(server = "", key = "", fullUrl = "", combined = false))
                                        },
                                        enabled = !state.active,
                                    ) { Text("LIVEPUSH / CUSTOM") }
                                    TextButton(
                                        onClick = {
                                            if (!state.active) repo.update(c.copy(server = ConfigMigrations.LAN_RTSP_SERVER, fullUrl = "", combined = false))
                                        },
                                        enabled = !state.active,
                                    ) { Text("REMOTE RTSP") }
                                }
                                Text(
                                    "For 4G worldwide viewing, enter the RTMP ingest URL and stream key from your cloud provider. Add its viewer/embed URL below; the viewer URL is separate from the publishing credentials.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                OutlinedTextField(
                                    value = c.viewerUrl,
                                    onValueChange = { repo.update(c.copy(viewerUrl = it)) },
                                    label = { Text("Optional viewer / embed URL") },
                                    supportingText = { Text("Paste the provider's HTTPS embed page or direct HLS .m3u8 URL. Do not paste the RTMP publish URL here.") },
                                    singleLine = true,
                                    enabled = !state.active,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                if (c.viewerUrl.isNotBlank())
                                    Text("Viewer URL saved on this transmitter; enter the same playback URL in Garuda Live Viewer on the PC.", color = MaterialTheme.colorScheme.primary)
                                c.lanViewerUrl()?.let { viewerUrl ->
                                    Text("Remote server playback URL: $viewerUrl", color = MaterialTheme.colorScheme.primary)
                                }
                                if (c.combined)
                                    OutlinedTextField(
                                        value = c.fullUrl,
                                        onValueChange = { repo.update(c.copy(fullUrl = it)) },
                                        label = { Text("Complete publish URL (includes stream key)") },
                                        singleLine = true,
                                        enabled = !state.active,
                                        visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                else {
                                    OutlinedTextField(
                                        value = c.server,
                                        onValueChange = { repo.update(c.copy(server = it)) },
                                        label = { Text(if (c.server.trimStart().startsWith("rtsp://")) "RTSP server" else "RTMP server") },
                                        singleLine = true,
                                        enabled = !state.active,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    OutlinedTextField(
                                        value = c.key,
                                        onValueChange = { repo.update(c.copy(key = it)) },
                                        label = { Text("Stream key") },
                                        singleLine = true,
                                        enabled = !state.active,
                                        visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                                TextButton(onClick = { keyVisible = !keyVisible }) {
                                    Text(
                                        when {
                                            c.combined && keyVisible -> "HIDE URL + KEY"
                                            c.combined -> "SHOW URL + KEY"
                                            keyVisible -> "HIDE KEY"
                                            else -> "SHOW KEY"
                                        }
                                    )
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { start() },
                                    enabled = !state.active && !state.testing,
                                    modifier = Modifier.heightIn(min = 52.dp),
                                ) {
                                    Text("START STREAM")
                                }
                                OutlinedButton(
                                    onClick = {
                                        context.startService(
                                            Intent(context, StreamService::class.java)
                                                .setAction(StreamService.STOP)
                                        )
                                    },
                                    enabled = state.active,
                                    modifier = Modifier.heightIn(min = 52.dp),
                                ) {
                                    Text("STOP STREAM")
                                }
                            }
                            Card(Modifier.fillMaxWidth()) {
                                Column(
                                    Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(5.dp),
                                ) {
                                    Text(
                                        "● ${state.status}",
                                        style = MaterialTheme.typography.titleLarge,
                                        color =
                                            if (state.status == "ERROR")
                                                MaterialTheme.colorScheme.error
                                            else MaterialTheme.colorScheme.primary,
                                    )
                                    Text(state.detail)
                                    Text(
                                        "${state.seconds/3600}:${"%02d".format(state.seconds/60%60)}:${"%02d".format(state.seconds%60)}  •  ${if (state.bytes < 0) "—" else "%.0f".format(state.bitrate)} kb/s"
                                    )
                                    Text(
                                        "${state.info.codec.uppercase()}  ${state.info.width} × ${state.info.height}  ${"%.1f".format(state.info.fps)} fps"
                                    )
                                    Text(
                                        "RTSP: ${if(state.camera)"connected" else "not connected"}  |  ${runCatching { Addresses.output(c.output()).scheme.uppercase() }.getOrDefault("OUTPUT")}: ${if(state.destination)"sending" else "not connected"}"
                                    )
                                    Text(
                                        "Reconnects: ${state.retries}  Errors: ${state.errors}  Drops: unavailable",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Text(state.network, style = MaterialTheme.typography.bodySmall)
                                    if (state.compatibility)
                                        TextButton(onClick = { page = "Advanced" }) {
                                            Text("REVIEW COMPATIBILITY SETTINGS")
                                        }
                                }
                            }
                        }
                    }
                    if (wide)
                        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                                sourcePanel()
                            }
                            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                                destinationPanel()
                            }
                        }
                    else
                        Column(
                            Modifier.verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(20.dp),
                        ) {
                            sourcePanel()
                            destinationPanel()
                        }
                }
        }
    }
    if (consent)
        AlertDialog(
            onDismissRequest = { consent = false },
            title = { Text("Enable H.264 transcoding?") },
            text = {
                Text(
                    "This decodes the camera feed and uses MediaCodec encoding when available. It consumes more power and may increase heat and latency. Hardware support is checked before streaming. No software fallback is started automatically."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        repo.update(c.copy(mode = StreamMode.TRANSCODE))
                        consent = false
                    }
                ) {
                    Text("ENABLE")
                }
            },
            dismissButton = { TextButton(onClick = { consent = false }) { Text("CANCEL") } },
        )
}

@Composable
private fun Choices(
    values: List<String>,
    selected: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        values.forEach {
            FilterChip(
                selected = it == selected,
                onClick = { onSelect(it) },
                enabled = enabled,
                label = { Text(it) },
            )
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun Preview(source: String, repo: Repository) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val lifecycle by owner.lifecycle.currentStateFlow.collectAsState()
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    var message by remember { mutableStateOf("Connecting preview…") }
    DisposableEffect(source, lifecycle.isAtLeast(Lifecycle.State.STARTED)) {
        var gateway: RoutedGateway? = null
        var instance: ExoPlayer? = null
        if (lifecycle.isAtLeast(Lifecycle.State.STARTED))
            try {
                gateway = RoutedGateway(Addresses.source(source), repo.networks, true)
                instance =
                    ExoPlayer.Builder(context).build().apply {
                        addListener(
                            object : Player.Listener {
                                override fun onPlayerError(error: PlaybackException) {
                                    message = "Preview unavailable; use TEST RTSP for diagnostics"
                                    repo.log("RTSP preview failed; check camera route and stream settings")
                                }

                                override fun onPlaybackStateChanged(state: Int) {
                                    if (state == Player.STATE_READY) {
                                        message = "Preview • independent camera connection"
                                        repo.log("RTSP preview connected and ready")
                                    }
                                }
                            }
                        )
                        setMediaSource(
                            RtspMediaSource.Factory()
                                .setForceUseRtpTcp(true)
                                .setTimeoutMs(8000)
                                .createMediaSource(MediaItem.fromUri(gateway.url))
                        )
                        volume = 0f
                        prepare()
                        playWhenReady = true
                    }
                player = instance
            } catch (_: Exception) {
                message = "Invalid camera URL or preview initialization failed"
            }
        onDispose {
            player = null
            instance?.release()
            gateway?.close()
        }
    }
    Column {
        AndroidView(
            factory = { PlayerView(it).apply { useController = false } },
            update = { it.player = player },
            modifier = Modifier.fillMaxWidth().height(210.dp),
        )
        Text(message, style = MaterialTheme.typography.bodySmall)
    }
}
