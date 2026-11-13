package com.garuda.rtsplive

import android.content.Context
import android.net.*
import java.io.*
import java.net.*
import java.security.MessageDigest
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.*
import kotlinx.coroutines.flow.MutableStateFlow

/** Every upstream socket belongs to its chosen Android Network. Never binds the process. */
class Networks(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    val description = MutableStateFlow("Checking networks")
    @Volatile
    var revision = 0L
        private set

    private val cb =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = refresh()

            override fun onLost(network: Network) = refresh()

            override fun onCapabilitiesChanged(network: Network, c: NetworkCapabilities) = refresh()

            override fun onLinkPropertiesChanged(network: Network, l: LinkProperties) = refresh()
        }

    init {
        cm.registerNetworkCallback(
            NetworkRequest.Builder()
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build(),
            cb,
        )
        refresh()
    }

    private fun refresh() {
        revision++
        val androidInterfaces =
            cm.allNetworks.mapNotNull { cm.getLinkProperties(it)?.interfaceName }
        val kernelInterfaces =
            NetworkInterface.getNetworkInterfaces()
                .toList()
                .filter { it.isUp && !it.isLoopback }
                .map { it.name }
        description.value =
            "Internet available: ${if(internet()!=null)"YES" else "NO"} | Interfaces: " +
                (androidInterfaces + kernelInterfaces).distinct().joinToString(", ")
    }

    fun internet(): Network? =
        cm.allNetworks.firstOrNull {
            cm.getNetworkCapabilities(it)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true &&
                it == cm.activeNetwork
        }
            ?: cm.allNetworks.firstOrNull {
                cm.getNetworkCapabilities(it)
                    ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            }

    fun forHost(host: String, camera: Boolean): Network {
        val literal =
            if (host.matches(Regex("[0-9.]+")) || ':' in host)
                runCatching { InetAddress.getByName(host) }.getOrNull()
            else null
        val candidates =
            cm.allNetworks.filter {
                cm.getNetworkCapabilities(it)
                    ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) == true
            }
        if (literal != null) {
            val best =
                candidates
                    .flatMap { n ->
                        cm.getLinkProperties(n)
                            ?.routes
                            .orEmpty()
                            .filter { it.destination.prefixLength > 0 && it.matches(literal) }
                            .map { n to it.destination.prefixLength }
                    }
                    .maxByOrNull { it.second }
            if (best != null) return best.first
        }
        if (camera && literal == null) {
            candidates
                .firstOrNull {
                    val c = cm.getNetworkCapabilities(it)
                    c?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true &&
                        !c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                }
                ?.let {
                    return it
                }
        }
        return internet()
            ?: if (camera) candidates.firstOrNull() ?: throw IOException("network")
            else throw IOException("internet")
    }

    fun connect(host: String, port: Int, camera: Boolean): Socket {
        // Some transmitter vendor links (including the G20 ar_net0 interface)
        // are not published as ConnectivityManager.Network objects. Prefer a
        // directly interface-bound socket for a local camera route before using
        // Android Network.socketFactory.
        if (camera) {
            val target = runCatching { InetAddress.getByName(host) }.getOrNull()
            if (target is Inet4Address) {
                NetworkInterface.getNetworkInterfaces().toList().forEach { nif ->
                    nif.interfaceAddresses
                        .firstOrNull { addr ->
                            val local = addr.address
                            local is Inet4Address &&
                                addr.networkPrefixLength in 1..32 &&
                                sameSubnet(local, target, addr.networkPrefixLength)
                        }
                        ?.let { addr ->
                            val socket = Socket()
                            try {
                                socket.reuseAddress = true
                                socket.tcpNoDelay = true
                                socket.bind(InetSocketAddress(addr.address, 0))
                                socket.connect(InetSocketAddress(target, port), 5000)
                                return socket
                            } catch (_: IOException) {
                                runCatching { socket.close() }
                            }
                        }
                }
            }
        }
        val n = forHost(host, camera)
        val addresses = n.getAllByName(host)
        for (a in addresses) {
            val s = n.socketFactory.createSocket()
            try {
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(a, port), 5000)
                return s
            } catch (_: IOException) {
                s.close()
            }
        }
        throw IOException("Connection failed")
    }

    fun localAddressFor(host: String): String? {
        val target =
            runCatching { InetAddress.getByName(host) }.getOrNull() as? Inet4Address ?: return null
        return NetworkInterface.getNetworkInterfaces()
            .toList()
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.interfaceAddresses.asSequence() }
            .firstOrNull { addr ->
                addr.address is Inet4Address &&
                    addr.networkPrefixLength in 1..32 &&
                    sameSubnet(addr.address as Inet4Address, target, addr.networkPrefixLength)
            }
            ?.address
            ?.hostAddress
    }

    /** Current IPv4 address that viewers on the transmitter's Wi-Fi can use. */
    fun wifiIpv4(): String? {
        val wifiNetwork =
            cm.allNetworks.firstOrNull {
                cm.getNetworkCapabilities(it)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
        val fromConnectivity =
            wifiNetwork?.let { cm.getLinkProperties(it)?.linkAddresses.orEmpty() }
                ?.firstOrNull {
                    it.address is Inet4Address &&
                        !it.address.isLoopbackAddress &&
                        !it.address.isLinkLocalAddress
                }
                ?.address
                ?.hostAddress
        if (!fromConnectivity.isNullOrBlank()) return fromConnectivity

        return NetworkInterface.getByName("wlan0")
            ?.interfaceAddresses
            ?.firstOrNull {
                it.address is Inet4Address &&
                    !it.address.isLoopbackAddress &&
                    !it.address.isLinkLocalAddress
            }
            ?.address
            ?.hostAddress
    }

    private fun sameSubnet(local: Inet4Address, target: Inet4Address, prefix: Short): Boolean {
        val mask = if (prefix.toInt() == 0) 0 else (-1 shl (32 - prefix))
        fun value(a: ByteArray) =
            a.fold(0) { result, byte -> (result shl 8) or (byte.toInt() and 0xff) }
        return value(local.address) and mask == value(target.address) and mask
    }
}

