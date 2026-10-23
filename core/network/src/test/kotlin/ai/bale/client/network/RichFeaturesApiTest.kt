package ai.bale.client.network

import ai.bale.client.domain.AttachmentKind
import ai.bale.client.domain.Connection
import ai.bale.client.domain.ExPeerKind
import ai.bale.client.domain.LinkPreviewInfo
import ai.bale.client.domain.MessageReference
import ai.bale.client.domain.Peer
import ai.bale.client.domain.SavedGif
import ai.bale.client.domain.StickerItem
import ai.bale.client.network.MessagingApi.Companion.exWire
import ai.bale.client.protocol.*
import com.google.protobuf.BoolValue
import com.google.protobuf.ByteString
import com.google.protobuf.Int64Value
import com.google.protobuf.MessageLite
import com.google.protobuf.StringValue
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Wire-level contracts for the release-candidate feature RPCs and the new Messaging methods. */
class RichFeaturesApiTest {
    private class StubRpc : BaleRpc {
        override val state = MutableStateFlow(Connection.Connected)
        override var sessionId = 1L
        override val updates = MutableSharedFlow<SessionUpdate>()
        val calls = mutableListOf<Triple<String, String, MessageLite>>()
        var responder: (String, String, MessageLite) -> ByteArray = { _, _, _ -> byteArrayOf() }
        override suspend fun rpc(service: String, method: String, request: MessageLite, expectedSession: Long): ByteArray {
            calls += Triple(service, method, request)
            return responder(service, method, request)
        }
    }

    @Test fun `scheduler wraps a full SendMessage request with a future timestamp and channel peer`() = runTest {
        val rpc = StubRpc()
        rpc.responder = { _, _, _ -> Scheduler_ScheduleTask_Response.newBuilder().setTaskId(4242L).build().toByteArray() }
        val api = RichFeaturesApi(rpc)
        val taskId = api.scheduleMessage(
            peer = Peer(55, 2, 999L),
            rid = 777L,
            content = Message2.newBuilder().setTextMessage(TextMessage.newBuilder().setText("later")).build(),
            sendAtMillis = System.currentTimeMillis() + 600_000L,
            exPeerType = ExPeerKind.CHANNEL,
        )
        assertEquals(4242L, taskId)
        val (service, method, request) = rpc.calls.single()
        assertEquals("bale.schedule.v1.Scheduler", service)
        assertEquals("ScheduleTask", method)
        val sent = request as Scheduler_ScheduleTask_Request
        assertEquals(55, sent.exPeer.id)
        assertEquals(ExPeerKind.CHANNEL, sent.exPeer.type)
        assertEquals(999L, sent.exPeer.accessHash)
        assertTrue(sent.field18.seconds * 1000L > System.currentTimeMillis())
        val inner = sent.payload.taskSendMessage.sendMessage
        assertEquals(55, inner.peer.id)
        assertEquals(2, inner.peer.type)
        assertEquals(777L, inner.rid)
        assertEquals("later", inner.message.textMessage.text)
    }

    @Test fun `scheduled task listing decodes nested text and cancel batches task ids`() = runTest {
        val rpc = StubRpc()
        val response = Scheduler_ListTasks_Response.newBuilder().addTasks(
            Tasks.newBuilder()
                .setTaskId(9L)
                .setStatus(0)
                .setPayload(
                    Payload2.newBuilder().setTaskSendMessage(
                        TaskSendMessage.newBuilder().setSendMessage(
                            Messaging_SendMessage_Request.newBuilder()
                                .setMessage(Message2.newBuilder().setTextMessage(TextMessage.newBuilder().setText("queued"))),
                        ),
                    ),
                ),
        ).build()
        rpc.responder = { _, _, _ -> response.toByteArray() }
        val api = RichFeaturesApi(rpc)
        val tasks = api.scheduledTasks(Peer(12, 1, 5L), ExPeerKind.PRIVATE)
        assertEquals("queued", tasks.single().payload.taskSendMessage.sendMessage.message.textMessage.text)

        api.executeTaskNow(9L)
        api.cancelTasks(listOf(9L, 10L))
        assertEquals(listOf("ListTasks", "ExecuteTaskNow", "UnScheduleTask"), rpc.calls.map { it.second })
        assertEquals(9L, (rpc.calls[1].third as Scheduler_ExecuteTaskNow_Request).taskID)
        assertEquals(listOf(9L, 10L), (rpc.calls[2].third as Scheduler_UnScheduleTask_Request).taskIDsList)
        api.cancelTasks(emptyList())
        assertEquals(3, rpc.calls.size)
    }

