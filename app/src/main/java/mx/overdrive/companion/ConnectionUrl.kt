package mx.overdrive.companion

import java.net.URI
import java.util.Locale

/** Network policy: HTTPS everywhere, HTTP only to literal private IPv4 / Tailscale CGNAT addresses.
 * No parsing via WebView / android.net.Uri, so this can also be unit tested on the JVM.
 */
internal object ConnectionUrl {
    enum class Provider { CLOUDFLARE, ZROK, TAILSCALE }

    fun validate(raw: String, provider: Provider): String? {
        val value = raw.trim()
        if (value.length !in 10..2048 || value.any { it.isWhitespace() || it.isISOControl() }) return null
        val uri = try { URI(value) } catch (_: Exception) { return null }
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme != "https" && scheme != "http") return null
        if (uri.isOpaque || uri.rawUserInfo != null || uri.host.isNullOrBlank()) return null
        if (uri.port !in -1..65535 || uri.port == 0) return null
        if (value.contains("\\")) return null
        val host = uri.host.lowercase(Locale.ROOT)
        if (host == "localhost" || host.endsWith(".localhost") || host == "0.0.0.0" ||
            host == "::1" || host == "[::1]" || host.startsWith("127.")) return null
        // URI accepts bracketed IPv6; HTTPS is fine, HTTP deliberately requires IPv4 literals.
        if (scheme == "http" && (provider != Provider.TAILSCALE || !isPrivateIpv4(host))) return null
        // A malformed or missing authority is not a valid car URL.
        if (uri.rawAuthority.isNullOrBlank()) return null
        return uri.toASCIIString()
    }

    fun sameOrigin(known: String, candidate: String): Boolean {
        val a = try { URI(known) } catch (_: Exception) { return false }
        val b = try { URI(candidate) } catch (_: Exception) { return false }
        return a.scheme.equals(b.scheme, true) && a.host != null &&
            a.host.equals(b.host, true) && effectivePort(a) == effectivePort(b) &&
            b.rawUserInfo == null
    }

    /** For WebView's HTTP subresource interception. */
    fun allowedResource(mainUrl: String, resourceUrl: String): Boolean {
        val resource = try { URI(resourceUrl) } catch (_: Exception) { return false }
        return when (resource.scheme?.lowercase(Locale.ROOT)) {
            "https" -> resource.host != null && resource.rawUserInfo == null
            "http" -> sameOrigin(mainUrl, resourceUrl) && isPrivateIpv4(resource.host ?: "")
            else -> false
        }
    }

    private fun effectivePort(uri: URI): Int = if (uri.port > 0) uri.port else
        if (uri.scheme.equals("https", true)) 443 else 80

    private fun isPrivateIpv4(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size != 4) return false
        val p = parts.map { segment ->
            if (segment.isEmpty() || segment.length > 3 || !segment.all(Char::isDigit) ||
                (segment.length > 1 && segment.startsWith('0'))) return false
            segment.toIntOrNull()?.takeIf { it in 0..255 } ?: return false
        }
        return p[0] == 10 ||
            (p[0] == 172 && p[1] in 16..31) ||
            (p[0] == 192 && p[1] == 168) ||
            (p[0] == 100 && p[1] in 64..127)
    }
}