/** Bounded in-memory loopback gateway. RTSP uses interleaved TCP, so RTP is never decoded. */
class RoutedGateway(
    private val uri: URI,
    private val networks: Networks,
    private val camera: Boolean,
    private val onTransportEnd: (String) -> Unit = {},
) : Closeable {
    private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    private val closed = AtomicBoolean(false)
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val executor = Executors.newFixedThreadPool(4)
    private val upstreamAuthority =
        uri.host.let { if (':' in it) "[$it]" else it } + if (uri.port > 0) ":${uri.port}" else ""
    private val localAuthority = "127.0.0.1:${server.localPort}"
    private val localBase = "rtsp://$localAuthority"
    private val remoteBase = "rtsp://$upstreamAuthority"
    private val mappings = ConcurrentHashMap<String, String>()
    val url: String =
        if (camera)
            "rtsp://" +
                (uri.rawUserInfo?.let { "$it@" } ?: "") +
                localAuthority +
                uri.rawPath +
                (uri.rawQuery?.let { "?$it" } ?: "")
        else "rtmp://$localAuthority/${Addresses.app(uri)}/${Addresses.playpath(uri)}"

    init {
        executor.execute {
            try {
                // A gateway is private to one session. Reject extra local connections.
                val client = server.accept()
                sockets.add(client)
                client.tcpNoDelay = true
                if (closed.get()) {
                    client.close()
                    return@execute
                }
                var upstream =
                    networks.connect(
                        uri.host,
                        if (uri.port > 0) uri.port
                        else if (camera) 554 else if (uri.scheme == "rtmps") 443 else 1935,
                        camera,
                    )
                sockets.add(upstream)
                if (!camera && uri.scheme == "rtmps") {
                    val tls =
                        (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(
                            upstream,
                            uri.host,
                            upstream.port,
                            true,
                        ) as SSLSocket
                    tls.sslParameters =
                        tls.sslParameters.apply {
                            endpointIdentificationAlgorithm = "HTTPS"
                            if (!uri.host.matches(Regex("[0-9.]+")) && ':' !in uri.host)
                                serverNames = listOf(SNIHostName(uri.host))
                        }
                    tls.soTimeout = 8000
                    tls.startHandshake()
                    tls.soTimeout = 0
                    upstream = tls
                    sockets.add(tls)
                }
                if (closed.get()) {
                    upstream.close()
                    return@execute
                }
                val out = upstream
                executor.execute { pipe(client, out, true) }
                executor.execute { pipe(out, client, false) }
            } catch (e: Exception) {
                reportTransportEnd("connection setup", e)
                close()
            }
        }
    }

    private fun pipe(from: Socket, to: Socket, request: Boolean) {
        try {
            val input = BufferedInputStream(from.getInputStream(), 32768)
            val output = BufferedOutputStream(to.getOutputStream(), 32768)
            if (camera) while (!closed.get()) transferRtsp(input, output, request)
            else {
                val b = ByteArray(32768)
                while (!closed.get()) {
                    val n = input.read(b)
                    if (n < 0) break
                    output.write(b, 0, n)
                    output.flush()
                }
            }
        } catch (e: Exception) {
            reportTransportEnd(if (request) "RTSP control" else "camera media", e)
        } finally {
            close()
        }
    }

    private fun reportTransportEnd(channel: String, error: Exception) {
        if (!closed.get() && camera) {
            val reason =
                when (error) {
                    is EOFException -> "EOF"
                    is SocketTimeoutException -> "timeout"
                    is SocketException -> "socket closed"
                    else ->
                        error.javaClass.simpleName +
                            (error.message?.takeIf(String::isNotBlank)?.let { ": ${it.take(120)}" } ?: "")
                }
            onTransportEnd("Camera RTSP $channel ended ($reason)")
        }
    }

    private fun transferRtsp(input: InputStream, output: OutputStream, request: Boolean) {
        val first = input.read()
        if (first < 0) throw EOFException()
        if (first == 36) {
            val ch = input.read()
            val hi = input.read()
            val lo = input.read()
            if (ch < 0 || hi < 0 || lo < 0) throw EOFException("Truncated interleaved RTP header")
            val data = readBytes(input, (hi shl 8) or lo)
            output.write(byteArrayOf(36, ch.toByte(), hi.toByte(), lo.toByte()))
            output.write(data)
            output.flush()
            return
        }
        val header = ByteArrayOutputStream()
        header.write(first)
        val tail = IntArray(4)
        var tailCount = 1
        tail[0] = first
        while (
            !(tailCount == 4 &&
                tail[0] == 0x0d &&
                tail[1] == 0x0a &&
                tail[2] == 0x0d &&
                tail[3] == 0x0a)
        ) {
            val b = input.read()
            if (b < 0) throw EOFException()
            header.write(b)
            require(header.size() <= 65536) { "RTSP header exceeds 64 KiB" }
            if (tailCount < 4) tail[tailCount++] = b
            else {
                tail[0] = tail[1]
                tail[1] = tail[2]
                tail[2] = tail[3]
                tail[3] = b
            }
        }
        var h = header.toString("ISO-8859-1")
        val length =
            Regex("(?im)^Content-Length:\\s*(\\d+)").find(h)?.groupValues?.get(1)?.toInt() ?: 0
        require(length in 0..1048576) { "Invalid RTSP message body length $length" }
        var body = readBytes(input, length)
        if (request) {
            val requestLine = h.substringBefore("\r\n")
            val parts = requestLine.split(' ')
            require(parts.size == 3) { "Malformed RTSP request line" }
            val original = mappings[parts[1]] ?: parts[1].replace(localBase, remoteBase)
            h = h.replaceFirst(requestLine, "${parts[0]} $original ${parts[2]}")
            h = rewriteDigest(h, parts[0], original)
        } else {
            fun localize(s: String): String =
                Regex("rtsp://[^\\s<>\"\\r\\n]+").replace(s) { match ->
                    val original = match.value
                    val remote = runCatching { URI(original) }.getOrNull()
                    if (remote?.host != null) {
                        val local =
                            localBase +
                                remote.rawPath.orEmpty() +
                                (remote.rawQuery?.let { "?$it" } ?: "")
                        mappings[local] = original
                        local
                    } else original
                }
            h = localize(h)
            if (body.isNotEmpty() && h.contains("application/sdp", true)) {
                body = localize(String(body, Charsets.UTF_8)).toByteArray(Charsets.UTF_8)
                h =
                    h.replace(
                        Regex("(?im)^Content-Length:\\s*\\d+"),
                        "Content-Length: ${body.size}",
                    )
            }
        }
        output.write(h.toByteArray(Charsets.ISO_8859_1))
        output.write(body)
        output.flush()
    }

    private fun readBytes(input: InputStream, n: Int): ByteArray {
        val b = ByteArray(n)
        var offset = 0
        while (offset < n) {
            val count = input.read(b, offset, n - offset)
            if (count < 0) throw EOFException()
            offset += count
        }
        return b
    }

    private fun rewriteDigest(header: String, method: String, target: String): String {
        val match = Regex("(?im)^Authorization: Digest ([^\\r\\n]+)").find(header) ?: return header
        val credentials = uri.userInfo?.split(':', limit = 2) ?: return header
        val fields =
            Regex("(\\w+)=(?:\"([^\"]*)\"|([^,\\s]+))")
                .findAll(match.groupValues[1])
                .associate { it.groupValues[1] to it.groupValues[2].ifEmpty { it.groupValues[3] } }
                .toMutableMap()
        val algorithm = fields["algorithm"] ?: "MD5"
        require(algorithm.uppercase() in listOf("MD5", "MD5-SESS", "SHA-256", "SHA-256-SESS"))
        fun hash(s: String) =
            MessageDigest.getInstance(if (algorithm.startsWith("SHA", true)) "SHA-256" else "MD5")
                .digest(s.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        var ha1 = hash("${credentials[0]}:${fields["realm"]}:${credentials.getOrElse(1){""}}")
        if (algorithm.endsWith("-sess", true))
            ha1 = hash("$ha1:${fields["nonce"]}:${fields["cnonce"]}")
        val ha2 = hash("$method:$target")
        val qop = fields["qop"]
        require(qop == null || qop == "auth") { "Unsupported RTSP Digest authentication mode" }
        fields["uri"] = target
        fields["response"] =
            if (qop == null) hash("$ha1:${fields["nonce"]}:$ha2")
            else hash("$ha1:${fields["nonce"]}:${fields["nc"]}:${fields["cnonce"]}:$qop:$ha2")
        val updated =
            "Authorization: Digest " +
                fields.entries.joinToString(", ") { (k, v) ->
                    if (k in listOf("qop", "nc", "algorithm")) "$k=$v" else "$k=\"$v\""
                }
        return header.replaceRange(match.range, updated)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { server.close() }
            sockets.forEach { runCatching { it.close() } }
            executor.shutdownNow()
        }
    }
}