    @Test fun `shared media request carries the tab content type and cursors by date`() = runTest {
        val rpc = StubRpc()
        val media = MediaResults.newBuilder()
            .setRid(31L)
            .setDate(1234L)
            .setSenderId(64)
            .setContent(Message2.newBuilder().setTextMessage(TextMessage.newBuilder().setText("https://ble.ir/x")))
            .build()
        rpc.responder = { _, _, _ -> SharedMediaService_LoadMedia_Response.newBuilder().addMediaResults(media).build().toByteArray() }
        val results = RichFeaturesApi(rpc).sharedMedia(Peer(7, 2, 8L), ExPeerKind.SUPERGROUP, contentType = 4, beforeDate = 99L)
        assertEquals(31L, results.single().rid)
        val request = rpc.calls.single().third as SharedMediaService_LoadMedia_Request
        assertEquals(4, request.contentType)
        assertEquals(ExPeerKind.SUPERGROUP, request.exPeer.type)
        assertEquals(99L, request.date.value)
        assertEquals(2, request.loadMode)
        assertEquals(30, request.minimumResults)
    }

    @Test fun `link preview maps metadata while an empty response degrades to null`() = runTest {
        val rpc = StubRpc()
        rpc.responder = { _, method, _ ->
            when (method) {
                "GetLinkPreview" -> TLDR_GetLinkPreview_Response.newBuilder()
                    .setTitle("Bale")
                    .setDescription("Official channel")
                    .setField26(Field2611.newBuilder().setUrl("https://cdn/thumb.jpg").setWidth(200).setHeight(100))
                    .build().toByteArray()
                else -> TLDR_GetLinkSummary_Response.newBuilder().setSummary("A short AI summary").build().toByteArray()
            }
        }
        val api = RichFeaturesApi(rpc)
        assertEquals(
            LinkPreviewInfo(url = "https://ble.ir/channel", title = "Bale", description = "Official channel", imageUrl = "https://cdn/thumb.jpg"),
            api.linkPreview("  https://ble.ir/channel  "),
        )
        assertEquals("A short AI summary", api.linkSummary("https://ble.ir/channel"))
        assertEquals("https://ble.ir/channel", (rpc.calls[0].third as TLDR_GetLinkPreview_Request).url)

        rpc.responder = { _, _, _ -> byteArrayOf() }
        assertNull(api.linkPreview("https://example.com/missing"))
        assertNull(api.linkPreview("   "))
        assertEquals(3, rpc.calls.size)
    }

    @Test fun `voice transcript waits for the server then returns the transcript document`() = runTest {
        val rpc = StubRpc()
        var mustWait = true
        rpc.responder = { _, _, _ ->
            val builder = AI_GetTranscript_Response.newBuilder().setMustWait(mustWait)
            if (!mustWait) {
                builder.setDownloadSource(
                    DownloadSource.newBuilder().setDocumentMessage(
                        DocumentMessage.newBuilder().setFileId(501L).setAccessHash(7L).setMimeType("text/plain"),
                    ),
                )
            }
            builder.build().toByteArray()
        }
        val api = RichFeaturesApi(rpc)
        val peer = Peer(3, 1, 4L)
        val reference = MessageReference(rid = 11L, date = 12L, sequence = 13L)
        val voice = DocumentMessage.newBuilder().setFileId(1L).setMimeType("audio/mp4").build()
        assertNull(api.voiceTranscript(peer, reference, voice, ExPeerKind.PRIVATE))
        mustWait = false
        assertEquals(501L, api.voiceTranscript(peer, reference, voice, ExPeerKind.PRIVATE)?.fileId)
        val request = rpc.calls.last().third as AI_GetTranscript_Request
        assertEquals(3, request.outPeer.id)
        assertEquals(11L, request.messageId.rid)
        assertEquals(13L, request.messageId.seq)
        assertEquals(1L, request.voice.fileId)
    }

