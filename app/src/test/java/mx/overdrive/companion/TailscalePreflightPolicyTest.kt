package mx.overdrive.companion

import org.junit.Assert.*
import org.junit.Test

class TailscalePreflightPolicyTest {
    @Test fun onlyTailscaleWithoutVpnGetsWarning() {
        assertTrue(TailscalePreflightPolicy.shouldWarn(ConnectionUrl.Provider.TAILSCALE, false))
        assertFalse(TailscalePreflightPolicy.shouldWarn(ConnectionUrl.Provider.TAILSCALE, true))
        assertFalse(TailscalePreflightPolicy.shouldWarn(ConnectionUrl.Provider.TAILSCALE, null))
        assertFalse(TailscalePreflightPolicy.shouldWarn(ConnectionUrl.Provider.ZROK, false))
        assertFalse(TailscalePreflightPolicy.shouldWarn(ConnectionUrl.Provider.CLOUDFLARE, false))
        assertFalse(TailscalePreflightPolicy.shouldWarn(null, false))
    }

    @Test fun returnFromTailscaleOnlyRetriesWithVpn() {
        assertTrue(TailscalePreflightPolicy.shouldAutoRetry(ConnectionUrl.Provider.TAILSCALE, true))
        assertFalse(TailscalePreflightPolicy.shouldAutoRetry(ConnectionUrl.Provider.TAILSCALE, false))
        assertFalse(TailscalePreflightPolicy.shouldAutoRetry(ConnectionUrl.Provider.TAILSCALE, null))
        assertFalse(TailscalePreflightPolicy.shouldAutoRetry(ConnectionUrl.Provider.ZROK, true))
    }
}
