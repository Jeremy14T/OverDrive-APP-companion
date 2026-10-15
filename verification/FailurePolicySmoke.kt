package mx.overdrive.companion

fun main() {
    check(ConnectionFailurePolicy.NAVIGATION_DEADLINE_MS == 10_000L)
    check(ConnectionFailurePolicy.showTailscaleHelp(ConnectionUrl.Provider.TAILSCALE))
    check(!ConnectionFailurePolicy.showTailscaleHelp(ConnectionUrl.Provider.CLOUDFLARE))
    check(!ConnectionFailurePolicy.showTailscaleHelp(ConnectionUrl.Provider.ZROK))
    check(!ConnectionFailurePolicy.showTailscaleHelp(null))
    println("5 checks: deadline/provider-specific messages OK")
}
