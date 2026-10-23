package ai.bale.client.data

import ai.bale.client.domain.Delivery
import ai.bale.client.network.MessagingApi
import ai.bale.client.protocol.*
import com.google.protobuf.Int64Value
import org.junit.Assert.*
import org.junit.Test

class MessageMappingTest {
    @Test fun `history maps quoted content edit marker sequence and reactions`() {
        val history = History.newBuilder().setRid(91).setDate(1234).setSenderUid(7)
            .setMessage(MessagingApi.text("answer")).setSeq(Int64Value.of(77)).setEditedAt(Int64Value.of(1235))
            .setQuotedMessage(QuotedMessage.newBuilder().setMessageId(Int64Value.of(88)).setSenderUserId(9)
                .setQuotedMessageContent(MessagingApi.text("question")))
            .addReactions(Field264.newBuilder().setCode("👍").setCardinality(Int64Value.of(3))).build()
        val message = history.entity(7, "1:9").domain()
        assertEquals("answer", message.text)
        assertEquals(88L, message.quote?.rid)
        assertEquals("question", message.quote?.text)
        assertEquals(77L, message.reference.sequence)
        assertTrue(message.edited)
        assertEquals(3L, message.reactions.single().count)
        assertEquals(Delivery.Sent, message.delivery)
    }
    @Test fun `legacy row has safe metadata defaults and keeps delivery`() {
        val row = MessageEntity(1, "1:2", 3, 1, "kept after migration", 4, "Failed", true)
        val message = row.domain()
        assertEquals("kept after migration", message.text)
        assertNull(message.quote)
        assertEquals(0L, message.sequence)
        assertFalse(message.edited)
        assertEquals(Delivery.Failed, message.delivery)
    }
    @Test fun `malformed optional cache metadata does not lose message text`() {
        val row = MessageEntity(1, "1:2", 3, 1, "still readable", 4, "Sent", true, metadata = byteArrayOf(10, 127))
        assertEquals("still readable", row.domain().text)
        assertNull(row.domain().quote)
    }
}
