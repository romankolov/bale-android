package ai.bale.client.data

import ai.bale.client.domain.*
import ai.bale.client.protocol.*

internal fun MessageEntity.history(): History = if (metadata.isEmpty()) History.getDefaultInstance() else
    try { History.parseFrom(metadata) } catch (_: com.google.protobuf.InvalidProtocolBufferException) { History.getDefaultInstance() }

internal fun MessageEntity.domain(): ChatMessage {
    val history = history()
    val quote = history.quotedMessage.takeIf { history.hasQuotedMessage() }?.let {
        QuotePreview(it.messageId.value, it.senderUserId, contentText(it.quotedMessageContent))
    }
    val rawMessage = history.message
    val template = if (rawMessage.hasTemplateMessage()) rawMessage.templateMessage else null
    val unwrapped = if (template != null && template.hasGeneralMessage()) template.generalMessage else rawMessage
    return ChatMessage(
        peerKey = peerKey,
        rid = rid,
        senderId = senderId,
        text = text,
        date = date,
        delivery = Delivery.valueOf(delivery),
        supported = supported,
        sequence = history.seq.value,
        quote = quote,
        edited = history.hasEditedAt() && history.editedAt.value > 0,
        received = received,
        read = read,
        deleted = deleted,
        reactions = history.reactionsList.filter { it.code.isNotBlank() }.mapNotNull { reaction ->
            val count = reaction.cardinality.value.takeIf { it > 0 } ?: reaction.usersCount.toLong()
            if (count <= 0) null else Reaction(reaction.code, count, accountId in reaction.usersList)
        },
        attachment = if (deleted) null else extractAttachment(unwrapped),
        poll = if (deleted) null else extractPoll(unwrapped),
        buttons = if (deleted || template == null) emptyList() else extractButtons(template),
        serviceNotice = !deleted && unwrapped.hasServiceMessage(),
    )
}

internal fun unwrapMessage(message: Message2): Message2 =
    if (message.hasTemplateMessage() && message.templateMessage.hasGeneralMessage()) message.templateMessage.generalMessage else message

internal fun isSupportedMessage(raw: Message2): Boolean {
    val inner = unwrapMessage(raw)
    return inner.hasTextMessage() || inner.hasLongTextMessage() || inner.hasServiceMessage() ||
        inner.hasDocumentMessage() || inner.hasPollMessage() || inner.hasStickerMessage() ||
        inner.hasAnimatedStickerMessage() || (raw.hasTemplateMessage() && extractButtons(raw.templateMessage).isNotEmpty())
}

internal fun contentText(raw: Message2): String {
    val message = unwrapMessage(raw)
    return when {
        message.hasTextMessage() -> message.textMessage.text
        message.hasLongTextMessage() -> message.longTextMessage.text
        message.hasServiceMessage() -> message.serviceMessage.text
        message.hasDocumentMessage() ->
            if (message.documentMessage.hasCaption() && message.documentMessage.caption.text.isNotBlank()) {
                message.documentMessage.caption.text
            } else {
                message.documentMessage.name
            }
        message.hasPollMessage() -> message.pollMessage.question
        message.hasStickerMessage() || message.hasAnimatedStickerMessage() -> "Sticker"
        else -> ""
    }
}

