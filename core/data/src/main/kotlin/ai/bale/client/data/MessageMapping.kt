package ai.bale.client.data

import ai.bale.client.domain.*
import ai.bale.client.protocol.*
import org.json.JSONObject

internal fun Avatar.toRefOrNull(): AvatarRef? {
    val location = when {
        hasSmallImage() && smallImage.hasFileLocation() && smallImage.fileLocation.fileId != 0L -> smallImage.fileLocation
        hasLargeImage() && largeImage.hasFileLocation() && largeImage.fileLocation.fileId != 0L -> largeImage.fileLocation
        hasFullImage() && fullImage.hasFileLocation() && fullImage.fileLocation.fileId != 0L -> fullImage.fileLocation
        else -> null
    } ?: return null
    return AvatarRef(location.fileId, location.accessHash)
}

internal fun Users.avatarRefOrNull(): AvatarRef? = if (hasAvatar()) avatar.toRefOrNull() else null

internal fun MessageEntity.history(): History = if (metadata.isEmpty()) History.getDefaultInstance() else
    try { History.parseFrom(metadata) } catch (_: com.google.protobuf.InvalidProtocolBufferException) { History.getDefaultInstance() }

/**
 * Official Bale Web (`H7` in `index.fe3fa18088.js`): every forwarded message sets
 * `message.emptyMessage` on the outer envelope and places the forwarded `Message2`
 * inside `quotedMessage.quotedMessageContent`.
 */
internal fun isForwardedHistory(history: History): Boolean =
    history.message.hasEmptyMessage() && history.hasQuotedMessage()

internal fun effectiveHistoryMessage(history: History): Message2 =
    if (isForwardedHistory(history)) history.quotedMessage.quotedMessageContent else history.message

internal fun MessageEntity.domain(): ChatMessage {
    val history = history()
    val forwarded = isForwardedHistory(history)
    val forwardedInfo = if (forwarded) {
        val quoted = history.quotedMessage
        ForwardedInfo(
            senderUserId = quoted.senderUserId,
            publicGroupId = quoted.publicGroupId.value,
            authorName = quoted.authorSign.value,
        )
    } else null
    val quote = if (!forwarded && history.hasQuotedMessage()) {
        val quoted = history.quotedMessage
        QuotePreview(quoted.messageId.value, quoted.senderUserId, contentText(quoted.quotedMessageContent))
    } else null
    val rawMessage = effectiveHistoryMessage(history)
    val unwrapped = unwrapMessage(rawMessage)
    val template = if (rawMessage.hasTemplateMessage()) rawMessage.templateMessage else unwrapped.takeIf { it.hasTemplateMessage() }?.templateMessage
    val resolvedText = if (deleted) "" else text.ifBlank { contentText(rawMessage) }
    val resolvedSupported = supported || isSupportedMessage(rawMessage)
    val sticker = if (!deleted && unwrapped.hasStickerMessage()) unwrapped.stickerMessage else null
    return ChatMessage(
        peerKey = peerKey,
        rid = rid,
        senderId = senderId,
        text = resolvedText,
        date = date,
        delivery = Delivery.valueOf(delivery),
        supported = resolvedSupported,
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
        forwardedFrom = if (deleted) null else forwardedInfo,
        repliesCount = if (history.hasReplies()) history.replies.repliesCount else 0,
        stickerCollectionId = sticker?.stickerCollectionId?.value ?: 0,
        stickerCollectionAccessHash = sticker?.stickerCollectionAccessHash?.value ?: 0L,
    )
}

internal fun unwrapMessage(message: Message2): Message2 {
    val unstreamed = if (message.hasStreamedMessage() && message.streamedMessage.hasMessage()) {
        message.streamedMessage.message
    } else {
        message
    }
    return if (unstreamed.hasTemplateMessage() && unstreamed.templateMessage.hasGeneralMessage()) {
        unstreamed.templateMessage.generalMessage
    } else {
        unstreamed
    }
}

