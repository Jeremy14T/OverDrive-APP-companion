package mx.overdrive.companion

fun main() {
    val p = TailscalePreflightPolicy
    check(p.shouldWarn(ConnectionUrl.Provider.TAILSCALE, false))
    check(!p.shouldWarn(ConnectionUrl.Provider.TAILSCALE, true))
    check(!p.shouldWarn(ConnectionUrl.Provider.TAILSCALE, null))
    check(!p.shouldWarn(ConnectionUrl.Provider.ZROK, false))
    check(!p.shouldWarn(ConnectionUrl.Provider.CLOUDFLARE, false))
    check(!p.shouldWarn(null, false))
    check(p.shouldAutoRetry(ConnectionUrl.Provider.TAILSCALE, true))
    check(!p.shouldAutoRetry(ConnectionUrl.Provider.TAILSCALE, false))
    check(!p.shouldAutoRetry(ConnectionUrl.Provider.TAILSCALE, null))
    check(!p.shouldAutoRetry(ConnectionUrl.Provider.ZROK, true))
    println("Tailscale VPN preflight: 10 checks OK")
}
