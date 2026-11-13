package com.garuda.livestream

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class LiveSession(val id: String, val viewerUrl: String, val iceServersJson: String, val pinRequired: Boolean)

class SignalingApi {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).callTimeout(15, TimeUnit.SECONDS).build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    fun createSession(server: String, token: String, pin: String?): LiveSession {
        val body = JSONObject().apply { if (!pin.isNullOrBlank()) put("pin", pin) }
            .toString().toRequestBody(jsonType)
        val request = Request.Builder().url("${server.trimEnd('/')}/api/sessions")
            .header("Authorization", "Bearer $token").post(body).build()
        client.newCall(request).execute().use { response ->
            val payload = JSONObject(response.body?.string().orEmpty())
            if (!response.isSuccessful) throw IOException(payload.optString("error", "Signaling server rejected the request (${response.code})."))
            return LiveSession(
                payload.getString("id"), payload.getString("viewerUrl"),
                payload.getJSONArray("iceServers").toString(), payload.optBoolean("pinRequired")
            )
        }
    }

    fun stopSession(server: String, token: String, id: String) {
        val request = Request.Builder().url("${server.trimEnd('/')}/api/sessions/$id")
            .header("Authorization", "Bearer $token").delete().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful && response.code != 404) throw IOException("Could not stop live session (${response.code}).")
        }
    }
}

/** WSS transports signaling only. SDP and ICE move through this service; media remains direct
 * WebRTC traffic unless the endpoints need the optional coturn relay. */
class PublisherChannel(
    server: String,
    private val sessionId: String,
    private val token: String,
    private val onMessage: (JSONObject) -> Unit,
    private val onState: (connected: Boolean, message: String) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).pingInterval(20, TimeUnit.SECONDS).build()
    private val retry = Executors.newSingleThreadScheduledExecutor()
    private val stopped = AtomicBoolean(false)
    private val baseUrl = server.trimEnd('/')
    @Volatile private var socket: WebSocket? = null
    @Volatile private var attempt = 0

    init { connect() }

    @Synchronized private fun connect() {
        if (stopped.get()) return
        val base = baseUrl.toHttpUrl()
        val url = base.newBuilder().encodedPath("/ws")
            .query(null).addQueryParameter("session", sessionId).addQueryParameter("role", "publisher").build()
        socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                attempt = 0
                webSocket.send(JSONObject().put("type", "publisher_auth").put("token", token).toString())
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { JSONObject(text) }.onSuccess { message ->
                    main.post {
                        if (!stopped.get() && socket === webSocket) {
                            if (message.optString("type") == "authenticated") onState(true, "Signaling connected")
                            onMessage(message)
                        }
                    }
                }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (stopped.get() || socket !== webSocket) return
                if (code in setOf(1000, 4001, 4003, 4004, 4008)) {
                    socket = null
                    main.post { if (!stopped.get()) onMessage(JSONObject().put("type", "session_ended")) }
                } else disconnected(webSocket)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (stopped.get()) return
                if (response?.code in setOf(401, 403, 404, 410)) {
                    main.post { if (!stopped.get()) onMessage(JSONObject().put("type", "session_ended")) }
                } else disconnected(webSocket)
            }
        })
    }

    private fun disconnected(webSocket: WebSocket) {
        if (socket !== webSocket) return
        socket = null
        if (stopped.get()) return
        onState(false, "Internet connection lost · reconnecting")
        val delay = (2L + attempt.coerceAtMost(5)).coerceAtMost(7L)
        attempt++
        runCatching { retry.schedule({ connect() }, delay, TimeUnit.SECONDS) }
    }

    fun send(message: JSONObject) { socket?.send(message.toString()) }

    fun close(revoke: Boolean = false) {
        if (!stopped.compareAndSet(false, true)) return
        if (revoke) socket?.send(JSONObject().put("type", "publisher_stop").toString())
        socket?.close(1000, "Live stopped")
        val old = socket
        main.postDelayed({ old?.cancel() }, 2000)
        retry.shutdownNow()
        client.connectionPool.evictAll()
    }
}
