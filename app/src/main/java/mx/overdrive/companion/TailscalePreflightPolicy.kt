package mx.overdrive.companion

/** A VPN status check is only an advisory, never proof of Tailscale connectivity. */
internal object TailscalePreflightPolicy {
    fun shouldWarn(provider: ConnectionUrl.Provider?, vpnActive: Boolean?): Boolean =
        provider == ConnectionUrl.Provider.TAILSCALE && vpnActive == false

    fun shouldAutoRetry(provider: ConnectionUrl.Provider?, vpnActive: Boolean?): Boolean =
        provider == ConnectionUrl.Provider.TAILSCALE && vpnActive == true
}
