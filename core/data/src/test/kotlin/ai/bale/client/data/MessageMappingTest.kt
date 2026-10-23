package ai.bale.client.data

import ai.bale.client.domain.AttachmentKind
import ai.bale.client.domain.AvatarRef
import ai.bale.client.domain.BotButtonKind
import ai.bale.client.domain.Delivery
import ai.bale.client.domain.ForwardedInfo
import ai.bale.client.network.MessagingApi
import ai.bale.client.protocol.*
import com.google.protobuf.ByteString
import com.google.protobuf.Int32Value
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
        assertNull(message.forwardedFrom)
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

    @Test fun `forwarded text media and poll envelopes unwrap emptyMessage and preserve poll results across sync`() {
        val fwdHistory = History.newBuilder()
            .setRid(501)
            .setDate(2000)
            .setSenderUid(12)
            .setMessage(Message2.newBuilder().setEmptyMessage(EmptyMessage.getDefaultInstance()))
            .setQuotedMessage(
                QuotedMessage.newBuilder()
                    .setSenderUserId(88)
                    .setPublicGroupId(Int32Value.of(555))
                    .setAuthorSign(StringValue.of("Bale News"))
                    .setQuotedMessageContent(MessagingApi.text("Forwarded breaking update"))
            )
            .build()

        val mapped = fwdHistory.entity(7, "1:12").domain()
        assertTrue(mapped.supported)
        assertEquals("Forwarded breaking update", mapped.text)
        assertNull(mapped.quote)
        assertEquals(ForwardedInfo(88, 555, "Bale News"), mapped.forwardedFrom)

        // Pre-existing Room v3 rows written with empty text & supported=false upgrade transparently on read
        val staleCached = MessageEntity(7, "1:12", 501, 12, "", 2000, "Sent", false, fwdHistory.toByteArray())
        val upgraded = staleCached.domain()
        assertTrue(upgraded.supported)
        assertEquals("Forwarded breaking update", upgraded.text)
        assertEquals("Bale News", upgraded.forwardedFrom?.authorName)

        // Forwarded poll keeps hydrated PollResult across subsequent LoadHistory syncs where PollMessage has no result
        val rawFwdPoll = History.newBuilder()
            .setRid(502)
            .setDate(2001)
            .setSenderUid(12)
            .setMessage(Message2.newBuilder().setEmptyMessage(EmptyMessage.getDefaultInstance()))
            .setQuotedMessage(
                QuotedMessage.newBuilder()
                    .setSenderUserId(99)
                    .setQuotedMessageContent(
                        Message2.newBuilder().setPollMessage(
                            PollMessage.newBuilder().setPollId(777L).setQuestion("Forwarded poll?")
                                .addOptions(Options.newBuilder().setId(0).setText("Option A"))
                                .addOptions(Options.newBuilder().setId(1).setText("Option B"))
                        )
                    )
            )
            .build()
        val hydrated = rawFwdPoll.withPollResult(
            PollResult.newBuilder().setPollId(777L).setVotersCount(9).addChosenOptionIds(1L)
                .addOptionResults(OptionResults.newBuilder().setOptionId(1).setVotesCount(6)).build()
        )
        val mergedAfterReload = mergeHistoryPreservingState(hydrated, rawFwdPoll)
        val domainPoll = mergedAfterReload.entity(7, "1:12").domain()
        assertTrue(domainPoll.supported)
        assertEquals(9, domainPoll.poll?.totalVoters)
        assertEquals(6, domainPoll.poll?.options?.get(1)?.votes)
        assertTrue(domainPoll.poll?.options?.get(1)?.chosen == true)
    }

    @Test fun `legacy row and malformed optional cache metadata preserve message text`() {
        val legacy = MessageEntity(1, "1:2", 3, 1, "kept after migration", 4, "Failed", true)
        assertEquals("kept after migration", legacy.domain().text)
        assertEquals(Delivery.Failed, legacy.domain().delivery)
        val malformed = MessageEntity(1, "1:2", 3, 1, "still readable", 4, "Sent", true, metadata = byteArrayOf(10, 127))
        assertEquals("still readable", malformed.domain().text)
    }

    @Test fun `avatars, thread counters and sticker collections enrich the cached row`() {
        val history = History.newBuilder().setRid(500).setDate(900).setSenderUid(21)
            .setMessage(
                Message2.newBuilder().setStickerMessage(
                    StickerMessage.newBuilder()
                        .setStickerId(Int32Value.of(7))
                        .setStickerCollectionId(Int32Value.of(88))
                        .setStickerCollectionAccessHash(Int64Value.of(4242L))
                        .setImage256(
                            ImageLocation.newBuilder()
                                .setFileLocation(
                                    FileLocation.newBuilder().setFileId(1234L).setAccessHash(5678L),
                                )
                                .setWidth(160)
                                .setHeight(160)
                                .setFileSize(4096),
                        ),
                ),
            )
            .setReplies(RepliesInfo.newBuilder().setRepliesCount(6).addRecentRepliers(UserPeers.newBuilder().setUid(21)))
            .build()
        val mapped = history.entity(7, "2:9").domain()
        assertTrue("stickers are supported content", mapped.supported)
        assertEquals("Sticker", mapped.text)
        assertEquals(88, mapped.stickerCollectionId)
        assertEquals(4242L, mapped.stickerCollectionAccessHash)
        assertEquals(6, mapped.repliesCount)
        // image256 is an ImageLocation on the wire; the receiver resolves it as a downloadable ref.
        assertEquals(1234L, mapped.attachment?.fileId)
        assertEquals(5678L, mapped.attachment?.accessHash)
        assertEquals(AttachmentKind.Sticker, mapped.attachment?.kind)
        assertArrayEquals(byteArrayOf(4, 5, 6), mapped.attachment?.thumbnail)

        val small = Avatar.newBuilder().setSmallImage(
            SmallImage.newBuilder()
                .setFileLocation(FileLocation.newBuilder().setFileId(321L).setAccessHash(654L))
                .setWidth(96).setHeight(96),
        ).build()
        assertEquals(AvatarRef(321L, 654L), small.toRefOrNull())
        assertNull(Avatar.newBuilder().setId(Int64Value.of(5)).build().toRefOrNull())
        assertEquals(
            AvatarRef(321L, 654L),
            Users.newBuilder().setId(21).setName("Sara").setAvatar(small).build().avatarRefOrNull(),
        )
    }

    @Test fun `forwarded stickers unwrap to supported content while keeping origin metadata`() {
        val forwarded = History.newBuilder().setRid(501).setDate(901).setSenderUid(7)
            .setMessage(Message2.newBuilder().setEmptyMessage(EmptyMessage.getDefaultInstance()))
            .setQuotedMessage(
                QuotedMessage.newBuilder()
                    .setSenderUserId(64)
                    .setPublicGroupId(Int32Value.of(9))
                    .setAuthorSign(StringValue.of("@owner"))
                    .setQuotedMessageContent(
                        Message2.newBuilder().setStickerMessage(
                            StickerMessage.newBuilder().setStickerCollectionId(Int32Value.of(12)).setStickerCollectionAccessHash(Int64Value.of(99L)),
                        ),
                    ),
            )
            .build()
        val mapped = forwarded.entity(7, "1:9").domain()
        assertTrue(mapped.supported)
        assertEquals(ForwardedInfo(senderUserId = 64, publicGroupId = 9, authorName = "@owner"), mapped.forwardedFrom)
        assertNull("forwarded content never renders as a reply quote", mapped.quote)
        assertEquals(12, mapped.stickerCollectionId)
        assertEquals(99L, mapped.stickerCollectionAccessHash)
    }

    @Test fun `shared media results become normal message rows for the gallery and cache`() {
        val media = MediaResults.newBuilder()
            .setRid(77L)
            .setDate(88L)
            .setSenderId(3)
            .setContent(Message2.newBuilder().setTextMessage(TextMessage.newBuilder().setText("https://ble.ir/shared")))
            .build()
        val history = media.toHistory()
        assertEquals(77L, history.rid)
        assertEquals(3, history.senderUid)
        val row = media.entity(7, "2:9")
        assertEquals(77L, row.rid)
        assertEquals("https://ble.ir/shared", row.domain().text)
        assertTrue(row.supported)
    }

    @Test fun `merge keeps hydrated thread counters when the server re-sends a bare envelope`() {
        val rich = History.newBuilder().setRid(60).setDate(60)
            .setMessage(MessagingApi.text("topic"))
            .setReplies(RepliesInfo.newBuilder().setRepliesCount(9))
            .build()
        val bare = History.newBuilder().setRid(60).setDate(60).setMessage(MessagingApi.text("topic")).build()
        assertEquals(9, mergeHistoryPreservingState(rich, bare).replies.repliesCount)
        assertEquals(9, mergeHistoryPreservingState(rich, bare).entity(7, "2:9").domain().repliesCount)
    }
}
