package ai.bale.client.data

import ai.bale.client.domain.ChatMessage
import ai.bale.client.domain.Delivery
import ai.bale.client.domain.QuotePreview
import ai.bale.client.domain.Reaction
import ai.bale.client.protocol.History
import ai.bale.client.protocol.Message2

internal fun MessageEntity.history(): History = if (metadata.isEmpty()) History.getDefaultInstance() else
    try { History.parseFrom(metadata) } catch (_: com.google.protobuf.InvalidProtocolBufferException) { History.getDefaultInstance() }

internal fun MessageEntity.domain(): ChatMessage {
    val history = history()
    val quote = history.quotedMessage.takeIf { history.hasQuotedMessage() }?.let {
        QuotePreview(it.messageId.value, it.senderUserId, contentText(it.quotedMessageContent))
    }
    return ChatMessage(peerKey, rid, senderId, text, date, Delivery.valueOf(delivery), supported,
        sequence = history.seq.value,
        quote = quote,
        edited = history.hasEditedAt() && history.editedAt.value > 0,
        received = received, read = read, deleted = deleted,
        reactions = history.reactionsList.filter { it.code.isNotBlank() }.map { Reaction(it.code, it.cardinality.value) })
}
internal fun contentText(message: Message2): String = when {
    message.hasTextMessage() -> message.textMessage.text
    message.hasDocumentMessage() && message.documentMessage.hasCaption() -> message.documentMessage.caption.text
    else -> ""
}
internal fun History.entity(account: Int, peerKey: String): MessageEntity = MessageEntity(
    account, peerKey, rid, senderUid, contentText(message), date, Delivery.Sent.name, message.hasTextMessage(), toByteArray())
