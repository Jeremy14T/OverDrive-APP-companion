package mx.overdrive.companion

/** A deadline from the actual load request, not from a WebView callback. */
internal object ConnectionFailurePolicy {
    const val NAVIGATION_DEADLINE_MS = 10_000L

    /** Advice depends on the selected tunnel, not on an unverified VPN diagnosis. */
    fun showTailscaleHelp(provider: ConnectionUrl.Provider?): Boolean =
        provider == ConnectionUrl.Provider.TAILSCALE
}