    @Test fun `sticker packs keep legacy and v2 entries and can be added or removed`() = runTest {
        val rpc = StubRpc()
        val collection = Collections.newBuilder()
            .setId(88)
            .setAccessHash(4242L)
            .setName(StringValue.of("Cats"))
            .setAnimated(BoolValue.of(true))
            .addStickers(
                Field265.newBuilder()
                    .setId(1)
                    .setEmoji(StringValue.of("😺"))
                    .setImage256(
                        ImageLocation.newBuilder()
                            .setFileLocation(FileLocation.newBuilder().setFileId(900L).setAccessHash(1L))
                            .setWidth(256).setHeight(256),
                    ),
            )
            .addStickersV2(Field422.newBuilder().setId(2).setEmoji(StringValue.of("😹")))
            .build()
        rpc.responder = { _, method, _ ->
            when (method) {
                "LoadOwnStickers" -> Images_LoadOwnStickers_Response.newBuilder().addOwnStickers(collection).build().toByteArray()
                "LoadStickerCollection" -> Images_LoadStickerCollection_Response.newBuilder().setCollection(collection).build().toByteArray()
                else -> byteArrayOf()
            }
        }
        val api = RichFeaturesApi(rpc)
        val packs = api.ownStickers()
        assertEquals("Cats", packs.single().name.value)
        assertEquals(1, packs.single().stickersCount)
        assertEquals(1, packs.single().stickersV2Count)
        assertTrue(packs.single().animated.value)
        assertEquals(900L, packs.single().stickersList.single().image256.fileLocation.fileId)

        assertEquals(88, api.stickerCollection(88, 4242L).id)
        api.addStickerCollection(88, 4242L)
        val added = rpc.calls.last().third as Images_AddStickerCollection_Request
        assertEquals(88, added.id)
        assertEquals(4242L, added.accessHash)
        api.removeStickerCollection(88, 4242L)
        assertEquals("RemoveStickerCollection", rpc.calls.last().second)
    }

    @Test fun `saved gifs round trip location mime and thumbnail bytes`() = runTest {
        val rpc = StubRpc()
        val gif = Gifs.newBuilder()
            .setGifLocation(FileLocation.newBuilder().setFileId(777L).setAccessHash(888L))
            .setWidth(320).setHeight(240).setFileSize(4096L).setDuration(4).setUsedAt(1234L)
            .setThumb(ByteString.copyFrom(byteArrayOf(9, 8, 7)))
            .setMimeType(StringValue.of("video/mp4"))
            .build()
        rpc.responder = { _, _, _ -> Images_GetSavedGifs_Response.newBuilder().addGifs(gif).build().toByteArray() }
        val api = RichFeaturesApi(rpc)
        val saved = api.savedGifs().single()
        assertEquals(777L, saved.gifLocation.fileId)
        assertEquals(320, saved.width)
        assertEquals("video/mp4", saved.mimeType.value)
        assertArrayEquals(byteArrayOf(9, 8, 7), saved.thumb.toByteArray())

        api.addGif(RichFeaturesApi.location(777L, 888L, 3), "video/mp4", byteArrayOf(1))
        val add = rpc.calls.last().third as Images_AddGif_Request
        assertEquals(777L, add.gif.fileId)
        assertEquals(3, add.gif.fileStorageVersion.value)
        assertEquals("video/mp4", add.mimeType.text)
        assertArrayEquals(byteArrayOf(1), add.thumb.toByteArray())

        api.useGif(RichFeaturesApi.location(777L, 888L))
        assertEquals("UseGif", rpc.calls.last().second)
        api.removeGif(RichFeaturesApi.location(777L, 888L))
        assertEquals("RemoveGif", rpc.calls.last().second)
    }

