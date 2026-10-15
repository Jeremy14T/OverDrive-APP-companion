package mx.overdrive.companion

/** Kept free of Android APIs so the startup preference rules can be unit tested. */
internal object ProfileChoice {
    fun <T> choose(entries: List<T>, favoriteId: String?, lastId: String?, pinned: Boolean, id: (T) -> String): T? {
        val primary = if (pinned) favoriteId else lastId
        return entries.firstOrNull { id(it) == primary }
            ?: entries.firstOrNull { id(it) == favoriteId }
            ?: entries.firstOrNull { id(it) == lastId }
            ?: entries.firstOrNull()
    }

    fun choose(entries: List<ConnectionStore.Saved>, favoriteId: String?, lastId: String?, pinned: Boolean) =
        choose(entries, favoriteId, lastId, pinned) { it.id }
}