internal fun isSupportedMessage(raw: Message2): Boolean {
    val inner = unwrapMessage(raw)
    return inner.hasTextMessage() || inner.hasLongTextMessage() || inner.hasServiceMessage() ||
        inner.hasDocumentMessage() || inner.hasPollMessage() || inner.hasStickerMessage() ||
        inner.hasAnimatedStickerMessage() || inner.hasJsonMessage() ||
        inner.hasGiftPacketMessage() || inner.hasGoldGiftPacketMessage() ||
        inner.hasPurchaseMessage() || inner.hasCrowdFundingMessage() ||
        inner.hasBankMessage() || inner.hasOrderMessage() ||
        inner.hasLiveMessage() || inner.hasStory() ||
        (raw.hasTemplateMessage() && extractButtons(raw.templateMessage).isNotEmpty())
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
        message.hasJsonMessage() -> formatJsonMessage(message.jsonMessage.rawJson)
        message.hasGiftPacketMessage() -> {
            val gift = message.giftPacketMessage
            val note = gift.regarding.value
            if (note.isNotBlank()) "🎁 Gift Packet · $note" else "🎁 Gift Packet (${gift.giftCount} gifts)"
        }
        message.hasGoldGiftPacketMessage() -> {
            val gift = message.goldGiftPacketMessage
            if (gift.description.isNotBlank()) "🪙 Gold Gift · ${gift.description}" else "🪙 Gold Gift Packet"
        }
        message.hasPurchaseMessage() -> {
            val purchase = message.purchaseMessage
            val innerText = if (purchase.hasMsg()) contentText(purchase.msg) else ""
            if (innerText.isNotBlank()) "💳 $innerText" else "💳 Payment Request (${purchase.amount} Rials)"
        }
        message.hasCrowdFundingMessage() -> {
            val fund = message.crowdFundingMessage
            listOf("🤝 ${fund.title}", fund.description).filter { it.isNotBlank() }.joinToString("\n")
        }
        message.hasBankMessage() -> "🏦 Bank Receipt"
        message.hasOrderMessage() -> "🛍️ Order Message"
        message.hasLiveMessage() -> "🔴 Live Stream"
        message.hasStory() -> "📸 Shared Story"
        else -> ""
    }
}

private fun formatJsonMessage(rawJson: String): String {
    val trimmed = rawJson.trim()
    if (trimmed.isEmpty()) return "Structured message"
    return runCatching {
        val obj = JSONObject(trimmed)
        val data = obj.optJSONObject("data") ?: obj
        val dataType = obj.optString("dataType", data.optString("type", "")).lowercase()
        when {
            "location" in dataType || (data.has("latitude") && data.has("longitude")) || (data.has("lat") && data.has("lon")) -> {
                val lat = data.optDouble("latitude", data.optDouble("lat", 0.0))
                val lon = data.optDouble("longitude", data.optDouble("lon", 0.0))
                "📍 Location ($lat, $lon)\nhttps://maps.google.com/?q=$lat,$lon"
            }
            "contact" in dataType || data.has("phone") || data.has("phoneNumber") -> {
                val name = data.optString("name", data.optString("fullName", "Contact"))
                val phone = data.optString("phone", data.optString("phoneNumber", ""))
                if (phone.isNotBlank()) "👤 $name · $phone" else "👤 $name"
            }
            data.optString("text").isNotBlank() -> data.optString("text")
            data.optString("title").isNotBlank() -> {
                val description = data.optString("description", "")
                if (description.isNotBlank()) "${data.optString("title")}\n$description" else data.optString("title")
            }
            else -> trimmed.take(300)
        }
    }.getOrDefault(trimmed.take(300))
}

internal fun History.pollMessageOrNull(): PollMessage? {
    val unwrapped = unwrapMessage(effectiveHistoryMessage(this))
    return if (unwrapped.hasPollMessage()) unwrapped.pollMessage else null
}

internal fun History.documentMessageOrNull(): DocumentMessage? {
    val unwrapped = unwrapMessage(effectiveHistoryMessage(this))
    return if (unwrapped.hasDocumentMessage()) unwrapped.documentMessage else null
}

internal fun History.withPollResult(result: PollResult): History {
    if (isForwardedHistory(this)) {
        val forwardedContent = quotedMessage.quotedMessageContent
        val updatedContent = forwardedContent.withInnerPollResult(result) ?: return this
        return toBuilder()
            .setQuotedMessage(quotedMessage.toBuilder().setQuotedMessageContent(updatedContent))
            .build()
    }
    val updatedContent = message.withInnerPollResult(result) ?: return this
    return toBuilder().setMessage(updatedContent).build()
}

private fun Message2.withInnerPollResult(result: PollResult): Message2? {
    if (hasPollMessage()) {
        return toBuilder().setPollMessage(pollMessage.toBuilder().setResult(result)).build()
    }
    if (hasTemplateMessage() && templateMessage.hasGeneralMessage() && templateMessage.generalMessage.hasPollMessage()) {
        val inner = templateMessage.generalMessage.toBuilder()
            .setPollMessage(templateMessage.generalMessage.pollMessage.toBuilder().setResult(result))
        return toBuilder().setTemplateMessage(templateMessage.toBuilder().setGeneralMessage(inner)).build()
    }
    return null
}

/**
 * Keeps client-side state that `LoadHistory`/socket updates do not always carry: a reply quote,
 * hydrated poll tallies, the thread counter and reactions. Cached rows are re-decoded on read,
 * so previously stored forwarded or poll messages upgrade without a schema migration.
 */