    @Test fun `sticker and gif builders produce the message payloads the server expects`() {
        val sticker = StickerItem(
            id = 5,
            emoji = "😺",
            collectionId = 12,
            collectionAccessHash = 99L,
            fileId = 400L,
            accessHash = 401L,
            animated = true,
            previewBytes = byteArrayOf(1, 2, 3),
        )
        val message = RichFeaturesApi.stickerMessage(sticker)
        assertEquals(5, message.stickerMessage.stickerId.value)
        assertEquals(12, message.stickerMessage.stickerCollectionId.value)
        assertEquals(99L, message.stickerMessage.stickerCollectionAccessHash.value)
        // Stickers are resolved server-side from ids: no file locations, and `format` is omitted
        // for static stickers (the web client never sends 0).
        assertFalse(message.stickerMessage.hasImage256())
        assertFalse(message.stickerMessage.hasAnimation())
        assertEquals(0, message.stickerMessage.format)

        val animated = RichFeaturesApi.stickerMessage(sticker.copy(format = 2))
        assertEquals(2, animated.stickerMessage.format)

        val gif = SavedGif(fileId = 70L, accessHash = 71L, storageVersion = 2, sizeBytes = 1200L, width = 200, height = 100, durationSeconds = 3)
        val gifMessage = RichFeaturesApi.gifMessage(gif)
        assertEquals(70L, gifMessage.documentMessage.fileId)
        assertEquals(71L, gifMessage.documentMessage.accessHash)
        assertEquals(2, gifMessage.documentMessage.fileStorageVersion.value)
        assertEquals(200, gifMessage.documentMessage.ext.documentExGif.w)
        assertEquals(3, gifMessage.documentMessage.ext.documentExGif.duration)
        assertEquals("animation.mp4", gifMessage.documentMessage.name)

        val attachment = with(RichFeaturesApi) { gif.toAttachment() }
        assertEquals(AttachmentKind.Video, attachment.kind)
        assertEquals(1200, attachment.sizeBytes)
        assertEquals(2, attachment.storageVersion)
    }

    @Test fun `mini app resolution returns url and query id and submits bot answers`() = runTest {
        val rpc = StubRpc()
        rpc.responder = { _, method, _ ->
            when (method) {
                "GetMiniAppUrl" -> Appzar_GetMiniAppUrl_Response.newBuilder()
                    .setUrl("https://mini.bale.ai/app?startapp=abc")
                    .setQueryId(StringValue.of("query-1"))
                    .build().toByteArray()
                "GetMenuButton" -> Appzar_GetMenuButton_Response.newBuilder()
                    .setMenuButton(MenuButton2.newBuilder().setMiniApp(MiniApp.newBuilder().setText("Open").setUrl("https://mini.bale.ai/app")))
                    .build().toByteArray()
                else -> byteArrayOf()
            }
        }
        val api = RichFeaturesApi(rpc)
        val (url, queryId) = api.miniAppUrl(botUserId = 61, contextPeer = Peer(61, 1, 7L), contextExType = ExPeerKind.BOT, startParam = "deep")
        assertEquals("https://mini.bale.ai/app?startapp=abc", url)
        assertEquals("query-1", queryId)
        val request = rpc.calls.first().third as Appzar_GetMiniAppUrl_Request
        assertEquals(61, request.botUserId)
        assertEquals(1, request.screenMode)
        assertEquals("deep", request.main.startParam.value)
        assertEquals(7L, request.main.peer.accessHash)

        assertEquals("Open", api.menuButton(61)?.miniApp?.text)
        api.sendMiniAppData(61, "query-1", "{\"ok\":true}", "Confirm")
        val sent = rpc.calls.last().third as Ketf_SendMiniAppData_Request
        assertEquals("query-1", sent.queryId.value)
        assertEquals("{\"ok\":true}", sent.data.value)
        assertEquals("Confirm", sent.buttonText.value)
    }

    @Test fun `an empty mini app url falls back to the button provided link`() = runTest {
        val rpc = StubRpc()
        rpc.responder = { _, _, _ -> Appzar_GetMiniAppUrl_Response.newBuilder().build().toByteArray() }
        val api = RichFeaturesApi(rpc)
        val (url, queryId) = api.miniAppUrl(61, Peer(61, 1, 7L), ExPeerKind.BOT, "", "https://fallback.bale.ai/app")
        assertEquals("https://fallback.bale.ai/app", url)
        assertEquals("", queryId)
        assertFalse((rpc.calls.single().third as Appzar_GetMiniAppUrl_Request).hasMain())
    }

