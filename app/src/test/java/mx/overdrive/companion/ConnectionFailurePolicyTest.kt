package mx.overdrive.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionFailurePolicyTest {
    @Test fun initialLoadDeadlineIsTenSeconds() {
        assertEquals(10_000L, ConnectionFailurePolicy.NAVIGATION_DEADLINE_MS)
    }

    @Test fun onlyTailscaleHasSpecificHelp() {
        assertTrue(ConnectionFailurePolicy.showTailscaleHelp(ConnectionUrl.Provider.TAILSCALE))
        assertFalse(ConnectionFailurePolicy.showTailscaleHelp(ConnectionUrl.Provider.ZROK))
        assertFalse(ConnectionFailurePolicy.showTailscaleHelp(ConnectionUrl.Provider.CLOUDFLARE))
        assertFalse(ConnectionFailurePolicy.showTailscaleHelp(null))
    }
}
