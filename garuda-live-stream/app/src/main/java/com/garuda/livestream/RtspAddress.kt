package com.garuda.livestream

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

data class RtspAddress(
    val mediaUri: String,
    val safeUrlToRemember: String,
    val host: String,
    val username: String = "",
    val password: String = ""
) {
    // Avoid accidentally exposing authentication or query tokens through logging.
    override fun toString() = "RtspAddress(host=$host)"
}

object RtspAddressBuilder {
    fun build(input: String, username: String, password: String): RtspAddress {
        val uri = try {
            URI(input.trim())
        } catch (_: java.net.URISyntaxException) {
            // URISyntaxException.message includes the complete input, including secrets.
            throw IllegalArgumentException("Enter a valid RTSP URL. Encode spaces and special characters in the URL.")
        }
        require(uri.scheme.equals("rtsp", ignoreCase = true)) { "Use an rtsp:// URL." }
        require(!uri.host.isNullOrBlank()) { "The RTSP URL needs a host name or IP address." }
        require(uri.port == -1 || uri.port in 1..65535) { "The RTSP port must be between 1 and 65535." }
        require(uri.rawFragment == null) { "RTSP URLs cannot contain a fragment. Encode # as %23 when it is part of a password or path." }
        val authority = uri.rawAuthority ?: error("The RTSP URL needs a host name or IP address.")
        val hostAuthority = authority.substringAfterLast('@')
        val embedded = uri.rawUserInfo.orEmpty()
        // Explicit fields form one credential pair: never combine a new user with an old URL password.
        // URL user info is percent-encoded; '+' is a literal plus, unlike form URL encoding.
        val useEmbedded = username.isEmpty() && password.isEmpty()
        val user = if (useEmbedded) decode(embedded.substringBefore(':')) else username
        val pass = if (useEmbedded) decode(embedded.substringAfter(':', "")) else password
        require(':' !in user) { "RTSP usernames cannot contain a colon." }
        require((user + pass).none { it < ' ' || it == '\u007f' }) { "RTSP credentials cannot contain control characters." }
        val safe = "rtsp://$hostAuthority${uri.rawPath.orEmpty()}" + uri.rawQuery?.let { "?$it" }.orEmpty()
        val auth = if (user.isNotEmpty() || pass.isNotEmpty())
            "${encode(user)}:${encode(pass)}@" else ""
        val media = "rtsp://$auth$hostAuthority${uri.rawPath.orEmpty()}" + uri.rawQuery?.let { "?$it" }.orEmpty()
        return RtspAddress(media, safe, uri.host.removeSurrounding("[", "]").replace("%25", "%"), user, pass)
    }

    private fun decode(value: String) = URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
        .replace("+", "%20").replace("%7E", "~")
}