    @Test fun `inline bot query round trips peer query and callback answer text`() = runTest {
        val rpc = StubRpc()
        rpc.responder = { _, method, _ ->
            when (method) {
                "GetInlineBotResults" -> Ketf_GetInlineBotResults_Response.newBuilder()
                    .addResults(
                        Results.newBuilder()
                            .setTitle(Offset.newBuilder().setText("Giphy cat"))
                            .setDescription(Offset.newBuilder().setText("mp4"))
                            .setBotInlineMessage(
                                BotInlineMessage.newBuilder().setBotInlineMessageText(
                                    BotInlineMessageText.newBuilder().setText("https://media/giphy/cat.mp4"),
                                ),
                            ),
                    )
                    .setQueryId(Int64Value.of(5150L))
                    .build().toByteArray()
                else -> byteArrayOf()
            }
        }
        val api = RichFeaturesApi(rpc)
        val response = api.inlineBotResults(Peer(9, 1, 11L), botUserId = 61, query = "cat")
        assertEquals("Giphy cat", response.resultsList.single().title.text)
        assertEquals(5150L, response.queryId.value)
        val request = rpc.calls.single().third as Ketf_GetInlineBotResults_Request
        assertEquals("cat", request.query)
        assertEquals(61, request.botUserId)
        assertEquals(9, request.peer.id)

        rpc.responder = { _, _, _ ->
            Ketf_SendInlineCallback_Response.newBuilder().setAnswer(Answer.newBuilder().setText("done")).build().toByteArray()
        }
        assertEquals("done", api.inlineCallback(Peer(9, 1, 11L), MessageReference(1L, 2L, 0L), "data", ExPeerKind.PRIVATE))
        val callback = rpc.calls.last().third as Ketf_SendInlineCallback_Request
        assertEquals("data", callback.data.value)
        assertEquals(2L, callback.messageId.date)
    }

    @Test fun `report content switches between message and peer payloads`() = runTest {
        val rpc = StubRpc()
        val api = RichFeaturesApi(rpc)
        api.reportContent(Peer(21, 2, 5L), ExPeerKind.CHANNEL, MessageReference(3L, 4L, 5L), "spam")
        val messageReport = (rpc.calls.single().third as Report_ReportInappropriateContent_Request).report
        assertEquals("spam", messageReport.description)
        assertEquals(1, messageReport.kind)
        assertEquals(21, messageReport.messageReport.peer.id)
        assertEquals(3L, messageReport.messageReport.midsList.single().rid)

        api.reportContent(Peer(21, 2, 5L), ExPeerKind.CHANNEL, null, "   ")
        val peerReport = (rpc.calls.last().third as Report_ReportInappropriateContent_Request).report
        assertEquals("Reported from Android client", peerReport.description)
        assertEquals(21, peerReport.peerReport.peer.id)
        assertEquals(ExPeerKind.CHANNEL, peerReport.peerReport.peer.type)
    }

    @Test fun `poll full results expose per option voter identities`() = runTest {
        val rpc = StubRpc()
        rpc.responder = { _, _, _ ->
            Poll_GetFullPollResult_Response.newBuilder()
                .addFullPollResult(FullPollResult.newBuilder().setOptionId(1).setVotesCount(2).addVoters(Voters.newBuilder().setUserId(64).setVotedAt(1000L)))
                .build().toByteArray()
        }
        val result = MessagingApi(rpc).fullPollResult(42L).single()
        assertEquals(1, result.optionId)
        assertEquals(2, result.votesCount)
        assertEquals(64, result.votersList.single().userId)
        assertEquals(1000L, result.votersList.single().votedAt)
        assertEquals("bale.poll.v1.Poll", rpc.calls.single().first)
        assertEquals("GetFullPollResult", rpc.calls.single().second)
        assertEquals(42L, (rpc.calls.single().third as Poll_GetFullPollResult_Request).pollId)
    }

    @Test fun `folder rpcs use the singular peer fields recovered from the service protos`() = runTest {
        val rpc = StubRpc()
        val api = MessagingApi(rpc)
        rpc.responder = { _, method, _ ->
            when (method) {
                "CreateFolder" -> Messaging_CreateFolder_Response.newBuilder().setFolderId(7).setIndex(2).build().toByteArray()
                "LoadFolders" -> Messaging_LoadFolders_Response.newBuilder()
                    .addFolders(
                        Folders.newBuilder()
                            .setId(7)
                            .setName("Work")
                            .addPeers(ExPeer.newBuilder().setType(ExPeerKind.CHANNEL).setId(55))
                            .addUnreadPeers(ExPeer.newBuilder().setId(55)),
                    )
                    .build().toByteArray()
                else -> byteArrayOf()
            }
        }
        assertEquals(7, api.createFolder("Work"))
        val created = rpc.calls.first().third as Messaging_CreateFolder_Request
        assertEquals("Work", created.name)
        assertFalse(created.hasField18())

        val folders = api.loadFolders().foldersList
        assertEquals(7, folders.single().id)
        assertEquals(1, folders.single().unreadPeersCount)
        assertEquals(1, folders.single().peersCount)

        api.addPeerToFolder(7, "Work", Peer(55, 2, 3L).exWire(ExPeerKind.CHANNEL))
        val added = rpc.calls.last().third as Messaging_EditFolder_Request
        assertEquals(7, added.folderId)
        assertEquals(55, added.field26.id)
        assertEquals(0, added.deletedPeersCount)

        api.removePeersFromFolder(7, "Work", listOf(Peer(55, 2, 3L).exWire(ExPeerKind.CHANNEL), Peer(56, 2, 4L).exWire(ExPeerKind.CHANNEL)))
        val removed = rpc.calls.last().third as Messaging_EditFolder_Request
        assertEquals(2, removed.deletedPeersCount)
        assertFalse(removed.hasField26())

        api.deleteFolder(7)
        assertEquals("DeleteFolder", rpc.calls.last().second)
        assertEquals(7, (rpc.calls.last().third as Messaging_DeleteFolder_Request).folderId)
    }

