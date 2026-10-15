package mx.overdrive.companion

import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileChoiceTest {
    private val entries = listOf("first", "second", "third")
    private fun choose(favorite: String?, last: String?, pinned: Boolean): String? =
        ProfileChoice.choose(entries, favorite, last, pinned) { it }

    @Test fun initiallyFirstConnectionIsUsed() {
        assertEquals("first", choose("first", "first", false))
    }
    @Test fun latestSelectionIsUsedByDefault() {
        assertEquals("second", choose("first", "second", false))
    }
    @Test fun explicitFavoriteOverridesLastSelectionOnNextLaunch() {
        assertEquals("third", choose("third", "second", true))
    }
    @Test fun missingPinnedProfileFallsBackToExisting() {
        assertEquals("second", choose("removed", "second", true))
    }
    @Test fun emptyProfilesAreSafe() {
        assertEquals(null, ProfileChoice.choose(emptyList<String>(), "first", "second", true) { it })
    }
}
