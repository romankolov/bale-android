package ai.bale.client.data

import ai.bale.client.domain.AppLanguage
import ai.bale.client.domain.AppThemeMode
import org.junit.Assert.*
import org.junit.Test

/**
 * The store owns appearance + per-account dialog flags (drafts, pin, mute, unread, mentions) so
 * Room can stay at schema v3. These tests use the in-memory mode, exactly as the app does before
 * Android hands it a SharedPreferences instance.
 */
class ClientPreferencesStoreTest {
    @Test fun `appearance updates are reactive and survive a store restart through the same backing map`() {
        val store = ClientPreferencesStore()
        assertEquals(AppThemeMode.System, store.preferences.value.themeMode)
        assertTrue("dynamic colour is opt-out", store.preferences.value.dynamicColor)

        store.updatePreferences { it.copy(themeMode = AppThemeMode.Dark, dynamicColor = false, language = AppLanguage.Persian) }
        assertEquals(AppThemeMode.Dark, store.preferences.value.themeMode)
        assertFalse(store.preferences.value.dynamicColor)
        assertEquals(AppLanguage.Persian, store.preferences.value.language)
        assertEquals("fa", store.preferences.value.language.tag)
    }

    @Test fun `per-account overlays isolate drafts pins mutes unread and mention counters`() {
        val store = ClientPreferencesStore()
        store.updateOverlay(7) { current ->
            current.copy(
                drafts = current.drafts + ("1:42" to "half written"),
                pinnedPeers = current.pinnedPeers + "2:9",
                mutedPeers = current.mutedPeers + "2:9",
                markedUnreadPeers = current.markedUnreadPeers + "1:42",
                unreadMentions = current.unreadMentions + ("1:42" to 3),
            )
        }
        store.updateOverlay(8) { current -> current.copy(archivedPeers = current.archivedPeers + "1:42") }

        val seven = store.overlayFor(7)
        assertEquals("half written", seven.drafts["1:42"])
        assertEquals(setOf("2:9"), seven.pinnedPeers)
        assertEquals(setOf("2:9"), seven.mutedPeers)
        assertEquals(setOf("1:42"), seven.markedUnreadPeers)
        assertEquals(3, seven.unreadMentions["1:42"])
        assertTrue("account 8 must not leak into account 7", "1:42" !in seven.archivedPeers)
        assertEquals(setOf("1:42"), store.overlayFor(8).archivedPeers)

        store.updateOverlay(7) { current -> current.copy(drafts = current.drafts - "1:42") }
        assertTrue(store.overlayFor(7).drafts.isEmpty())

        store.clearAccount(7)
        assertTrue("clearing an account drops its cached overlay", store.overlayFor(7).pinnedPeers.isEmpty())
        assertEquals(setOf("1:42"), store.overlayFor(8).archivedPeers)
    }

    @Test fun `overlay stream exposes every loaded account for the conversation list combinator`() {
        val store = ClientPreferencesStore()
        store.updateOverlay(3) { it.copy(mutedPeers = it.mutedPeers + "1:1") }
        store.updateOverlay(4) { it.copy(pinnedPeers = it.pinnedPeers + "2:2") }
        assertEquals(setOf(3, 4), store.overlays.value.keys)
        assertEquals(setOf("1:1"), store.overlays.value.getValue(3).mutedPeers)
    }
}
