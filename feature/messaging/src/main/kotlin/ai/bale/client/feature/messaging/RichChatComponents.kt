package ai.bale.client.feature.messaging

import ai.bale.client.core.designsystem.R
import ai.bale.client.domain.AvatarRef
import ai.bale.client.domain.ChatFolder
import ai.bale.client.domain.ChatMessage
import ai.bale.client.domain.Conversation
import ai.bale.client.domain.SavedGif
import ai.bale.client.domain.StickerItem
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

private val stampFormatter: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

private fun formatStamp(date: Long): String =
    stampFormatter.format(Instant.ofEpochMilli(date).atZone(ZoneId.systemDefault()))

/** Real profile picture when the avatar file is cached, deterministic initial otherwise. */
@Composable
fun BaleAvatar(
    avatar: AvatarRef?,
    paths: Map<Long, String>,
    label: String,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    online: Boolean = false,
) {
    val path = avatar?.let { paths[it.fileId] }
    val bitmap = remember(path, size) { path?.let { decodeSampledFileBitmap(it, (size.value * 3).roundToInt()) } }
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.matchParentSize()) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    modifier = Modifier.matchParentSize().clip(CircleShape),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        label.trim().take(1).ifBlank { "•" },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
        if (online) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.align(Alignment.BottomEnd).size(size / 3.5f),
            ) {}
        }
    }
}

/** Rounded pill button reused by the filter strip, pickers and report reasons. */
@Composable
private fun PillCell(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    tag: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.testTag(tag),
    ) {
        Text(
            label,
            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun DialogFilter.labelRes(): Int = when (this) {
    DialogFilter.All -> R.string.filter_all
    DialogFilter.Unread -> R.string.filter_unread
    DialogFilter.Personal -> R.string.filter_personal
    DialogFilter.Groups -> R.string.filter_groups
    DialogFilter.Bots -> R.string.filter_bots
    DialogFilter.Saved -> R.string.filter_saved
    DialogFilter.Archived -> R.string.filter_archived
}

/** Chat filters plus Bale's folder tabs, with inline folder creation. */
@Composable
fun DialogFilterStrip(
    filter: DialogFilter,
    onFilter: (DialogFilter) -> Unit,
    folders: List<ChatFolder>,
    folderId: Int,
    onFolder: (Int) -> Unit,
    onCreateFolder: (String) -> Unit,
    onDeleteFolder: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var creating by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable(creating) { mutableStateOf("") }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(DialogFilter.entries.toList()) { entry ->
                PillCell(
                    label = stringResource(entry.labelRes()),
                    selected = folderId == 0 && filter == entry,
                    onClick = { onFilter(entry); onFolder(0) },
                    tag = "filter_${entry.name.lowercase()}",
                )
            }
            items(folders.filterNot { it.reserved }, key = { it.id }) { folder ->
                PillCell(
                    label = "${folder.name} (${folder.unreadCount})",
                    selected = folderId == folder.id,
                    onClick = { onFolder(if (folderId == folder.id) 0 else folder.id) },
                    tag = "folder_${folder.id}",
                )
            }
            item {
                PillCell(
                    label = stringResource(R.string.folder_new),
                    selected = false,
                    onClick = { creating = !creating },
                    tag = "folder_new",
                )
            }
        }
        if (folderId != 0) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.folder_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onDeleteFolder(folderId) }, modifier = Modifier.testTag("folder_delete")) {
                    Text(stringResource(R.string.folder_delete), color = MaterialTheme.colorScheme.error)
                }
            }
        }
        if (creating) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.folder_name)) },
                    modifier = Modifier.weight(1f).testTag("folder_name_field"),
                )
                TextButton(
                    onClick = {
                        val trimmed = name.trim()
                        if (trimmed.isNotBlank()) {
                            onCreateFolder(trimmed)
                            name = ""
                            creating = false
                        }
                    },
                    modifier = Modifier.testTag("folder_create_confirm"),
                ) { Text(stringResource(R.string.create)) }
                TextButton(onClick = { creating = false; name = "" }) { Text(stringResource(R.string.cancel)) }
            }
        }
    }
}

