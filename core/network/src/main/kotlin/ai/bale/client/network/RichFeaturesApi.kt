package ai.bale.client.network

import ai.bale.client.domain.Attachment
import ai.bale.client.domain.AttachmentKind
import ai.bale.client.domain.LinkPreviewInfo
import ai.bale.client.domain.MessageReference
import ai.bale.client.domain.Peer
import ai.bale.client.domain.SavedGif
import ai.bale.client.domain.StickerItem
import ai.bale.client.network.MessagingApi.Companion.exWire
import ai.bale.client.network.MessagingApi.Companion.wire
import ai.bale.client.protocol.*
import com.google.protobuf.ByteString
import com.google.protobuf.Int32Value
import com.google.protobuf.Int64Value
import com.google.protobuf.MessageLite
import com.google.protobuf.StringValue
import com.google.protobuf.Timestamp

/**
 * Release-candidate feature RPCs: stickers & GIFs (`Images`), scheduled sending (`Scheduler`),
 * shared media (`SharedMediaService`), link previews and summaries (`TLDR`), voice transcripts
 * (`AI`), bots, inline bots and Mini-Apps (`Ketf`, `Appzar`, `Garson`) and moderation (`Report`).
 *
 * Every request shape below is taken from the recovered service protos; responses degrade to empty
 * values instead of throwing so a single unsupported feature never breaks a chat screen.
 */
class RichFeaturesApi(private val rpc: BaleRpc, private val session: Long = rpc.sessionId) {
    private suspend fun call(service: String, method: String, request: MessageLite): ByteArray =
        rpc.rpc(service, method, request, session)

    // --- Stickers & saved GIFs (`bale.v1.Images`) ---
    suspend fun ownStickers(): List<Collections> =
        Images_LoadOwnStickers_Response.parseFrom(
            call(IMAGES, "LoadOwnStickers", Images_LoadOwnStickers_Request.getDefaultInstance()),
        ).ownStickersList

    suspend fun stickerCollection(id: Int, accessHash: Long): Collections =
        Images_LoadStickerCollection_Response.parseFrom(
            call(IMAGES, "LoadStickerCollection", Images_LoadStickerCollection_Request.newBuilder().setId(id).setAccessHash(accessHash).build()),
        ).collection

    suspend fun addStickerCollection(id: Int, accessHash: Long) {
        call(IMAGES, "AddStickerCollection", Images_AddStickerCollection_Request.newBuilder().setId(id).setAccessHash(accessHash).build())
    }

    suspend fun removeStickerCollection(id: Int, accessHash: Long) {
        call(IMAGES, "RemoveStickerCollection", Images_RemoveStickerCollection_Request.newBuilder().setId(id).setAccessHash(accessHash).build())
    }

    suspend fun savedGifs(): List<Gifs> =
        Images_GetSavedGifs_Response.parseFrom(
            call(IMAGES, "GetSavedGifs", Images_GetSavedGifs_Request.getDefaultInstance()),
        ).gifsList

    suspend fun addGif(location: FileLocation, mimeType: String, thumb: ByteArray = byteArrayOf()) {
        call(
            IMAGES,
            "AddGif",
            Images_AddGif_Request.newBuilder()
                .setGif(location)
                .setThumb(ByteString.copyFrom(thumb))
                .setMimeType(Offset.newBuilder().setText(mimeType.ifBlank { "video/mp4" }))
                .build(),
        )
    }

    suspend fun removeGif(location: FileLocation) {
        call(IMAGES, "RemoveGif", Images_RemoveGif_Request.newBuilder().setGif(location).build())
    }

    suspend fun useGif(location: FileLocation, usedAt: Long = System.currentTimeMillis()) {
        call(IMAGES, "UseGif", Images_UseGif_Request.newBuilder().setGif(location).setUsedAt(usedAt).build())
    }

