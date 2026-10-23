package ai.bale.client.data

import ai.bale.client.domain.AttachmentKind
import ai.bale.client.domain.BotButtonKind
import ai.bale.client.domain.Delivery
import ai.bale.client.network.MessagingApi
import ai.bale.client.protocol.*
import com.google.protobuf.ByteString
import com.google.protobuf.Int64Value
import com.google.protobuf.StringValue
import org.junit.Assert.*
import org.junit.Test

class MessageMappingTest {
    @Test fun `history maps quoted content edit marker sequence and own reaction flag`() {
        val history = History.newBuilder().setRid(91).setDate(1234).setSenderUid(7)
            .setMessage(MessagingApi.text("answer")).setSeq(Int64Value.of(77)).setEditedAt(Int64Value.of(1235))
            .setQuotedMessage(QuotedMessage.newBuilder().setMessageId(Int64Value.of(88)).setSenderUserId(9)
                .setQuotedMessageContent(MessagingApi.text("question")))
            .addReactions(Field264.newBuilder().addUsers(7).addUsers(9).setCode("👍").setCardinality(Int64Value.of(3)))
            .build()
        val message = history.entity(7, "1:9").domain()
        assertEquals("answer", message.text)
        assertEquals(88L, message.quote?.rid)
        assertEquals("question", message.quote?.text)
        assertEquals(77L, message.reference.sequence)
        assertTrue(message.edited)
        assertEquals(3L, message.reactions.single().count)
        assertTrue(message.reactions.single().mine)
        assertEquals(Delivery.Sent, message.delivery)
    }

    @Test fun `document poll bot template and service messages map rich fields cleanly`() {
        val docMsg = Message2.newBuilder().setDocumentMessage(
            DocumentMessage.newBuilder().setFileId(44L).setAccessHash(88L).setName("note.ogg").setMimeType("audio/ogg").setFileSize(2048)
                .setThumb(Thumb.newBuilder().setW(16).setH(16).setThumb(ByteString.copyFrom(byteArrayOf(1, 2, 3))))
                .setExt(Extra2.newBuilder().setDocumentExVoice(DocumentExVoice.newBuilder().setDuration(9)))
        ).build()
        val mappedDoc = History.newBuilder().setRid(1).setDate(10).setMessage(docMsg).build().entity(7, "1:9").domain()
        assertEquals(AttachmentKind.Voice, mappedDoc.attachment?.kind)
        assertEquals(9, mappedDoc.attachment?.durationSeconds)
        assertEquals(3, mappedDoc.attachment?.thumbnail?.size)

        val pollMsg = Message2.newBuilder().setPollMessage(
            PollMessage.newBuilder().setPollId(55L).setQuestion("Ship now?")
                .addOptions(Options.newBuilder().setId(0).setText("Yes"))
                .addOptions(Options.newBuilder().setId(1).setText("Definitely"))
                .setResult(PollResult.newBuilder().addChosenOptionIds(0L).setVotersCount(5)
                    .addOptionResults(OptionResults.newBuilder().setOptionId(0).setVotesCount(4)))
        ).build()
        val mappedPoll = History.newBuilder().setRid(2).setDate(11).setMessage(pollMsg).build().entity(7, "1:9").domain()
        assertEquals(55L, mappedPoll.poll?.pollId)
        assertTrue(mappedPoll.poll!!.options[0].chosen)
        assertEquals(4, mappedPoll.poll!!.options[0].votes)

        val tplMsg = Message2.newBuilder().setTemplateMessage(
            TemplateMessage.newBuilder().setTemplateMessageId(33L).setGeneralMessage(MessagingApi.text("Choose action"))
                .setInlineKeyboardMarkup(InlineKeyboardMarkup.newBuilder().addListOfInlineList(
                    ListOfInlineList.newBuilder()
                        .addInlineList(InlineList.newBuilder().setText("Confirm").setCallbackData(StringValue.of("ok")))
                        .addInlineList(InlineList.newBuilder().setText("Docs").setUrl(StringValue.of("https://bale.ai")))
                ))
        ).build()
        val mappedTpl = History.newBuilder().setRid(3).setDate(12).setMessage(tplMsg).build().entity(7, "1:9").domain()
        assertEquals("Choose action", mappedTpl.text)
        assertEquals(BotButtonKind.Callback, mappedTpl.buttons[0][0].kind)
        assertEquals(BotButtonKind.OpenUrl, mappedTpl.buttons[0][1].kind)

        val svcMsg = Message2.newBuilder().setServiceMessage(ServiceMessage.newBuilder().setText("Group created")).build()
        val mappedSvc = History.newBuilder().setRid(4).setDate(13).setMessage(svcMsg).build().entity(7, "2:9").domain()
        assertTrue(mappedSvc.serviceNotice)
    }

    @Test fun `legacy row and malformed optional cache metadata preserve message text`() {
        val legacy = MessageEntity(1, "1:2", 3, 1, "kept after migration", 4, "Failed", true)
        assertEquals("kept after migration", legacy.domain().text)
        assertEquals(Delivery.Failed, legacy.domain().delivery)
        val malformed = MessageEntity(1, "1:2", 3, 1, "still readable", 4, "Sent", true, metadata = byteArrayOf(10, 127))
        assertEquals("still readable", malformed.domain().text)
    }
}
