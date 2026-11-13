package com.garuda.livestream

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.webrtc.SurfaceViewRenderer

class MainActivity : ComponentActivity(), LiveStreamService.Listener {
    private val prefs by lazy { AppPrefs(this) }
    private var service: LiveStreamService? = null
    private var bound = false
    private var rendererReady = false
    private var previewAttached = false
    private lateinit var preview: SurfaceViewRenderer
    private lateinit var rtspUrl: TextInputEditText
    private lateinit var rtspUser: TextInputEditText
    private lateinit var rtspPassword: TextInputEditText
    private lateinit var serverUrl: TextInputEditText
    private lateinit var publisherToken: TextInputEditText
    private lateinit var requirePin: CheckBox
    private lateinit var pinInput: TextInputEditText
    private lateinit var rtspStatus: TextView
    private lateinit var internetStatus: TextView
    private lateinit var liveStatus: TextView
    private lateinit var viewerLink: TextInputEditText
    private lateinit var viewerCount: TextView
    private lateinit var duration: TextView
    private lateinit var stats: TextView
    private lateinit var startButton: MaterialButton
    private lateinit var stopButton: MaterialButton
    private lateinit var copyButton: MaterialButton
    private lateinit var shareButton: MaterialButton
    private var currentState = LiveUiState()
    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockTick = object : Runnable {
        override fun run() {
            duration.text = if (currentState.startedAtMs > 0) elapsed(System.currentTimeMillis() - currentState.startedAtMs) else "00:00:00"
            clockHandler.postDelayed(this, 1_000)
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as LiveStreamService.LocalBinder).service()
            bound = true
            service?.observe(this@MainActivity)
            attachPreview()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.rgb(11, 17, 24)
        window.navigationBarColor = Color.rgb(11, 17, 24)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 801)
        }
        val content = buildUi()
        setContentView(content)
        ViewCompat.setOnApplyWindowInsetsListener(content) { view, windowInsets ->
            val bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(dp(20) + bars.left, dp(12) + bars.top, dp(20) + bars.right, dp(24) + bars.bottom)
            windowInsets
        }
        ViewCompat.requestApplyInsets(content)
        bindService(Intent(this, LiveStreamService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
        clockHandler.post(clockTick)
    }

    private fun buildUi(): View {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(11, 17, 24))
            clipToPadding = false
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(24))
        }
        scroll.addView(root)

        root.addView(label("GARUDA", 13, Color.rgb(87, 217, 163), bold = true), margin(0, 4, 0, 2))
        root.addView(label("LIVE STREAM", 25, Color.WHITE, bold = true), margin(0, 0, 0, 6))
        root.addView(label("Low-latency drone video · end-to-end encrypted media", 13, MUTED), margin(0, 0, 0, 18))

        val rtspCard = card()
        addContent(rtspCard, sectionTitle("RTSP INPUT"))
        rtspUrl = field(rtspCard, "RTSP URL", "rtsp://192.168.144.25:8554/main.264", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI).apply { setText(prefs.lastRtspUrl) }
        rtspUser = field(rtspCard, "Username · optional", "Camera username", InputType.TYPE_CLASS_TEXT)
        rtspPassword = field(rtspCard, "Password · optional", "Camera password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD).apply { setText(prefs.rtspPassword) }
        rtspUser.setText(prefs.rtspUser)
        addContent(rtspCard, label("Credentials are stored encrypted on this device and omitted from the viewer link.", 12, MUTED), margin(0, 2, 0, 12))
        val testButton = button("TEST RTSP", outlined = true)
        testButton.setOnClickListener { testRtsp() }
        addContent(rtspCard, testButton, margin(0, 2, 0, 8))
        rtspStatus = label("● DISCONNECTED", 13, RED, bold = true)
        addContent(rtspCard, rtspStatus)
        root.addView(rtspCard, margin(0, 0, 0, 14))

        val previewCard = card()
        addContent(previewCard, sectionTitle("VIDEO PREVIEW"))
        preview = SurfaceViewRenderer(this).apply { setBackgroundColor(Color.BLACK); setEnableHardwareScaler(true) }
        val previewFrame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        previewFrame.addView(preview, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220)))
        addContent(previewCard, previewFrame, margin(0, 4, 0, 8))
        root.addView(previewCard, margin(0, 0, 0, 14))

        val liveCard = card()
        addContent(liveCard, sectionTitle("INTERNET LIVE"))
        internetStatus = label("● CHECKING INTERNET", 13, MUTED, bold = true)
        addContent(liveCard, internetStatus, margin(0, 0, 0, 8))
        serverUrl = field(liveCard, "Self-hosted signaling URL", "https://live.example.com", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI).apply { setText(prefs.serverUrl) }
        publisherToken = field(liveCard, "Publisher token", "Created on your signaling server", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD).apply { setText(prefs.publisherToken) }
        val accessRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, dp(2)) }
        accessRow.addView(label("VIEWER ACCESS", 11, MUTED, bold = true))
        requirePin = CheckBox(this).apply {
            text = "Require a PIN before viewing"
            setTextColor(Color.WHITE)
            buttonTintList = android.content.res.ColorStateList.valueOf(Color.rgb(87, 217, 163))
        }
        accessRow.addView(requirePin)
        pinInput = field(accessRow, "Viewer PIN · 4–12 characters", "Choose a PIN", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        pinInput.visibility = View.GONE
        requirePin.setOnCheckedChangeListener { _, checked -> pinInput.visibility = if (checked) View.VISIBLE else View.GONE }
        addContent(liveCard, accessRow, margin(0, 0, 0, 10))
        startButton = button("START INTERNET LIVE")
        startButton.setOnClickListener { startLive() }
        addContent(liveCard, startButton, margin(0, 0, 0, 8))
        liveStatus = label("NOT STREAMING", 13, MUTED, bold = true)
        addContent(liveCard, liveStatus, margin(0, 4, 0, 12))
        viewerLink = field(liveCard, "Viewer link", "Generated when live starts", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI).apply { isFocusable = false; isClickable = true }
        val shareActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        copyButton = button("COPY LINK", outlined = true)
        shareButton = button("SHARE LINK", outlined = true)
        copyButton.setOnClickListener { copyLink() }
        shareButton.setOnClickListener { shareLink() }
        shareActions.addView(copyButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(6) })
        shareActions.addView(shareButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(6) })
        addContent(liveCard, shareActions, margin(0, 4, 0, 12))
        val viewersRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        viewerCount = label("VIEWERS  0", 13, Color.WHITE, bold = true)
        duration = label("00:00:00", 13, Color.WHITE, bold = true)
        viewersRow.addView(viewerCount, LinearLayout.LayoutParams(0, dp(28), 1f))
        viewersRow.addView(duration, LinearLayout.LayoutParams(0, dp(28), 1f).apply { gravity = Gravity.END })
        addContent(liveCard, viewersRow)
        stopButton = button("STOP LIVE", outlined = true).apply { setOnClickListener { service?.stopLive() }; isEnabled = false }
        addContent(liveCard, stopButton, margin(0, 4, 0, 2))
        root.addView(liveCard, margin(0, 0, 0, 14))

        val statsCard = card()
        addContent(statsCard, sectionTitle("CONNECTION STATISTICS"))
        stats = label("RTSP FPS       —\nRESOLUTION      —\nRTSP BITRATE    —\nUPLOAD          —\nWEBRTC          —\nPACKET LOSS     —\nROUND TRIP      —", 12, Color.rgb(211, 220, 228))
        stats.typeface = android.graphics.Typeface.MONOSPACE
        addContent(statsCard, stats, margin(0, 2, 0, 0))
        root.addView(statsCard, margin(0, 0, 0, 8))
        root.addView(label("Video is sent directly between devices when NAT allows. The signaling server never receives or stores video.", 12, MUTED), margin(dp(4), 0, dp(4), 8))
        return scroll
    }

    private fun testRtsp() {
        val address = runCatching { RtspAddressBuilder.build(rtspUrl.text?.toString().orEmpty(), rtspUser.text?.toString().orEmpty(), rtspPassword.text?.toString().orEmpty()) }
            .getOrElse { Toast.makeText(this, it.message ?: "Enter a valid RTSP URL", Toast.LENGTH_LONG).show(); return }
        prefs.rtspUser = address.username
        prefs.rtspPassword = address.password
        val route = RtspNetwork.select(this, address.host)
        service?.testRtsp(address.mediaUri, address.safeUrlToRemember, address.host, route)
    }

    private fun startLive() {
        val pin = pinInput.text?.toString().orEmpty()
        if (requirePin.isChecked && pin.length !in 4..12) {
            Toast.makeText(this, "Choose a viewer PIN from 4 to 12 characters.", Toast.LENGTH_LONG).show()
            return
        }
        val server = serverUrl.text?.toString().orEmpty().trimEnd('/')
        val serverUri = runCatching { java.net.URI(server) }.getOrNull()
        val localHttp = BuildConfig.DEBUG && serverUri?.scheme == "http" && serverUri.host in setOf("localhost", "127.0.0.1", "10.0.2.2")
        if ((serverUri?.scheme != "https" && !localHttp) || serverUri?.host.isNullOrBlank() || serverUri?.rawUserInfo != null || serverUri?.rawQuery != null || serverUri?.rawFragment != null || !serverUri?.path.orEmpty().let { it.isEmpty() || it == "/" }) {
            Toast.makeText(this, "Use an HTTPS signaling server URL.", Toast.LENGTH_LONG).show()
            return
        }
        prefs.serverUrl = server
        prefs.publisherToken = publisherToken.text?.toString().orEmpty()
        service?.startLive(prefs.serverUrl, prefs.publisherToken, if (requirePin.isChecked) pin else null)
    }

    private fun attachPreview() {
        val videoTrack = service?.videoTrack() ?: return
        if (!rendererReady) {
            val eglContext = service?.eglContext() ?: return
            preview.init(eglContext, null)
            preview.setScalingType(org.webrtc.RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            rendererReady = true
        }
        videoTrack.removeSink(preview)
        videoTrack.addSink(preview)
        previewAttached = true
    }

    override fun onState(state: LiveUiState) {
        currentState = state
        rtspStatus.text = if (state.rtspConnected) "● CONNECTED" else "● ${state.rtspMessage.uppercase()}"
        rtspStatus.setTextColor(if (state.rtspConnected) GREEN else RED)
        internetStatus.text = if (state.internetConnected) "● INTERNET CONNECTED" else "● INTERNET CONNECTION LOST"
        internetStatus.setTextColor(if (state.internetConnected) GREEN else RED)
        liveStatus.text = if (state.liveMessage.startsWith("LIVE")) "● ${state.liveMessage.uppercase()}" else state.liveMessage.uppercase()
        liveStatus.setTextColor(if (state.liveMessage.startsWith("LIVE")) GREEN else if (state.live) AMBER else MUTED)
        viewerLink.setText(state.viewerUrl)
        viewerCount.text = "VIEWERS  ${state.viewerCount}"
        startButton.isEnabled = !state.live && state.rtspConnected
        stopButton.isEnabled = state.live
        copyButton.isEnabled = state.viewerUrl.isNotBlank()
        shareButton.isEnabled = state.viewerUrl.isNotBlank()
        val s = state.stats
        stats.text = "RTSP FPS       ${s.fps}\nRESOLUTION      ${if (s.width > 0) "${s.width} × ${s.height}" else "—"}\nRTSP RX EST.    ${s.rtspKbps} kbps\nUPLOAD EST.     ${s.uploadKbps} kbps\nWEBRTC          ${s.peerState}\nPACKET LOSS     ${s.packetLoss}\nROUND TRIP      ${s.rttMs} ms"
        duration.text = if (state.startedAtMs > 0) elapsed(System.currentTimeMillis() - state.startedAtMs) else "00:00:00"
    }

    private fun copyLink() {
        val link = currentState.viewerUrl
        if (link.isBlank()) return
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Garuda Live viewer link", link))
        Toast.makeText(this, "Viewer link copied", Toast.LENGTH_SHORT).show()
    }

    private fun shareLink() {
        val link = currentState.viewerUrl
        if (link.isBlank()) return
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link), "Share live viewer link"))
    }

    override fun onStart() {
        super.onStart()
        if (bound) attachPreview()
    }

    override fun onStop() {
        if (previewAttached) service?.videoTrack()?.removeSink(preview)
        previewAttached = false
        super.onStop()
    }

    override fun onDestroy() {
        clockHandler.removeCallbacksAndMessages(null)
        service?.unobserve(this)
        if (bound) unbindService(serviceConnection)
        if (rendererReady) preview.release()
        super.onDestroy()
    }

    private fun card() = MaterialCardView(this).apply {
        radius = dp(18).toFloat()
        cardElevation = 0f
        setCardBackgroundColor(Color.rgb(20, 29, 39))
        strokeWidth = dp(1)
        strokeColor = Color.rgb(37, 50, 64)
        val inside = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16)) }
        addView(inside)
        tag = inside
    }

    private fun sectionTitle(text: String) = label(text, 12, Color.rgb(151, 170, 186), bold = true).apply { letterSpacing = 0.12f }

    private fun addContent(card: MaterialCardView, view: View, params: ViewGroup.LayoutParams = margin(0, 0, 0, 6)) {
        (card.tag as LinearLayout).addView(view, params)
    }

    private fun field(parent: View, label: String, hint: String, inputType: Int): TextInputEditText {
        val container = parent as? MaterialCardView
        val layout = TextInputLayout(this, null, com.google.android.material.R.attr.textInputStyle).apply {
            this.hint = label
            placeholderText = hint
            isExpandedHintEnabled = false
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_FILLED
            boxBackgroundColor = Color.rgb(14, 21, 29)
            setBoxStrokeColor(Color.rgb(65, 82, 99))
            setHintTextColor(android.content.res.ColorStateList.valueOf(MUTED))
            setDefaultHintTextColor(android.content.res.ColorStateList.valueOf(MUTED))
            layoutParams = margin(0, 0, 0, 10)
        }
        val input = TextInputEditText(this).apply {
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(105, 122, 138))
            minimumHeight = dp(56)
            background = null
            setPadding(dp(12), dp(20), dp(12), dp(6))
            this.inputType = inputType
            textSize = 14f
            maxLines = 1
            setSingleLine(true)
        }
        layout.addView(input)
        when (parent) {
            is MaterialCardView -> (parent.tag as LinearLayout).addView(layout)
            is LinearLayout -> parent.addView(layout)
        }
        return input
    }

    private fun label(text: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size.toFloat()
        setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun button(text: String, outlined: Boolean = false) = MaterialButton(this, null, if (outlined) com.google.android.material.R.attr.materialButtonOutlinedStyle else com.google.android.material.R.attr.materialButtonStyle).apply {
        this.text = text
        textSize = 13f
        letterSpacing = 0.04f
        isAllCaps = false
        setTextColor(if (outlined) Color.rgb(87, 217, 163) else Color.rgb(9, 24, 19))
        if (!outlined) backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(87, 217, 163))
        else strokeColor = android.content.res.ColorStateList.valueOf(Color.rgb(87, 217, 163))
        minimumHeight = dp(48)
    }

    private fun margin(left: Int, top: Int, right: Int, bottom: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { setMargins(left, top, right, bottom) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun elapsed(millis: Long): String {
        val seconds = millis.coerceAtLeast(0) / 1000
        return "%02d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
    }

    companion object {
        private val GREEN = Color.rgb(87, 217, 163)
        private val RED = Color.rgb(255, 112, 112)
        private val AMBER = Color.rgb(255, 190, 95)
        private val MUTED = Color.rgb(151, 170, 186)
    }
}
