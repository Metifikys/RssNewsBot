package metifikys.fetch

import metifikys.config.AppConfig
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Proxy
import java.net.URL
import java.net.UnknownHostException

/**
 * SSRF guard for every outbound request whose URL the bot does not hard-code: feeds, the article
 * pages their items link to (incl. reddit / converted-feed repoints and the long-form refetch),
 * feed autodiscovery and the badge / intro APIs.
 *
 * Rule: only `http`/`https`, and the host must not RESOLVE to a non-public address (loopback,
 * RFC-1918, link-local, CGNAT, unique-local / site-local IPv6, multicast, unspecified — IPv4-mapped,
 * IPv4-compatible, NAT64 and 6to4 IPv6 forms are judged by the IPv4 address they carry). The one
 * exception is a [trustedHosts] entry: the hosts of the operator's own feed URLs (a LAN RSSHub such
 * as `192.168.77.7:1200`) plus `fetcher.privateHostAllowlist`. Trust is per `host:port` for a feed
 * URL, so an article link to another port of the same LAN box is still refused.
 *
 * Two layers:
 * - [check] before a request: parse + resolve + classify, so a LAN link never gets a connection.
 * - [install] on an OkHttp client: a network interceptor re-judges EVERY hop — redirects included —
 *   by the address the connection actually went to, which also closes DNS rebinding between the
 *   pre-check and the connect. `HttpURLConnection` users follow redirects by hand and [check] each hop.
 *
 * A host that does not resolve passes [check]: the request fails on its own, and the JVM's DNS
 * cache hands the connect the same (negative) answer.
 */
