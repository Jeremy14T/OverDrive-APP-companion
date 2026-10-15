package mx.overdrive.companion

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Local-only profiles. Migrates the original single saved tunnel in place. */
internal class ConnectionStore(context: Context) {
    private val prefs = context.getSharedPreferences("companion_connection", Context.MODE_PRIVATE)

    data class Saved(
        val provider: ConnectionUrl.Provider,
        val url: String,
        val id: String = UUID.randomUUID().toString(),
        val label: String = provider.displayName
    )

    init { migrateLegacy() }

    private fun migrateLegacy() {
        if (prefs.contains("profiles_json")) return
        val provider = try {
            ConnectionUrl.Provider.valueOf(prefs.getString("provider", "") ?: "")
        } catch (_: IllegalArgumentException) { null }
        val url = provider?.let { ConnectionUrl.validate(prefs.getString("url", "") ?: "", it) }
        if (provider != null && url != null) {
            val first = Saved(provider, url)
            prefs.edit().putString("profiles_json", serialize(listOf(first)))
                .putString("favorite_id", first.id)
                .putString("last_id", first.id)
                .putBoolean("favorite_pinned", false)
                .apply()
        } else {
            prefs.edit().putString("profiles_json", "[]").apply()
        }
    }

    fun all(): List<Saved> = try {
        val array = JSONArray(prefs.getString("profiles_json", "[]"))
        val output = mutableListOf<Saved>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val provider = try { ConnectionUrl.Provider.valueOf(obj.getString("provider")) }
                catch (_: Exception) { continue }
            val url = ConnectionUrl.validate(obj.optString("url"), provider) ?: continue
            val id = obj.optString("id").takeIf { it.isNotBlank() } ?: continue
            if (output.any { it.id == id }) continue
            val label = obj.optString("label").take(50).ifBlank { provider.displayName }
            output.add(Saved(provider, url, id, label))
        }
        output
    } catch (_: Exception) { emptyList() }

    fun favoriteId(): String? = prefs.getString("favorite_id", null)
    fun lastId(): String? = prefs.getString("last_id", null)
    fun isFavoritePinned(): Boolean = prefs.getBoolean("favorite_pinned", false)

    fun startup(): Saved? {
        val entries = all()
        return ProfileChoice.choose(entries, favoriteId(), lastId(), isFavoritePinned())
    }

    fun upsert(provider: ConnectionUrl.Provider, url: String, label: String, editingId: String? = null): Saved {
        val valid = ConnectionUrl.validate(url, provider) ?: error("Invalid URL")
        val items = all().toMutableList()
        val existing = items.firstOrNull { it.id == editingId } ?: items.firstOrNull {
            it.provider == provider && it.url == valid
        }
        val updated = Saved(provider, valid, existing?.id ?: UUID.randomUUID().toString(),
            label.trim().take(50).ifBlank { provider.displayName })
        val index = items.indexOfFirst { it.id == updated.id }
        if (index >= 0) items[index] = updated else items.add(updated)
        val edit = prefs.edit().putString("profiles_json", serialize(items))
        // Only a newly created first profile gets the automatic initial favorite.
        // Editing an existing profile must not re-star it after the user unmarked it.
        if (items.size == 1 && index < 0) {
            edit.putString("favorite_id", updated.id)
                .putBoolean("favorite_pinned", false)
        }
        edit.apply()
        return updated
    }

    /** Record the user's latest selection, including while trying another tunnel. */
    fun markUsed(id: String) {
        if (all().any { it.id == id }) prefs.edit().putString("last_id", id).apply()
    }

    /** Explicitly pin this tunnel to be auto-opened in future sessions. */
    fun setFavorite(id: String) {
        if (all().any { it.id == id }) prefs.edit().putString("favorite_id", id)
            .putBoolean("favorite_pinned", true).apply()
    }

    /** Unmark the currently starred tunnel; do not select a different favorite silently. */
    fun clearFavorite(id: String) {
        if (favoriteId() == id) prefs.edit()
            .remove("favorite_id")
            .putBoolean("favorite_pinned", false)
            .apply()
    }

    /** Resume auto-opening the last selected tunnel (keep the favourite label). */
    fun useLastOnStartup() {
        prefs.edit().putBoolean("favorite_pinned", false).apply()
    }

    fun delete(id: String) {
        val entries = all().filterNot { it.id == id }
        val fav = favoriteId()
        val last = lastId()
        val edit = prefs.edit().putString("profiles_json", serialize(entries))
        if (fav == id) {
            edit.putString("favorite_id", entries.firstOrNull()?.id)
                .putBoolean("favorite_pinned", false)
        }
        if (last == id) edit.putString("last_id", entries.firstOrNull()?.id)
        edit.apply()
    }

    fun hasShownConnectionHint(): Boolean = prefs.getBoolean("hint_seen_v1", false)
    fun markConnectionHintShown() { prefs.edit().putBoolean("hint_seen_v1", true).apply() }

    private fun serialize(entries: List<Saved>): String = JSONArray().apply {
        entries.forEach { item ->
            put(JSONObject().put("id", item.id).put("provider", item.provider.name)
                .put("url", item.url).put("label", item.label))
        }
    }.toString()
}
