package mx.overdrive.companion

import org.junit.Assert.*
import org.junit.Test

class ConnectionUrlTest {
    private val cf = ConnectionUrl.Provider.CLOUDFLARE
    private val z = ConnectionUrl.Provider.ZROK
    private val ts = ConnectionUrl.Provider.TAILSCALE

    @Test fun acceptsHttpsWithCustomDomainsAndPaths() {
        assertEquals("https://car.example.com/overview?tab=1", ConnectionUrl.validate(" https://car.example.com/overview?tab=1 ", cf))
        assertNotNull(ConnectionUrl.validate("https://example.share.zrok.io", z))
        assertNotNull(ConnectionUrl.validate("https://my-car.tailnet.ts.net:8443", ts))
    }
    @Test fun allowsPrivateTailscaleHttpOnly() {
        assertNotNull(ConnectionUrl.validate("http://100.80.2.3:8080", ts))
        assertNotNull(ConnectionUrl.validate("http://192.168.1.44:8080", ts))
        assertNotNull(ConnectionUrl.validate("http://172.31.1.44:8080", ts))
        assertNull(ConnectionUrl.validate("http://100.80.2.3:8080", cf))
        assertNull(ConnectionUrl.validate("http://8.8.8.8:8080", ts))
        assertNull(ConnectionUrl.validate("http://car.example.com", ts))
        assertNull(ConnectionUrl.validate("http://100.128.2.3:8080", ts))
    }
    @Test fun blocksBadInputs() {
        for (url in listOf("javascript:alert(1)", "file:///etc/passwd", "https://a.example.com@evil.com", "https://user:pass@car.com", "https://localhost", "https://127.0.0.1", "https://[::1]", "https://", "https://foo.com\\@bar.com", "https://a.com:0", "https://foo.com  ".replace("  ", " xyz"))) {
            assertNull("should reject $url", ConnectionUrl.validate(url, cf))
        }
    }
    @Test fun onlySameOriginMayNavigateInside() {
        assertTrue(ConnectionUrl.sameOrigin("https://car.example.com", "https://car.example.com/dashboard"))
        assertTrue(ConnectionUrl.sameOrigin("https://car.example.com:443", "https://car.example.com/live"))
        assertFalse(ConnectionUrl.sameOrigin("https://car.example.com", "https://evil.example.com"))
        assertFalse(ConnectionUrl.sameOrigin("https://car.example.com", "http://car.example.com"))
        assertFalse(ConnectionUrl.sameOrigin("https://car.example.com:8443", "https://car.example.com"))
    }
    @Test fun blocksThirdPartyHttpResources() {
        val main = "http://100.70.1.2:8080"
        assertTrue(ConnectionUrl.allowedResource(main, "http://100.70.1.2:8080/api"))
        assertFalse(ConnectionUrl.allowedResource(main, "http://10.1.2.3/api"))
        assertFalse(ConnectionUrl.allowedResource(main, "http://example.com/file.js"))
        assertTrue(ConnectionUrl.allowedResource(main, "https://fonts.gstatic.com/file.woff2"))
    }
    @Test fun identifiesRecognizableQrTunnelTypes() {
        assertEquals(cf, ConnectionUrl.suggestProvider("https://demo.trycloudflare.com"))
        assertEquals(z, ConnectionUrl.suggestProvider("https://demo.share.zrok.io"))
        assertEquals(ts, ConnectionUrl.suggestProvider("http://100.64.131.112:8080"))
        assertEquals(ts, ConnectionUrl.suggestProvider("https://vehicle.tailnet.ts.net"))
        assertNull(ConnectionUrl.suggestProvider("https://my-custom-domain.com"))
        assertNull(ConnectionUrl.suggestProvider("http://192.168.1.50:8080"))
        assertNull(ConnectionUrl.suggestProvider("javascript:alert(1)"))
    }
}