    @Test fun `dialog unread flags and mention reads use the ex peer envelopes`() = runTest {
        val rpc = StubRpc()
        val api = MessagingApi(rpc)
        val peer = Peer(12, 2, 21L)
        api.markDialogs(listOf(peer to ExPeerKind.CHANNEL), unread = true)
        val unread = rpc.calls.last().third as Messaging_MarkDialogsAsUnread_Request
        assertEquals(12, unread.peersList.single().id)
        assertEquals(ExPeerKind.CHANNEL, unread.peersList.single().type)
        api.markDialogs(listOf(peer to ExPeerKind.CHANNEL), unread = false)
        assertEquals("MarkDialogsAsRead", rpc.calls.last().second)
        api.markDialogs(emptyList(), unread = true)
        assertEquals(2, rpc.calls.size)

        api.mentionRead(peer, MessageReference(4L, 5L, 6L), ExPeerKind.CHANNEL)
        val mention = rpc.calls.last().third as Messaging_MentionRead_Request
        assertEquals(4L, mention.messageId.rid)
        assertEquals(ExPeerKind.CHANNEL, mention.peer.type)
    }

    @Test fun `reaction voters replies and thread history decode the recovered containers`() = runTest {
        val rpc = StubRpc()
        val api = MessagingApi(rpc)
        rpc.responder = { _, method, _ ->
            when (method) {
                "GetMessageReactionsList" -> Abacus_GetMessageReactionsList_Response.newBuilder()
                    .addUserReactions(UserReactions.newBuilder().setUserId(64).setCode("❤️").setReactionTime(1000L))
                    .build().toByteArray()
                "GetMessagesRepliesInfo" -> Messaging_GetMessagesRepliesInfo_Response.newBuilder()
                    .addContainers(Containers3.newBuilder().setRid(3L).setRepliesInfo(RepliesInfo.newBuilder().setRepliesCount(4)))
                    .build().toByteArray()
                else -> Messaging_LoadReplies_Response.newBuilder()
                    .addHistory(History.newBuilder().setRid(9L).setDate(10L).setSenderUid(3).setMessage(MessagingApi.text("reply")))
                    .addUsers(Users.newBuilder().setId(3).setName("Sara"))
                    .build().toByteArray()
            }
        }
        val peer = Peer(12, 2, 21L)
        val reference = MessageReference(rid = 3L, date = 4L, sequence = 5L)
        val voters = api.reactionsFor(peer, reference)
        assertEquals(64, voters.single().userId)
        assertEquals("❤️", voters.single().code)
        assertEquals(1000L, voters.single().reactionTime)
        assertEquals("bale.abacus.v1.Abacus", rpc.calls.last().first)

        assertEquals(4, api.repliesInfo(peer, reference, ExPeerKind.SUPERGROUP))
        val replies = api.threadReplies(peer, reference, exPeerType = ExPeerKind.SUPERGROUP)
        assertEquals("reply", replies.historyList.single().message.textMessage.text)
        assertEquals("Sara", replies.usersList.single().name)
        val loaded = rpc.calls.last().third as Messaging_LoadReplies_Request
        assertEquals(3L, loaded.threadId.rid)
        assertEquals(ExPeerKind.SUPERGROUP, loaded.peer.type)
    }
}