class UrlGuard(
    /** `host`, `host:port`, `[v6]:port` or a full URL (its host and effective port). */
    trustedHosts: Collection<String> = emptyList(),
    /** DNS seam for tests; production resolves through the JVM (and its cache). */
    private val resolve: (String) -> Array<InetAddress> = { InetAddress.getAllByName(it) },
    /** False only in tests that talk to a local server ([OFF]). */
    private val enabled: Boolean = true
) {

    /** A trusted host; [port] null = any port (a bare-host allowlist entry). */
    data class Trust(val host: String, val port: Int?)

    private val trusted: Set<Trust> = trustedHosts.map { parseTrustEntry(it) }.toSet()

    /** Raised by the OkHttp interceptor — an [IOException] so the call fails like any transport error. */
    class RejectedUrlException(message: String) : IOException(message)

    fun isTrusted(host: String, port: Int): Boolean {
        val h = normalizeHost(host)
        return trusted.any { it.host == h && (it.port == null || it.port == port) }
    }

    /**
     * Throws [IllegalArgumentException] when [url] is malformed, not http(s), or points at a
     * non-public address that is not trusted.
     */
    fun check(url: String) {
        if (!enabled) return
        val parsed = try {
            URL(url)
        } catch (e: Exception) {
            throw IllegalArgumentException("Malformed URL: $url")
        }
        require(parsed.protocol in ALLOWED_SCHEMES) {
            "Only http/https URLs are allowed (got '${parsed.protocol}'): $url"
        }
        val host = parsed.host?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("URL has no host: $url")
        val port = if (parsed.port == -1) parsed.defaultPort else parsed.port
        checkHost(host, port)
    }

    private fun checkHost(host: String, port: Int) {
        if (isTrusted(host, port)) return
        val addresses = try {
            resolve(normalizeHost(host))
        } catch (e: UnknownHostException) {
            return
        }
        addresses.firstOrNull { isNonPublic(it) }?.let { rejectAddress(host, it) }
    }

    private fun rejectAddress(host: String, address: InetAddress): Nothing =
        throw IllegalArgumentException("Private or loopback hosts are not allowed: $host (${address.hostAddress})")

    /** Adds the per-hop network interceptor (a no-op for [OFF]). Redirects may stay on. */
    fun install(builder: OkHttpClient.Builder): OkHttpClient.Builder =
        if (enabled) builder.addNetworkInterceptor(interceptor) else builder

    private val interceptor = Interceptor { chain ->
        val url = chain.request().url
        if (!isTrusted(url.host, url.port)) {
            // The address this hop is really connected to; through a proxy fall back to resolving.
            val address = chain.connection()?.route()
                ?.takeIf { it.proxy.type() == Proxy.Type.DIRECT }
                ?.socketAddress?.address
            try {
                if (address != null) {
                    if (isNonPublic(address)) rejectAddress(url.host, address)
                } else {
                    check(url.toString())
                }
            } catch (e: IllegalArgumentException) {
                throw RejectedUrlException("${e.message} — refused ${url.redact()}")
            }
        }
        chain.proceed(chain.request())
    }

    companion object {
        private val ALLOWED_SCHEMES = setOf("http", "https")

        /** No checks at all — tests against a local server only. */
        val OFF = UrlGuard(enabled = false)

        /**
         * The production guard: every category's feed hosts (`host:port`) plus
         * `fetcher.privateHostAllowlist` (validated at config load). A feed URL too broken to parse
         * trusts nothing — the fetcher rejects it on its own.
         */
        fun forConfig(config: AppConfig): UrlGuard = UrlGuard(
            config.categories.values.flatMap { c -> c.feeds.map { it.url } }
                .filter { runCatching { parseTrustEntry(it) }.isSuccess } +
                config.fetcher.privateHostAllowlist
        )

        /** Parses one trust entry; throws [IllegalArgumentException] on a malformed one. */
        fun parseTrustEntry(entry: String): Trust {
            val s = entry.trim()
            require(s.isNotEmpty()) { "empty host entry" }
            val (host, port) = when {
                "://" in s -> {
                    val u = try { URL(s) } catch (e: Exception) { throw IllegalArgumentException("malformed URL '$s'") }
                    u.host to (if (u.port == -1) u.defaultPort else u.port)
                }
                s.startsWith("[") -> {
                    val end = s.indexOf(']')
                    require(end > 1) { "malformed IPv6 host '$s'" }
                    val rest = s.substring(end + 1)
                    require(rest.isEmpty() || rest.startsWith(":")) { "malformed host '$s'" }
                    s.substring(1, end) to rest.removePrefix(":").takeIf { it.isNotEmpty() }?.let { parsePort(it, s) }
                }
                s.count { it == ':' } == 1 -> s.substringBefore(':') to parsePort(s.substringAfter(':'), s)
                else -> s to null // a hostname, or a bare IPv6 address
            }
            require(host.isNotBlank() && host.none { it.isWhitespace() || it == '/' }) { "malformed host '$s'" }
            return Trust(normalizeHost(host), port)
        }

        private fun parsePort(p: String, entry: String): Int =
            p.toIntOrNull()?.takeIf { it in 1..65535 } ?: throw IllegalArgumentException("bad port in '$entry'")

        private fun normalizeHost(host: String): String =
            host.trim().removePrefix("[").removeSuffix("]").removeSuffix(".").lowercase()

        /** True for any address a fetch of untrusted origin must never reach. */
        fun isNonPublic(address: InetAddress): Boolean {
            if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
                address.isSiteLocalAddress || address.isMulticastAddress
            ) return true
            val b = address.address
            return when (address) {
                is Inet4Address -> nonPublicV4(b, 0)
                is Inet6Address -> nonPublicV6(b)
                else -> true
            }
        }

        private fun u(b: ByteArray, i: Int) = b[i].toInt() and 0xff

        private fun nonPublicV4(b: ByteArray, o: Int): Boolean {
            val a0 = u(b, o)
            val a1 = u(b, o + 1)
            return a0 == 0 || a0 == 10 || a0 == 127 ||
                (a0 == 100 && a1 in 64..127) ||          // CGNAT 100.64/10
                (a0 == 169 && a1 == 254) ||
                (a0 == 172 && a1 in 16..31) ||
                (a0 == 192 && a1 == 168) ||
                (a0 == 192 && a1 == 0 && u(b, o + 2) == 0) || // IETF protocol assignments
                (a0 == 198 && a1 in 18..19) ||           // benchmarking
                a0 >= 224                                // multicast, reserved, broadcast
        }

        private fun nonPublicV6(b: ByteArray): Boolean {
            val zeroPrefix = (0 until 10).all { b[it].toInt() == 0 }
            return when {
                zeroPrefix && u(b, 10) == 0xff && u(b, 11) == 0xff -> nonPublicV4(b, 12) // ::ffff:a.b.c.d
                zeroPrefix && b[10].toInt() == 0 && b[11].toInt() == 0 -> true         // ::/96 (::, ::1, ::a.b.c.d)
                u(b, 0) == 0x00 && u(b, 1) == 0x64 && u(b, 2) == 0xff && u(b, 3) == 0x9b &&
                    (4 until 12).all { b[it].toInt() == 0 } -> nonPublicV4(b, 12)      // NAT64 64:ff9b::/96
                u(b, 0) == 0x20 && u(b, 1) == 0x02 -> nonPublicV4(b, 2)                // 6to4 2002::/16
                (u(b, 0) and 0xfe) == 0xfc -> true                                     // unique-local fc00::/7
                u(b, 0) == 0xfe && (u(b, 1) and 0xc0) in setOf(0x80, 0xc0) -> true    // fe80::/10, fec0::/10
                u(b, 0) == 0xff -> true                                                // multicast
                else -> false
            }
        }
    }
}