internal fun mergeHistoryPreservingState(old: History?, incoming: History): History {
    if (old == null) return incoming
    var merged = if (!incoming.hasQuotedMessage() && old.hasQuotedMessage()) {
        incoming.toBuilder().setQuotedMessage(old.quotedMessage).build()
    } else {
        incoming
    }
    val incomingPoll = merged.pollMessageOrNull()
    val oldPoll = old.pollMessageOrNull()
    if (incomingPoll != null && !incomingPoll.hasResult() && oldPoll != null && oldPoll.hasResult()) {
        merged = merged.withPollResult(oldPoll.result)
    }
    if (!incoming.hasReplies() && old.hasReplies()) {
        merged = merged.toBuilder().setReplies(old.replies).build()
    }
    if (incoming.reactionsCount == 0 && old.reactionsCount > 0) {
        merged = merged.toBuilder().addAllReactions(old.reactionsList).build()
    }
    return merged
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
            else -> inferAttachmentKind(doc.mimeType, doc.name)
        }
        val width = when {
            ext.hasDocumentExPhoto() -> ext.documentExPhoto.w
            ext.hasDocumentExVideo() -> ext.documentExVideo.w
            ext.hasDocumentExGif() -> ext.documentExGif.w
            doc.hasThumb() -> doc.thumb.w
            else -> 0
        }
        val height = when {
            ext.hasDocumentExPhoto() -> ext.documentExPhoto.h
            ext.hasDocumentExVideo() -> ext.documentExVideo.h
            ext.hasDocumentExGif() -> ext.documentExGif.h
            doc.hasThumb() -> doc.thumb.h
            else -> 0
        }
        val duration = when {
            ext.hasDocumentExVoice() -> ext.documentExVoice.duration
            ext.hasDocumentExVideo() -> ext.documentExVideo.duration
            ext.hasDocumentExGif() -> ext.documentExGif.duration
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
    message.hasCrowdFundingMessage() && message.crowdFundingMessage.hasPhoto() ->
        extractAttachment(Message2.newBuilder().setDocumentMessage(message.crowdFundingMessage.photo).build())
    message.hasStickerMessage() -> {
        val sticker = message.stickerMessage
        val preview = when {
            sticker.image256.size() > 0 -> sticker.image256.toByteArray()
            sticker.image512.size() > 0 -> sticker.image512.toByteArray()
            else -> sticker.fastPreview.value.toByteArray()
        }
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
            val mapped = row.inlineListList.filter { it.text.isNotBlank() }.map { button ->
                when {
                    button.hasWebApp() && button.webApp.url.isNotBlank() ->
                        BotButton(button.text, button.webApp.url, BotButtonKind.MiniApp, template.templateMessageId)
                    button.hasUrl() && button.url.value.isNotBlank() ->
                        BotButton(button.text, button.url.value, BotButtonKind.OpenUrl, template.templateMessageId)
                    button.hasCopyText() && button.copyText.text.isNotBlank() ->
                        BotButton(button.text, button.copyText.text, BotButtonKind.CopyText, template.templateMessageId)
                    button.hasCallbackData() ->
                        BotButton(button.text, button.callbackData.value, BotButtonKind.Callback, template.templateMessageId)
                    else ->
                        BotButton(button.text, button.text, BotButtonKind.ReplyText, template.templateMessageId)
                }
            }
            if (mapped.isNotEmpty()) rows += mapped
        }
    }
    if (template.hasReplyKeyboardMarkup()) {
        template.replyKeyboardMarkup.listOfKeyboardListList.forEach { row ->
            val mapped = row.keyboardListList.filter { it.text.isNotBlank() }.map { button ->
                BotButton(button.text, button.text, BotButtonKind.ReplyText, template.templateMessageId)
            }
            if (mapped.isNotEmpty()) rows += mapped
        }
    }
    if (rows.isEmpty() && template.btnListCount > 0) {
        template.btnListList.filter { it.text.isNotBlank() }.forEach { button ->
            val kind = if (button.value.startsWith("https://")) BotButtonKind.OpenUrl else BotButtonKind.Callback
            rows += listOf(BotButton(button.text, button.value.ifBlank { button.text }, kind, template.templateMessageId))
        }
    }
    return rows
}

internal fun History.entity(account: Int, peerKey: String): MessageEntity {
    val effective = effectiveHistoryMessage(this)
    return MessageEntity(
        account, peerKey, rid, senderUid, contentText(effective), date, Delivery.Sent.name, isSupportedMessage(effective), toByteArray(),
    )
}

/** `SharedMediaService/MediaResults` carries no `History` envelope, so synthesise an equivalent one. */
internal fun MediaResults.toHistory(): History = History.newBuilder()
    .setRid(rid)
    .setDate(date)
    .setSenderUid(senderId)
    .setMessage(content)
    .build()

internal fun MediaResults.entity(account: Int, peerKey: String): MessageEntity = toHistory().entity(account, peerKey)