    // --- Scheduled sending (`bale.schedule.v1.Scheduler`) ---
    suspend fun scheduleMessage(
        peer: Peer,
        rid: Long,
        content: Message2,
        sendAtMillis: Long,
        exPeerType: Int,
        reply: MessageReference? = null,
    ): Long {
        val builder = Messaging_SendMessage_Request.newBuilder()
            .setPeer(peer.wire())
            .setExPeer(Peer3.newBuilder().setType(exPeerType).setId(peer.id).setAccessHash(peer.accessHash))
            .setRid(rid)
            .setMessage(content)
        if (reply != null) builder.setQuotedMessageReference(MessagingApi.reference(peer, reply))
        return Scheduler_ScheduleTask_Response.parseFrom(
            call(
                SCHEDULER,
                "ScheduleTask",
                Scheduler_ScheduleTask_Request.newBuilder()
                    .setExPeer(peer.exWire(exPeerType))
                    .setField18(timestampOf(sendAtMillis))
                    .setPayload(Payload2.newBuilder().setTaskSendMessage(TaskSendMessage.newBuilder().setSendMessage(builder)))
                    .build(),
            ),
        ).taskId
    }

    suspend fun scheduledTasks(peer: Peer, exPeerType: Int): List<Tasks> =
        Scheduler_ListTasks_Response.parseFrom(
            call(
                SCHEDULER,
                "ListTasks",
                Scheduler_ListTasks_Request.newBuilder().setExPeer(peer.exWire(exPeerType)).build(),
            ),
        ).tasksList

    suspend fun executeTaskNow(taskId: Long) {
        call(SCHEDULER, "ExecuteTaskNow", Scheduler_ExecuteTaskNow_Request.newBuilder().setTaskID(taskId).build())
    }

    suspend fun cancelTasks(taskIds: List<Long>) {
        if (taskIds.isEmpty()) return
        call(SCHEDULER, "UnScheduleTask", Scheduler_UnScheduleTask_Request.newBuilder().addAllTaskIDs(taskIds).build())
    }

    // --- Shared media gallery (`bale.shared_media.v1.SharedMediaService`) ---
    suspend fun sharedMedia(peer: Peer, exPeerType: Int, contentType: Int, beforeDate: Long = 0L): List<MediaResults> {
        val builder = SharedMediaService_LoadMedia_Request.newBuilder()
            .setExPeer(peer.exWire(exPeerType))
            .setContentType(contentType)
            .setLoadMode(2)
            .setMinimumResults(30)
        if (beforeDate > 0L) builder.setDate(Int64Value.of(beforeDate))
        return SharedMediaService_LoadMedia_Response.parseFrom(call(SHARED_MEDIA, "LoadMedia", builder.build())).mediaResultsList
    }

    // --- Link preview, summary and voice transcription ---
    suspend fun linkPreview(url: String): LinkPreviewInfo? {
        val clean = url.trim()
        if (clean.isBlank()) return null
        val resp = TLDR_GetLinkPreview_Response.parseFrom(call(TLDR, "GetLinkPreview", TLDR_GetLinkPreview_Request.newBuilder().setUrl(clean).build()))
        if (resp.title.isBlank() && resp.description.isBlank() && !resp.hasField26()) return null
        return LinkPreviewInfo(
            url = clean,
            title = resp.title,
            description = resp.description,
            imageUrl = if (resp.hasField26()) resp.field26.url else "",
        )
    }

    suspend fun linkSummary(url: String): String =
        TLDR_GetLinkSummary_Response.parseFrom(
            call(TLDR, "GetLinkSummary", TLDR_GetLinkSummary_Request.newBuilder().setUrl(url.trim()).build()),
        ).summary

    /** Returns the transcript document, or null while the server is still processing (`mustWait`). */
    suspend fun voiceTranscript(peer: Peer, message: MessageReference, voice: DocumentMessage, exPeerType: Int): DocumentMessage? {
        val resp = AI_GetTranscript_Response.parseFrom(
            call(
                AI_SERVICE,
                "GetTranscript",
                AI_GetTranscript_Request.newBuilder()
                    .setVoice(voice)
                    .setOutPeer(Peer3.newBuilder().setType(exPeerType).setId(peer.id).setAccessHash(peer.accessHash))
                    .setMessageId(messageId(message))
                    .build(),
            ),
        )
        if (resp.mustWait || !resp.hasDownloadSource() || !resp.downloadSource.hasDocumentMessage()) return null
        return resp.downloadSource.documentMessage
    }

