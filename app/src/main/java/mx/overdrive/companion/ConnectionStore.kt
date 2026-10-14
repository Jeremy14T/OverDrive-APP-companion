package mx.overdrive.companion

import android.content.Context

internal class ConnectionStore(context: Context) {
    private val prefs = context.getSharedPreferences("companion_connection", Context.MODE_PRIVATE)

    data class Saved(val provider: ConnectionUrl.Provider, val url: String)

    fun read(): Saved? {
        val provider = try {
            ConnectionUrl.Provider.valueOf(prefs.getString("provider", "") ?: "")
        } catch (_: IllegalArgumentException) { return null }
        val raw = prefs.getString("url", null) ?: return null
        val validated = ConnectionUrl.validate(raw, provider) ?: return null
        return Saved(provider, validated)
    }

    fun save(value: Saved) {
        prefs.edit().putString("provider", value.provider.name).putString("url", value.url).apply()
    }
}