private fun extractAttachment(message: Message2): Attachment? = when {
    message.hasDocumentMessage() -> {
        val doc = message.documentMessage
        val ext = doc.ext
        val kind = when {
            ext.hasDocumentExPhoto() -> AttachmentKind.Photo
            ext.hasDocumentExVideo() || ext.hasDocumentExGif() -> AttachmentKind.Video
            ext.hasDocumentExVoice() -> AttachmentKind.Voice
            ext.hasDocumentExAudio() -> AttachmentKind.Audio
            else -> inferAttachmentKind(doc.mimeType)
        }
        val width = when {
            ext.hasDocumentExPhoto() -> ext.documentExPhoto.w
            ext.hasDocumentExVideo() -> ext.documentExVideo.w
            doc.hasThumb() -> doc.thumb.w
            else -> 0
        }
        val height = when {
            ext.hasDocumentExPhoto() -> ext.documentExPhoto.h
            ext.hasDocumentExVideo() -> ext.documentExVideo.h
            doc.hasThumb() -> doc.thumb.h
            else -> 0
        }
        val duration = when {
            ext.hasDocumentExVoice() -> ext.documentExVoice.duration
            ext.hasDocumentExVideo() -> ext.documentExVideo.duration
            ext.hasDocumentExAudio() -> ext.documentExAudio.duration
            else -> 0
        }
        Attachment(
            fileId = doc.fileId,
            accessHash = doc.accessHash,
            storageVersion = if (doc.hasFileStorageVersion()) doc.fileStorageVersion.value else 0,
            name = doc.name.ifBlank { "attachment" },
            mimeType = doc.mimeType.ifBlank { "application/octet-stream" },
            sizeBytes = doc.fileSize,
            kind = kind,
            durationSeconds = duration,
            width = width,
            height = height,
            thumbnail = if (doc.hasThumb()) doc.thumb.thumb.toByteArray() else byteArrayOf(),
        )
    }
    message.hasStickerMessage() -> {
        val sticker = message.stickerMessage
        val preview = sticker.image256.toByteArray()
            .ifEmpty { sticker.image512.toByteArray().ifEmpty { sticker.fastPreview.value.toByteArray() } }
        Attachment(0, 0, 0, "Sticker", "image/webp", preview.size, AttachmentKind.Sticker, thumbnail = preview)
    }
    message.hasAnimatedStickerMessage() -> {
        val preview = message.animatedStickerMessage.fastPreview.value.toByteArray()
        Attachment(0, 0, 0, "Sticker", "application/tgs", preview.size, AttachmentKind.Sticker, thumbnail = preview)
    }
    else -> null
}

private fun extractPoll(message: Message2): PollView? {
    if (!message.hasPollMessage()) return null
    val poll = message.pollMessage
    val counts = poll.result.optionResultsList.associate { it.optionId to it.votesCount }
    val chosen = poll.result.chosenOptionIdsList.map(Long::toInt).toSet()
    return PollView(
        pollId = poll.pollId,
        question = poll.question,
        options = poll.optionsList.map { PollOptionView(it.id, it.text, counts[it.id] ?: 0, it.id in chosen) },
        anonymous = poll.isAnonymous,
        multipleChoice = poll.type == 1,
        closed = poll.result.isClosed,
        totalVoters = poll.result.votersCount,
    )
}

private fun extractButtons(template: TemplateMessage): List<List<BotButton>> {
    val rows = mutableListOf<List<BotButton>>()
    if (template.hasInlineKeyboardMarkup()) {
        template.inlineKeyboardMarkup.listOfInlineListList.forEach { row ->
            val mapped = row.inlineListList.filter { it.text.isNotBlank() }.map { btn ->
                when {
                    btn.hasUrl() && btn.url.value.isNotBlank() ->
                        BotButton(btn.text, btn.url.value, BotButtonKind.OpenUrl, template.templateMessageId)
                    btn.hasCopyText() && btn.copyText.text.isNotBlank() ->
                        BotButton(btn.text, btn.copyText.text, BotButtonKind.CopyText, template.templateMessageId)
                    btn.hasCallbackData() ->
                        BotButton(btn.text, btn.callbackData.value, BotButtonKind.Callback, template.templateMessageId)
                    else ->
                        BotButton(btn.text, btn.text, BotButtonKind.ReplyText, template.templateMessageId)
                }
            }
            if (mapped.isNotEmpty()) rows += mapped
        }
    }
    if (template.hasReplyKeyboardMarkup()) {
        template.replyKeyboardMarkup.listOfKeyboardListList.forEach { row ->
            val mapped = row.keyboardListList.filter { it.text.isNotBlank() }.map { btn ->
                BotButton(btn.text, btn.text, BotButtonKind.ReplyText, template.templateMessageId)
            }
            if (mapped.isNotEmpty()) rows += mapped
        }
    }
    if (rows.isEmpty() && template.btnListCount > 0) {
        template.btnListList.filter { it.text.isNotBlank() }.forEach { btn ->
            val kind = if (btn.value.startsWith("https://")) BotButtonKind.OpenUrl else BotButtonKind.Callback
            rows += listOf(BotButton(btn.text, btn.value.ifBlank { btn.text }, kind, template.templateMessageId))
        }
    }
    return rows
}

internal fun History.entity(account: Int, peerKey: String): MessageEntity = MessageEntity(
    account, peerKey, rid, senderUid, contentText(message), date, Delivery.Sent.name, isSupportedMessage(message), toByteArray(),
)
