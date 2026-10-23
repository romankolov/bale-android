package ai.bale.client.feature.messaging

import ai.bale.client.domain.ChatMessage
import ai.bale.client.domain.Conversation
import ai.bale.client.domain.Delivery
import ai.bale.client.domain.LinkPreviewInfo
import ai.bale.client.domain.PollOptionVoters
import ai.bale.client.domain.ReactionVoter
import ai.bale.client.domain.SavedGif
import ai.bale.client.domain.SharedMediaTab
import ai.bale.client.domain.ScheduledMessageItem
import ai.bale.client.domain.StickerPack

/** Top-of-list conversation filters, mirroring Bale's All / Unread / Groups / Bots tabs. */
enum class DialogFilter { All, Unread, Personal, Groups, Bots, Saved, Archived }

/** Which picker sheet is open above the composer. */
enum class PickerMode { Stickers, Gifs }

internal fun Conversation.matches(text: String, filter: DialogFilter): Boolean {
    if (text.isNotBlank() && !title.contains(text, ignoreCase = true) && !preview.contains(text, ignoreCase = true)) return false
    return when (filter) {
        DialogFilter.All -> !archived
        DialogFilter.Unread -> !archived && (unread > 0 || markedUnread)
        DialogFilter.Personal -> !archived && !isChannel && !isBot && !isSavedMessages
        DialogFilter.Groups -> !archived && (isChannel || peer.kind == 2)
        DialogFilter.Bots -> !archived && isBot
        DialogFilter.Saved -> isSavedMessages
        DialogFilter.Archived -> archived
    }
}

data class ViewerState(
    val message: ChatMessage,
    val path: String = "",
    val busy: Boolean = true,
    val failed: Boolean = false,
    val transcribing: Boolean = false,
    val transcript: String? = null,
    val transcriptFailed: Boolean = false,
) {
    val attachment get() = message.attachment
    val isImage: Boolean get() = message.attachment?.mimeType?.startsWith("image/") == true
    val isVideo: Boolean get() = message.attachment?.mimeType?.startsWith("video/") == true
    val isAudio: Boolean get() = message.attachment?.mimeType?.startsWith("audio/") == true
    val canTranscribe: Boolean
        get() = message.attachment?.mimeType == "audio/mp4" || message.attachment?.mimeType?.startsWith("audio/") == true
}

data class ThreadState(
    val root: ChatMessage,
    val replies: List<ChatMessage> = emptyList(),
    val busy: Boolean = false,
    val failed: Boolean = false,
)

data class ScheduleState(
    val items: List<ScheduledMessageItem> = emptyList(),
    val busy: Boolean = false,
    val composing: Boolean = false,
    val open: Boolean = false,
    val failed: Boolean = false,
)

data class StickerPickerState(
    val mode: PickerMode? = null,
    val packs: List<StickerPack> = emptyList(),
    val gifs: List<SavedGif> = emptyList(),
    val busy: Boolean = false,
    val failed: Boolean = false,
)

data class MiniAppState(val bot: ai.bale.client.domain.Peer, val session: ai.bale.client.domain.MiniAppSession)

data class LinkPreviewState(
    val url: String,
    val info: LinkPreviewInfo? = null,
    val busy: Boolean = false,
    val withSummary: Boolean = false,
)

data class VotersState(
    val title: String,
    val poll: List<PollOptionVoters> = emptyList(),
    val reactions: List<ReactionVoter> = emptyList(),
    val busy: Boolean = false,
    val failed: Boolean = false,
)

/** Quick-pick delays offered in the schedule sheet, in minutes. */
internal val SCHEDULE_PRESET_MINUTES = listOf(10L, 30L, 60L, 180L, 1_440L)

/**
 * First absolute URL in a message. Only trailing sentence punctuation is removed, so links that
 * legitimately end in a bracket (query strings) survive untouched.
 */
internal fun firstUrlIn(text: String): String? =
    Regex("""https?://[^\s]+""").find(text)?.value?.trimEnd('.', ',')?.trimEnd('.', ',')

internal fun isRetryableFailure(message: ChatMessage): Boolean =
    message.delivery == Delivery.Failed && message.senderId != 0

data class SharedMediaState(
    val tab: SharedMediaTab = SharedMediaTab.Media,
    val items: List<ChatMessage> = emptyList(),
    val busy: Boolean = false,
    val open: Boolean = false,
    val failed: Boolean = false,
)
