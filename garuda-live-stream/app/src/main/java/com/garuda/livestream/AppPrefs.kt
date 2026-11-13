package com.garuda.livestream

import android.content.Context
import com.garuda.livestream.security.SecureStore

class AppPrefs(context: Context) {
    private val plain = context.getSharedPreferences("garuda_live_v1", Context.MODE_PRIVATE)
    private val secure = SecureStore(context)

    var lastRtspUrl: String
        get() {
            secure.get("last_rtsp_url")?.let { return it }
            val legacy = plain.getString("last_rtsp_url", null) ?: return ""
            // URL query strings may contain camera tokens, so the whole URL belongs in encrypted storage.
            lastRtspUrl = legacy
            return secure.get("last_rtsp_url").orEmpty()
        }
        set(value) {
            if (value.isEmpty()) {
                secure.put("last_rtsp_url", "")
            } else {
                val address = RtspAddressBuilder.build(value, "", "")
                if (address.username.isNotEmpty() || address.password.isNotEmpty()) {
                    rtspUser = address.username
                    rtspPassword = address.password
                }
                secure.put("last_rtsp_url", address.safeUrlToRemember)
            }
            plain.edit().remove("last_rtsp_url").apply()
        }
    var rtspUser: String
        get() = secure.get("rtsp_user") ?: ""
        set(value) = secure.put("rtsp_user", value)
    var rtspPassword: String
        get() = secure.get("rtsp_password") ?: ""
        set(value) = secure.put("rtsp_password", value)
    var publisherToken: String
        get() = secure.get("publisher_token") ?: ""
        set(value) = secure.put("publisher_token", value)
    var serverUrl: String
        get() = plain.getString("server_url", "") ?: ""
        set(value) { plain.edit().putString("server_url", value.trimEnd('/')).apply() }
}