    // --- Bots, inline bots and Mini-Apps ---
    suspend fun botInfo(botUserId: Int): BotInfo =
        Ketf_GetBotInfo_Response.parseFrom(
            call(KETF, "GetBotInfo", Ketf_GetBotInfo_Request.newBuilder().setBotUserId(botUserId).build()),
        ).botInfo

    suspend fun inlineBotResults(peer: Peer, botUserId: Int, query: String, offset: String = ""): Ketf_GetInlineBotResults_Response =
        Ketf_GetInlineBotResults_Response.parseFrom(
            call(
                KETF,
                "GetInlineBotResults",
                Ketf_GetInlineBotResults_Request.newBuilder()
                    .setPeer(peer.wire())
                    .setBotUserId(botUserId)
                    .setQuery(query)
                    .setOffset(offset)
                    .build(),
            ),
        )

    /** Callback-button press; returns the bot's toast text when the answer carries one. */
    suspend fun inlineCallback(peer: Peer, message: MessageReference, data: String, exPeerType: Int): String {
        val resp = Ketf_SendInlineCallback_Response.parseFrom(
            call(
                KETF,
                "SendInlineCallback",
                Ketf_SendInlineCallback_Request.newBuilder()
                    .setPeer(peer.exWire(exPeerType))
                    .setMessageId(messageId(message))
                    .setData(StringValue.of(data))
                    .build(),
            ),
        )
        return if (resp.hasAnswer()) resp.answer.text else ""
    }

    suspend fun menuButton(botUserId: Int): MenuButton2? {
        val resp = Appzar_GetMenuButton_Response.parseFrom(
            call(APPZAR, "GetMenuButton", Appzar_GetMenuButton_Request.newBuilder().setBotUserId(botUserId).build()),
        )
        return if (resp.hasMenuButton()) resp.menuButton else null
    }

    /** Resolves the launch URL for a bot Mini-App; falls back to the button-provided URL. */
    suspend fun miniAppUrl(botUserId: Int, contextPeer: Peer, contextExType: Int, startParam: String, fallback: String = ""): Pair<String, String> {
        val builder = Appzar_GetMiniAppUrl_Request.newBuilder().setBotUserId(botUserId).setScreenMode(1)
        if (startParam.isNotBlank()) builder.setMain(
            Main.newBuilder().setStartParam(StringValue.of(startParam)).setPeer(contextPeer.exWire(contextExType)),
        )
        val resp = Appzar_GetMiniAppUrl_Response.parseFrom(call(APPZAR, "GetMiniAppUrl", builder.build()))
        val url = resp.url.ifBlank { fallback }
        return url to if (resp.hasQueryId()) resp.queryId.value else ""
    }

    suspend fun sendMiniAppData(botUserId: Int, queryId: String, data: String, buttonText: String) {
        call(
            KETF,
            "SendMiniAppData",
            Ketf_SendMiniAppData_Request.newBuilder()
                .setBotUserId(botUserId)
                .setQueryId(StringValue.of(queryId))
                .setData(StringValue.of(data))
                .setButtonText(StringValue.of(buttonText))
                .build(),
        )
    }

    suspend fun recommendedBots(): List<Bots> =
        Garson_GetRecommendedBots_Response.parseFrom(
            call(GARSON, "GetRecommendedBots", Garson_GetRecommendedBots_Request.newBuilder().build()),
        ).botsList

    // --- Moderation (`bale.report.v1.Report`) ---
    suspend fun reportContent(peer: Peer, exPeerType: Int, message: MessageReference?, description: String) {
        val builder = Report.newBuilder()
            .setKind(1)
            .setDescription(description.trim().ifBlank { "Reported from Android client" })
        if (message != null) {
            builder.setMessageReport(MessageReport.newBuilder().setPeer(peer.exWire(exPeerType)).addMids(messageId(message)))
        } else {
            builder.setPeerReport(PeerReport.newBuilder().setSource(1).setPeer(peer.exWire(exPeerType)))
        }
        call(REPORT, "ReportInappropriateContent", Report_ReportInappropriateContent_Request.newBuilder().setReport(builder).build())
    }