/** Full-screen media viewer: photos, video playback, documents, transcripts and GIF saving. */
@Composable
fun MediaViewerOverlay(
    state: ViewerState,
    onDismiss: () -> Unit,
    onOpenExternally: () -> Unit,
    onTranscribe: () -> Unit,
    onSaveGif: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = MaterialTheme.colorScheme.scrim, modifier = Modifier.fillMaxSize().testTag("media_viewer")) {
            Column(Modifier.fillMaxSize().systemBarsPadding(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            state.message.attachment?.name.orEmpty().ifBlank { stringResource(R.string.media_viewer) },
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        state.message.attachment?.let { attachment ->
                            Text(
                                "${attachment.sizeBytes / 1024} KB · ${formatStamp(state.message.date)}",
                                color = Color.White.copy(alpha = 0.72f),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                    TextButton(onClick = onOpenExternally, modifier = Modifier.testTag("viewer_open_external")) {
                        Text(stringResource(R.string.open_externally), color = Color.White)
                    }
                    if (state.canTranscribe) {
                        TextButton(onClick = onTranscribe, modifier = Modifier.testTag("viewer_transcribe")) {
                            Text(if (state.transcribing) stringResource(R.string.busy) else stringResource(R.string.transcribe_voice), color = Color.White)
                        }
                    }
                    TextButton(onClick = onSaveGif, modifier = Modifier.testTag("viewer_save_gif")) {
                        Text(stringResource(R.string.save_gif), color = Color.White)
                    }
                    TextButton(onClick = onDismiss, modifier = Modifier.testTag("viewer_close")) {
                        Text(stringResource(R.string.close_panel), color = Color.White)
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    when {
                        state.busy -> CircularProgressIndicator(color = Color.White)
                        state.failed -> Text(stringResource(R.string.viewer_failed), color = Color.White)
                        state.isImage -> {
                            val bitmap = remember(state.path) { decodeSampledFileBitmap(state.path, 1600) }
                            if (bitmap != null) {
                                Image(bitmap, null, Modifier.fillMaxSize().padding(8.dp), contentScale = ContentScale.Fit)
                            } else {
                                Text(stringResource(R.string.viewer_failed), color = Color.White)
                            }
                        }
                        state.isVideo && state.path.isNotBlank() -> VideoSurface(state.path)
                        else -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                state.message.attachment?.name.orEmpty().ifBlank { stringResource(R.string.unsupported_message) },
                                color = Color.White,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Button(onClick = onOpenExternally) { Text(stringResource(R.string.open_externally)) }
                        }
                    }
                }
                if (state.transcript != null || state.transcriptFailed) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("viewer_transcript"),
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(stringResource(R.string.transcript_title), style = MaterialTheme.typography.labelLarge)
                            Text(
                                state.transcript ?: stringResource(R.string.transcript_failed),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
                if (state.message.text.isNotBlank()) {
                    Text(
                        state.message.text,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun VideoSurface(path: String, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { context: Context ->
            VideoView(context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setVideoURI(Uri.fromFile(File(path)))
                setOnPreparedListener { it.isLooping = true; start() }
                setOnErrorListener { _, _, _ -> true }
            }
        },
        onRelease = { view -> view.stopPlayback() },
        modifier = modifier,
    )
}

/** Thread / channel-comments sheet with an inline reply field. */
@Composable
fun ThreadOverlay(
    state: ThreadState,
    meId: Int?,
    onSend: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by rememberSaveable(state.root.rid) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("thread_sheet"),
        title = { Text(stringResource(R.string.thread_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.thread_replies, state.replies.size), style = MaterialTheme.typography.labelMedium)
                BubbleLine(state.root, meId)
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.failed) Text(stringResource(R.string.thread_failed), style = MaterialTheme.typography.bodySmall)
                if (!state.busy && !state.failed && state.replies.isEmpty()) {
                    Text(stringResource(R.string.thread_empty), style = MaterialTheme.typography.bodySmall)
                }
                LazyColumn(Modifier.heightIn(max = 240.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(state.replies, key = { it.rid }) { reply -> BubbleLine(reply, meId) }
                }
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text(stringResource(R.string.thread_placeholder)) },
                    modifier = Modifier.fillMaxWidth().testTag("thread_input"),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val text = draft.trim()
                    if (text.isNotBlank()) { onSend(text); draft = "" }
                },
                modifier = Modifier.testTag("thread_send"),
            ) { Text(stringResource(R.string.send)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close_panel)) } },
    )
}

@Composable
private fun BubbleLine(message: ChatMessage, meId: Int?) {
    val mine = message.senderId == meId
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Surface(
            color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (!mine && message.senderName.isNotBlank()) {
                    Text(message.senderName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                Text(message.text.ifBlank { stringResource(R.string.unsupported_message) }, style = MaterialTheme.typography.bodyMedium)
                Text(formatStamp(message.date), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Scheduling sheet: quick delays for the current draft plus the pending task list. */
@Composable
fun ScheduleOverlay(
    state: ScheduleState,
    hasDraft: Boolean,
    onPick: (Long) -> Unit,
    onSendNow: (Long) -> Unit,
    onCancel: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("schedule_sheet"),
        title = { Text(stringResource(R.string.schedule_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (hasDraft) stringResource(R.string.schedule_hint) else stringResource(R.string.schedule_needs_draft),
                    style = MaterialTheme.typography.bodySmall,
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(SCHEDULE_PRESET_MINUTES) { minutes ->
                        PillCell(
                            label = stringResource(R.string.schedule_in_minutes, minutes),
                            selected = false,
                            onClick = { if (hasDraft) onPick(minutes) },
                            tag = "schedule_pick_$minutes",
                        )
                    }
                }
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.failed) Text(stringResource(R.string.schedule_failed), style = MaterialTheme.typography.bodySmall)
                if (!state.busy && state.items.isEmpty()) Text(stringResource(R.string.schedule_empty), style = MaterialTheme.typography.bodySmall)
                state.items.forEach { task ->
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(task.text, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    stringResource(if (task.status == 1) R.string.schedule_status_sent else R.string.schedule_status_waiting),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            TextButton(onClick = { onSendNow(task.taskId) }, modifier = Modifier.testTag("schedule_send_${task.taskId}")) {
                                Text(stringResource(R.string.schedule_send_now))
                            }
                            TextButton(onClick = { onCancel(task.taskId) }, modifier = Modifier.testTag("schedule_cancel_${task.taskId}")) {
                                Text(stringResource(R.string.schedule_cancel), color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close_panel)) } },
    )
}

/** Sticker packs and saved GIFs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickerOverlay(
    state: StickerPickerState,
    onMode: (PickerMode) -> Unit,
    onSendSticker: (StickerItem) -> Unit,
    onSendGif: (SavedGif) -> Unit,
    onRemoveGif: (SavedGif) -> Unit,
    onDismiss: () -> Unit,
) {
    val mode = state.mode ?: PickerMode.Stickers
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("picker_sheet")) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                PillCell(stringResource(R.string.picker_stickers), mode == PickerMode.Stickers, { onMode(PickerMode.Stickers) }, "picker_tab_stickers")
                PillCell(stringResource(R.string.picker_gifs), mode == PickerMode.Gifs, { onMode(PickerMode.Gifs) }, "picker_tab_gifs")
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.close_panel)) }
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.failed) Text(stringResource(R.string.picker_failed), style = MaterialTheme.typography.bodySmall)
            if (!state.busy && !state.failed && mode == PickerMode.Stickers && state.packs.isEmpty()) {
                Text(stringResource(R.string.picker_empty_stickers), style = MaterialTheme.typography.bodySmall)
            }
            if (!state.busy && !state.failed && mode == PickerMode.Gifs && state.gifs.isEmpty()) {
                Text(stringResource(R.string.picker_empty_gifs), style = MaterialTheme.typography.bodySmall)
            }
            LazyColumn(Modifier.heightIn(max = 340.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                state.packs.forEach { pack ->
                    item(key = "pack_${pack.id}") {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(pack.name, style = MaterialTheme.typography.labelLarge)
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(pack.stickers, key = { it.id }) { sticker ->
                                    Surface(
                                        onClick = { onSendSticker(sticker) },
                                        shape = RoundedCornerShape(14.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                                        modifier = Modifier.size(64.dp).testTag("sticker_${pack.id}_${sticker.id}"),
                                    ) {
                                        val preview = remember(sticker) {
                                            if (sticker.previewBytes.isNotEmpty()) decodeSampledBitmap(sticker.previewBytes, 128) else null
                                        }
                                        if (preview != null) {
                                            Image(preview, null, Modifier.fillMaxSize(), contentScale = ContentScale.Inside)
                                        } else {
                                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                                Text(sticker.emoji.ifBlank { "🙂" }, style = MaterialTheme.typography.headlineSmall)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (mode == PickerMode.Gifs) {
                    items(state.gifs, key = { it.fileId }) { gif ->
                        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        "${gif.width}×${gif.height} · ${gif.sizeBytes / 1024} KB",
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Text(
                                        if (gif.usedAt > 0) formatStamp(gif.usedAt) else stringResource(R.string.gif_unused),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                TextButton(onClick = { onSendGif(gif) }, modifier = Modifier.testTag("gif_send_${gif.fileId}")) {
                                    Text(stringResource(R.string.send))
                                }
                                TextButton(onClick = { onRemoveGif(gif) }, modifier = Modifier.testTag("gif_remove_${gif.fileId}")) {
                                    Text(stringResource(R.string.remove), color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Bale Mini-App host. The bridge exposes the small `BaleAndroid` object plus a `Telegram.WebApp`
 * compatibility shim so existing mini-apps keep working inside the client.
 */
@Composable
fun MiniAppOverlay(
    state: MiniAppState,
    onSubmitQuery: (String, String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize().testTag("mini_app"), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.miniapp_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.close_panel)) }
                }
                LinearProgressIndicator(Modifier.fillMaxWidth())
                MiniAppWebView(
                    url = state.session.url,
                    onClose = onDismiss,
                    onSendData = { data -> onSubmitQuery(state.session.queryId, data, "") },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

private const val MINI_APP_SHIM = """
javascript:(function(){
  var api = window.BaleAndroid || {};
  window.Telegram = window.Telegram || {};
  window.Telegram.WebApp = window.Telegram.WebApp || {};
  var app = window.Telegram.WebApp;
  app.sendData = function(data){ if (api.sendData) api.sendData(String(data)); };
  app.close = function(){ if (api.close) api.close(); };
  app.ready = function(){ if (api.ready) api.ready(); };
  app.expand = function(){ if (api.expand) api.expand(); };
  app.isVersionAtLeast = function(){ return true; };
})();
"""

@Composable
private fun MiniAppWebView(
    url: String,
    onClose: () -> Unit,
    onSendData: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val bridge = remember(onClose, onSendData) { BaleMiniAppBridge(onClose, onSendData) }
    AndroidView(
        factory = { viewContext ->
            WebView(viewContext).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, unusedUrl: String?) {
                        view?.evaluateJavascript(MINI_APP_SHIM, null)
                    }

                    override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                        val target = request?.url?.toString().orEmpty()
                        if (target.startsWith("https://") || target.startsWith("http://")) return false
                        runCatching {
                            viewContext.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                        return true
                    }
                }
                addJavascriptInterface(bridge, "BaleAndroid")
                loadUrl(url)
            }
        },
        onRelease = { view ->
            runCatching { view.removeJavascriptInterface("BaleAndroid") }
            view.destroy()
        },
        modifier = modifier,
    )
}

/** JS entry points used by bot Mini-Apps; names must stay stable, hence public members. */
class BaleMiniAppBridge(
    private val onClose: () -> Unit,
    private val onData: (String) -> Unit,
) {
    @JavascriptInterface
    fun sendData(data: String) = onData(data)

    @JavascriptInterface
    fun close() = onClose()

    @JavascriptInterface
    fun ready() = Unit

    @JavascriptInterface
    fun expand() = Unit

    @JavascriptInterface
    fun isVersionAtLeast(unusedVersion: String) = true
}

/** Composer card for the first link in the draft, with the optional AI summary. */
@Composable
fun LinkPreviewCard(
    state: LinkPreviewState?,
    onToggleSummary: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val preview = state ?: return
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth().testTag("link_preview_card"),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        preview.info?.title?.ifBlank { preview.url } ?: preview.url,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        preview.url,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(onClick = onDismiss, modifier = Modifier.testTag("link_preview_close")) { Text(stringResource(R.string.close_panel)) }
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.info?.description?.takeIf { it.isNotBlank() }?.let { description ->
                Text(description, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            if (!state.busy && state.info == null) Text(stringResource(R.string.preview_unavailable), style = MaterialTheme.typography.bodySmall)
            if (state.info?.summary?.isNotBlank() == true) {
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        state.info?.summary.orEmpty(),
                        Modifier.padding(10.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            TextButton(onClick = { onToggleSummary(!state.withSummary) }, modifier = Modifier.testTag("link_summary_toggle")) {
                Text(stringResource(if (state.withSummary) R.string.preview_hide_summary else R.string.preview_show_summary))
            }
        }
    }
}

/** Vote breakdown for polls and the list of users behind message reactions. */
@Composable
fun VotersOverlay(state: VotersState, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("voters_sheet"),
        title = { Text(state.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (!state.busy && state.failed) Text(stringResource(R.string.voters_unavailable), style = MaterialTheme.typography.bodySmall)
                state.poll.forEach { option ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("${option.optionText} · ${option.votesCount}", style = MaterialTheme.typography.labelLarge)
                        if (option.voters.isEmpty()) {
                            Text(stringResource(R.string.voters_none), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        option.voters.forEach { voter ->
                            Text(voter.name, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                state.reactions.forEach { entry ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(entry.emoji, style = MaterialTheme.typography.titleMedium)
                        Text(entry.name, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close_panel)) } },
    )
}

/** Report sheet for one message or the whole conversation. */
@Composable
fun ReportSheet(
    isMessage: Boolean,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val reasons = listOf(
        R.string.report_reason_spam,
        R.string.report_reason_harassment,
        R.string.report_reason_scam,
        R.string.report_reason_fake,
        R.string.report_reason_other,
    )
    var reason by rememberSaveable { mutableStateOf("") }
    val otherLabel = stringResource(R.string.report_reason_other)
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("report_sheet"),
        title = { Text(stringResource(if (isMessage) R.string.report_message_title else R.string.report_channel_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                reasons.forEachIndexed { index, labelRes ->
                    val label = stringResource(labelRes)
                    PillCell(label, reason == label, { reason = label }, "report_reason_$index")
                }
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text(stringResource(R.string.report_hint)) },
                    modifier = Modifier.fillMaxWidth().testTag("report_reason_field"),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(reason.ifBlank { otherLabel }) },
                modifier = Modifier.testTag("report_submit"),
            ) { Text(stringResource(R.string.report_submit)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Long-press sheet for one conversation row: pin, mute, unread, archive and folder membership. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatActionSheet(
    conversation: Conversation,
    folders: List<ChatFolder>,
    onDismiss: () -> Unit,
    onPin: () -> Unit,
    onMute: () -> Unit,
    onUnread: () -> Unit,
    onArchive: () -> Unit,
    onReport: () -> Unit,
    onFolder: (Int) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("chat_actions_sheet")) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(conversation.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = onPin, modifier = Modifier.testTag("chat_action_pin")) {
                Text(stringResource(if (conversation.pinned) R.string.unpin_chat else R.string.pin_chat))
            }
            TextButton(onClick = onMute, modifier = Modifier.testTag("chat_action_mute")) {
                Text(stringResource(if (conversation.muted) R.string.unmute_chat else R.string.mute_chat))
            }
            TextButton(onClick = onUnread, modifier = Modifier.testTag("chat_action_unread")) {
                Text(stringResource(if (conversation.markedUnread) R.string.mark_read else R.string.mark_unread))
            }
            TextButton(onClick = onArchive, modifier = Modifier.testTag("chat_action_archive")) {
                Text(stringResource(if (conversation.archived) R.string.unarchive_chat else R.string.archive_chat))
            }
            TextButton(onClick = onReport, modifier = Modifier.testTag("chat_action_report")) {
                Text(stringResource(R.string.report_chat), color = MaterialTheme.colorScheme.error)
            }
            val userFolders = folders.filterNot { it.reserved }
            if (userFolders.isNotEmpty()) {
                Text(stringResource(R.string.chat_folder_add), style = MaterialTheme.typography.labelLarge)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(userFolders, key = { it.id }) { folder ->
                        val alreadyIn = folder.peerKeys.contains(conversation.peer.key)
                        PillCell(
                            label = if (alreadyIn) "${folder.name} ✓" else folder.name,
                            selected = alreadyIn,
                            onClick = { onFolder(folder.id) },
                            tag = "chat_folder_${folder.id}",
                        )
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

/** Opens cached media with an external player/editor when the viewer cannot render it. */
internal fun openExternallyOrWarn(context: Context, path: String, mimeType: String, onUnavailable: () -> Unit) {
    if (path.isBlank()) {
        onUnavailable()
        return
    }
    try {
        openCachedMediaExternally(context, path, mimeType)
    } catch (_: ActivityNotFoundException) {
        onUnavailable()
    }
}
