package ai.bale.client.feature.messaging

import ai.bale.client.domain.Conversation
import ai.bale.client.domain.PeerPresence
import ai.bale.client.domain.Peer
import ai.bale.client.domain.SharedMediaTab
import org.junit.Assert.*
import org.junit.Test

/** The filter strip and folder scoping are pure logic; they must never silently hide chats. */
class ConversationFilterTest {
    private fun chat(
        key: String,
        id: Int,
        kind: Int = 1,
        unread: Int = 0,
        archived: Boolean = false,
        isChannel: Boolean = false,
        isBot: Boolean = false,
        isSavedMessages: Boolean = false,
        markedUnread: Boolean = false,
        draft: String = "",
        title: String = "Chat $id",
        preview: String = "body",
    ) = Conversation(
        peer = Peer(id, kind, 0L),
        title = title,
        preview = preview,
        date = 1000L + id,
        unread = unread,
        archived = archived,
        isChannel = isChannel,
        isBot = isBot,
        isSavedMessages = isSavedMessages,
        markedUnread = markedUnread,
        draft = draft,
    )

    private val chats = listOf(
        chat("1:1", 1, unread = 2),
        chat("2:2", 2, kind = 2, isChannel = true),
        chat("1:3", 3, isBot = true),
        chat("1:4", 4, archived = true),
        chat("1:5", 5, isSavedMessages = true),
        chat("1:6", 6, markedUnread = true),
    )

    @Test fun `each filter selects exactly the conversations it advertises`() {
        assertEquals(listOf("1:1", "2:2", "1:3", "1:5", "1:6"), chats.filter { it.matches("", DialogFilter.All) }.map { it.peer.key })
        assertEquals(listOf("1:1", "1:6"), chats.filter { it.matches("", DialogFilter.Unread) }.map { it.peer.key })
        assertEquals(listOf("1:1", "1:6"), chats.filter { it.matches("", DialogFilter.Personal) }.map { it.peer.key })
        assertEquals(listOf("2:2"), chats.filter { it.matches("", DialogFilter.Groups) }.map { it.peer.key })
        assertEquals(listOf("1:3"), chats.filter { it.matches("", DialogFilter.Bots) }.map { it.peer.key })
        assertEquals(listOf("1:5"), chats.filter { it.matches("", DialogFilter.Saved) }.map { it.peer.key })
        assertEquals(listOf("1:4"), chats.filter { it.matches("", DialogFilter.Archived) }.map { it.peer.key })
    }

    @Test fun `text query narrows every filter and matches preview as well as title`() {
        assertTrue(chats.filter { it.matches("Chat 2", DialogFilter.All) }.single().peer.key == "2:2")
        val renamed = chats + chat("1:9", 9, title = "Design review", preview = "handoff notes")
        assertEquals(listOf("1:9"), renamed.filter { it.matches("handoff", DialogFilter.All) }.map { it.peer.key })
        assertTrue(renamed.filter { it.matches("handoff", DialogFilter.Archived) }.isEmpty())
    }

    @Test fun `firstUrlIn finds the first http link and trims trailing punctuation`() {
        assertNull(firstUrlIn("no link here"))
        assertEquals("https://ble.ir/join/abc", firstUrlIn("look: https://ble.ir/join/abc."))
        assertEquals("http://a.test/x", firstUrlIn("http://a.test/x then https://b.test/y"))
        assertEquals("https://a.test?(x=1)", firstUrlIn("https://a.test?(x=1), then more"))
    }

    @Test fun `schedule presets ascend and shared media tabs map to distinct wire content types`() {
        assertEquals(listOf(1, 2, 3, 4), SharedMediaTab.entries.map { it.wireType })
        assertEquals(5, SCHEDULE_PRESET_MINUTES.size)
        assertEquals(SCHEDULE_PRESET_MINUTES.sorted(), SCHEDULE_PRESET_MINUTES)
    }

    @Test fun `presence labels prefer online then exact timestamps then coarse privacy buckets`() {
        assertEquals(PresenceLabel.None, presenceLabelFor(null))
        assertEquals(PresenceLabel.None, presenceLabelFor(PeerPresence()))
        assertEquals(PresenceLabel.Online, presenceLabelFor(PeerPresence(online = true)))
        assertEquals(
            PresenceLabel.LastSeenAt,
            presenceLabelFor(PeerPresence(lastSeenEpochSec = 1_700_000_000L, lastSeenUnknown = true, unknownLevel = 1)),
        )
        assertEquals(PresenceLabel.LastSeenRecently, presenceLabelFor(PeerPresence(lastSeenUnknown = true, unknownLevel = 1)))
        assertEquals(PresenceLabel.LastSeenWeek, presenceLabelFor(PeerPresence(lastSeenUnknown = true, unknownLevel = 2)))
        assertEquals(PresenceLabel.LastSeenMonth, presenceLabelFor(PeerPresence(lastSeenUnknown = true, unknownLevel = 3)))
        // An unknown bucket the client does not know degrades to "no line" instead of lying.
        assertEquals(PresenceLabel.None, presenceLabelFor(PeerPresence(lastSeenUnknown = true, unknownLevel = 9)))
    }
}