    companion object {
        const val IMAGES = "bale.v1.Images"
        const val SCHEDULER = "bale.schedule.v1.Scheduler"
        const val SHARED_MEDIA = "bale.shared_media.v1.SharedMediaService"
        const val TLDR = "bale.tldr.v1.TLDR"
        const val AI_SERVICE = "bale.turing.v1.AI"
        const val KETF = "bale.ketf.v1.Ketf"
        const val APPZAR = "bale.appzar.v1.Appzar"
        const val GARSON = "bale.garson.v1.Garson"
        const val REPORT = "bale.report.v1.Report"

        fun location(fileId: Long, accessHash: Long, storageVersion: Int = 0): FileLocation {
            val builder = FileLocation.newBuilder().setFileId(fileId).setAccessHash(accessHash)
            if (storageVersion != 0) builder.setFileStorageVersion(Int32Value.of(storageVersion))
            return builder.build()
        }

        fun messageId(reference: MessageReference): MessageId = MessageId.newBuilder()
            .setRid(reference.rid).setDate(reference.date).setSeq(reference.sequence).build()

        fun timestampOf(millis: Long): Timestamp = Timestamp.newBuilder()
            .setSeconds(millis / 1_000L)
            .setNanos(((millis % 1_000L) * 1_000_000L).toInt())
            .build()

        /**
         * Stickers are referenced by id + owning collection; the server resolves the binary from
         * its store. `image256`/`animation` carry `ImageLocation` file references on the wire (see
         * the web `$.rF` codec), never raw bytes, and `format` is the sticker's own stored format.
         */
        fun stickerMessage(sticker: StickerItem): Message2 {
            val builder = StickerMessage.newBuilder()
                .setStickerId(Int32Value.of(sticker.id))
                .setStickerCollectionId(Int32Value.of(sticker.collectionId))
                .setStickerCollectionAccessHash(Int64Value.of(sticker.collectionAccessHash))
            if (sticker.format != 0) builder.setFormat(sticker.format)
            // The server resolves a send through the sticker's stored file reference: echo the
            // exact ImageLocation (animated packs use `animation`, static ones `image256`).
            if (sticker.fileId != 0L) {
                val location = ImageLocation.newBuilder()
                    .setFileLocation(
                        FileLocation.newBuilder()
                            .setFileId(sticker.fileId)
                            .setAccessHash(sticker.accessHash)
                            .apply { if (sticker.storageVersion != 0) setFileStorageVersion(Int32Value.of(sticker.storageVersion)) },
                    )
                    .setWidth(sticker.width)
                    .setHeight(sticker.height)
                    .setFileSize(sticker.sizeBytes)
                if (sticker.animated) builder.setAnimation(location) else builder.setImage256(location)
            }
            return Message2.newBuilder().setStickerMessage(builder).build()
        }

        fun gifMessage(gif: SavedGif): Message2 {
            val doc = DocumentMessage.newBuilder()
                .setFileId(gif.fileId)
                .setAccessHash(gif.accessHash)
                .setFileSize(gif.sizeBytes.toInt())
                .setName("animation.mp4")
                .setMimeType(gif.mimeType.ifBlank { "video/mp4" })
                .setExt(
                    Extra2.newBuilder().setDocumentExGif(
                        DocumentExGif.newBuilder().setW(gif.width).setH(gif.height).setDuration(gif.durationSeconds),
                    ),
                )
            if (gif.storageVersion != 0) doc.setFileStorageVersion(Int32Value.of(gif.storageVersion))
            if (gif.thumbnail.isNotEmpty()) {
                doc.setThumb(Thumb.newBuilder().setW(gif.width).setH(gif.height).setThumb(ByteString.copyFrom(gif.thumbnail)))
            }
            return Message2.newBuilder().setDocumentMessage(doc).build()
        }

        fun SavedGif.toAttachment(): Attachment = Attachment(
            fileId = fileId,
            accessHash = accessHash,
            storageVersion = storageVersion,
            name = "animation.mp4",
            mimeType = mimeType.ifBlank { "video/mp4" },
            sizeBytes = sizeBytes.toInt(),
            kind = AttachmentKind.Video,
            durationSeconds = durationSeconds,
            width = width,
            height = height,
            thumbnail = thumbnail,
        )
    }
}
