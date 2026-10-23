package ai.bale.client.feature.messaging

import ai.bale.client.core.designsystem.R
import ai.bale.client.designsystem.Notice
import ai.bale.client.domain.*
import android.content.ClipData
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessengerScreen(viewModel: MessengerViewModel = hiltViewModel()) {
    val chats by viewModel.filtered.collectAsStateWithLifecycle()
    val allChats by viewModel.conversations.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val messages = viewModel.pagedMessages.collectAsLazyPagingItems()
    val account by viewModel.account.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val queuing by viewModel.queuing.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val composer by viewModel.composer.collectAsStateWithLifecycle()
    val search by viewModel.searchState.collectAsStateWithLifecycle()
    val typing by viewModel.typingUsers.collectAsStateWithLifecycle()
    val moreHistory by viewModel.moreHistory.collectAsStateWithLifecycle()
    val moreDialogs by viewModel.moreDialogs.collectAsStateWithLifecycle()
    var confirmLogout by remember { mutableStateOf(false) }
    var action by remember(selected?.key, account?.id) { mutableStateOf<ChatMessage?>(null) }
    var deleting by remember(selected?.key) { mutableStateOf<ChatMessage?>(null) }
    var forwarding by remember(selected?.key) { mutableStateOf<ChatMessage?>(null) }
    var reacting by remember(selected?.key) { mutableStateOf<Pair<ChatMessage, String>?>(null) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    BackHandler(enabled = selected != null) { viewModel.back() }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try { awaitCancellation() } finally { viewModel.stopTyping() }
        }
    }
    if (confirmLogout) AlertDialog(
        onDismissRequest = { confirmLogout = false }, title = { Text(stringResource(R.string.logout_title)) },
        text = { Text(stringResource(R.string.logout_detail)) },
        confirmButton = { TextButton(onClick = { confirmLogout = false; viewModel.signOut() }) { Text(stringResource(R.string.logout)) } },
        dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text(stringResource(R.string.cancel)) } },
    )
    action?.let { message ->
        ModalBottomSheet(onDismissRequest = { action = null }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(message.text.ifBlank { stringResource(R.string.unsupported_message) }, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                if (message.text.isNotBlank()) TextButton(onClick = {
                    scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("message", message.text))) }
                    action = null
                }) { Text(stringResource(R.string.copy_message)) }
                if (!message.deleted && message.delivery == Delivery.Sent) {
                    TextButton(onClick = { viewModel.reply(message); action = null }) { Text(stringResource(R.string.reply_message)) }
                    if (message.senderId == account?.id && message.supported) TextButton(onClick = { viewModel.edit(message); action = null }) { Text(stringResource(R.string.edit_message)) }
                    TextButton(onClick = { forwarding = message; action = null }) { Text(stringResource(R.string.forward_message)) }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("👍", "❤️", "😂", "😮", "😢").forEach { code ->
                            TextButton(onClick = { viewModel.react(message, code, false); action = null }, modifier = Modifier.weight(1f)) { Text(code) }
                        }
                    }
                }
                if (!message.deleted && message.delivery != Delivery.Pending) TextButton(onClick = { deleting = message; action = null }) {
                    Text(stringResource(if (message.delivery == Delivery.Failed) R.string.discard_attempt else R.string.delete_message), color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
    deleting?.let { message ->
        var everyone by remember(message.rid) { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text(stringResource(R.string.delete_message)) },
            text = {
                Column {
                    Text(stringResource(if (message.delivery == Delivery.Failed) R.string.discard_detail else R.string.delete_detail))
                    if (message.senderId == account?.id && message.delivery == Delivery.Sent) Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(everyone, { everyone = it }); Text(stringResource(R.string.delete_everyone))
                    }
                }
            }, confirmButton = { TextButton(onClick = { viewModel.delete(message, everyone); deleting = null }) { Text(stringResource(R.string.delete_message)) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) } })
    }
    forwarding?.let { message ->
        AlertDialog(onDismissRequest = { forwarding = null }, title = { Text(stringResource(R.string.forward_message)) },
            text = { LazyColumn(Modifier.heightIn(max = 400.dp)) { items(allChats, key = { it.peer.key }) { chat ->
                TextButton(onClick = { viewModel.forward(message, chat.peer); forwarding = null }, modifier = Modifier.fillMaxWidth()) { Text(chat.title) }
            } } }, confirmButton = { TextButton(onClick = { forwarding = null }) { Text(stringResource(R.string.cancel)) } })
    }
    reacting?.let { (message, code) ->
        AlertDialog(onDismissRequest = { reacting = null }, title = { Text(code) }, text = { Text(stringResource(R.string.reaction_detail)) },
            confirmButton = { TextButton(onClick = { viewModel.react(message, code, false); reacting = null }) { Text(stringResource(R.string.add_reaction)) } },
            dismissButton = { TextButton(onClick = { viewModel.react(message, code, true); reacting = null }) { Text(stringResource(R.string.remove_reaction)) } })
    }
    if (search.open) SearchDialog(search, selected != null, viewModel::searchServer, viewModel::closeSearch, viewModel::openSearchHit)
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().imePadding()) {
            if (error) Notice(stringResource(R.string.action_failed), Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            BoxWithConstraints(Modifier.weight(1f)) {
                val expanded = maxWidth >= 840.dp
                Row(Modifier.fillMaxSize()) {
                    if (expanded || selected == null) ConversationList(
                        chats, query, connection, selected?.key, moreDialogs,
                        onQuery = viewModel::search, onOpen = viewModel::open,
                        onRefresh = { viewModel.refresh() }, onMore = { viewModel.moreConversations() },
                        onSearch = viewModel::openSearch, onLogout = { confirmLogout = true },
                        modifier = if (expanded) Modifier.width(360.dp) else Modifier.weight(1f))
                    if (expanded) VerticalDivider()
                    val peer = selected
                    if (peer != null) key(account?.id, peer.key) {
                        ChatPane(
                            title = allChats.firstOrNull { it.peer.key == peer.key }?.title ?: peer.id.toString(),
                            peer = peer, queuing = queuing, messages = messages, me = account?.id,
                            composer = composer, typing = typing.isNotEmpty(), more = moreHistory, loading = loading,
                            onBack = viewModel::back, onSend = viewModel::send, onRetry = viewModel::retry,
                            onOlder = { viewModel.loadOlder() }, onRead = viewModel::read,
                            onActions = { action = it }, onReaction = { m, code -> reacting = m to code },
                            onCancelComposer = viewModel::cancelComposer, onDraftChanged = viewModel::draftChanged,
                            onSearch = viewModel::openSearch, modifier = Modifier.weight(1f))
                    } else if (expanded) {
                        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.select_chat), style = MaterialTheme.typography.headlineMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationList(
    chats: List<Conversation>, query: String, connection: Connection, selectedKey: String?, more: Boolean,
    onQuery: (String) -> Unit, onOpen: (Peer) -> Unit, onRefresh: () -> Unit, onMore: () -> Unit,
    onSearch: () -> Unit, onLogout: () -> Unit, modifier: Modifier,
) {
    Column(modifier.fillMaxHeight()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.chats), style = MaterialTheme.typography.headlineLarge)
            Text(stringResource(when (connection) {
                Connection.Connected -> R.string.connected
                Connection.Connecting -> R.string.connecting
                Connection.Reconnecting -> R.string.reconnecting
                Connection.Disconnected -> R.string.offline
                Connection.Unauthorized -> R.string.session_expired
            }), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Row {
                TextButton(onClick = onRefresh) { Text(stringResource(R.string.refresh)) }
                TextButton(onClick = onSearch) { Text(stringResource(R.string.search_messages)) }
            }
            TextButton(onClick = onLogout) { Text(stringResource(R.string.logout)) }
            OutlinedTextField(query, onQuery, modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(stringResource(R.string.search_chats)) }, shape = RoundedCornerShape(24.dp))
        }
        if (chats.isEmpty()) Text(stringResource(R.string.no_chats), Modifier.padding(24.dp))
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(chats, key = { it.peer.key }) { chat ->
                Surface(onClick = { onOpen(chat.peer) }, shape = RoundedCornerShape(26.dp), color =
                    if (chat.peer.key == selectedKey) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Surface(Modifier.size(48.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                            Box(contentAlignment = Alignment.Center) { Text(chat.title.take(1), style = MaterialTheme.typography.titleLarge) }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(chat.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(chat.preview.ifBlank { stringResource(R.string.unsupported_message) }, style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (chat.unread > 0) Badge { Text(stringResource(R.string.unread_count, chat.unread)) }
                    }
                }
            }
            if (more) item { TextButton(onClick = onMore, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.more_conversations)) } }
        }
    }
}

@Composable
private fun ChatPane(
    title: String, peer: Peer, queuing: Boolean, messages: LazyPagingItems<ChatMessage>, me: Int?, composer: ComposerState,
    typing: Boolean, more: Boolean, loading: Boolean, onBack: () -> Unit,
    onSend: (String, () -> Unit) -> Unit, onRetry: (ChatMessage) -> Unit, onOlder: () -> Unit, onRead: (Long) -> Unit,
    onActions: (ChatMessage) -> Unit, onReaction: (ChatMessage, String) -> Unit, onCancelComposer: () -> Unit,
    onDraftChanged: (String) -> Unit, onSearch: () -> Unit, modifier: Modifier,
) {
    var draft by rememberSaveable(peer.key) { mutableStateOf("") }
    var editingDraft by remember(composer.edit?.rid) { mutableStateOf(composer.edit?.text.orEmpty()) }
    val listState = rememberLazyListState()
    val visibleIncoming by remember(messages, listState, me, peer.key) { derivedStateOf {
        listState.layoutInfo.visibleItemsInfo.mapNotNull { item ->
            if (item.index < messages.itemCount) messages.peek(item.index) else null
        }.filter { it.peerKey == peer.key && it.senderId != me && it.delivery == Delivery.Sent }.maxOfOrNull { it.date }
    } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(peer.key, visibleIncoming, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            visibleIncoming?.let(onRead)
            awaitCancellation()
        }
    }
    Column(modifier.fillMaxHeight()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (typing) Text(stringResource(R.string.typing), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            TextButton(onClick = onSearch) { Text(stringResource(R.string.search_messages)) }
        }
        HorizontalDivider()
        if (messages.itemCount == 0 && messages.loadState.refresh !is LoadState.Loading) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.no_messages))
        } else LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, reverseLayout = true, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(count = messages.itemCount, key = messages.itemKey { "${it.peerKey}:${it.rid}" }) { index ->
                messages[index]?.takeIf { it.peerKey == peer.key }?.let { message ->
                    MessageBubble(message, message.senderId == me, peer.kind == 2, onActions, onRetry, onReaction)
                }
            }
            if (messages.loadState.append is LoadState.Loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (messages.loadState.append.endOfPaginationReached && more) item {
                TextButton(onClick = onOlder, enabled = !loading, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.load_older)) }
            }
        }
        (composer.edit ?: composer.reply)?.let { message ->
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(if (composer.edit != null) R.string.edit_message else R.string.reply_message), style = MaterialTheme.typography.labelLarge)
                        Text(message.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = onCancelComposer) { Text(stringResource(R.string.cancel)) }
                }
            }
        }
        val input = if (composer.edit != null) editingDraft else draft
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(input, {
                if (it.length <= 4096) {
                    if (composer.edit != null) editingDraft = it else draft = it
                    onDraftChanged(it)
                }
            }, modifier = Modifier.weight(1f).testTag("message-composer"), maxLines = 5, enabled = !queuing,
                shape = RoundedCornerShape(28.dp), placeholder = { Text(stringResource(R.string.message_hint)) })
            Button(onClick = {
                val submitted = input
                onSend(submitted) { if (composer.edit == null && draft == submitted) draft = "" }
            }, enabled = input.isNotBlank() && !queuing, modifier = Modifier.heightIn(min = 56.dp)) {
                Text(stringResource(if (composer.edit != null) R.string.save_message else R.string.send))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MessageBubble(
    message: ChatMessage, outgoing: Boolean, group: Boolean, onActions: (ChatMessage) -> Unit,
    onRetry: (ChatMessage) -> Unit, onReaction: (ChatMessage, String) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start) {
        Surface(shape = RoundedCornerShape(24.dp), color = if (outgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(0.88f)
                .combinedClickable(onClick = { if (!message.deleted) onActions(message) }, onLongClick = { if (!message.deleted) onActions(message) })) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!outgoing && group) Text(message.senderId.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                message.quote?.let { quote ->
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                        Text(quote.text.ifBlank { stringResource(R.string.unsupported_message) }, Modifier.padding(10.dp), maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text(when {
                    message.deleted -> stringResource(R.string.deleted_message)
                    message.supported -> message.text
                    else -> stringResource(R.string.unsupported_message)
                }, style = MaterialTheme.typography.bodyLarge)
                Text(formatTime(message.date) + if (message.edited) " · " + stringResource(R.string.edited_message) else "", style = MaterialTheme.typography.labelSmall)
                if (message.reactions.isNotEmpty() && !message.deleted) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    message.reactions.take(5).forEach { reaction ->
                        SuggestionChip(onClick = { onReaction(message, reaction.code) }, label = { Text("${reaction.code} ${reaction.count}") })
                    }
                }
                if (outgoing && !message.deleted) {
                    if (message.delivery == Delivery.Failed) TextButton(onClick = { onRetry(message) }) { Text(stringResource(R.string.retry)) }
                    else Text(stringResource(when {
                        message.delivery == Delivery.Pending -> R.string.sending
                        message.read -> R.string.read_message
                        message.received -> R.string.delivered_message
                        else -> R.string.sent
                    }), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun SearchDialog(state: SearchState, hasChat: Boolean, onSearch: (String, Boolean) -> Unit, onClose: () -> Unit, onOpen: (SearchHit) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var currentOnly by rememberSaveable { mutableStateOf(hasChat) }
    val keyboard = LocalSoftwareKeyboardController.current
    AlertDialog(onDismissRequest = onClose, title = { Text(stringResource(R.string.search_messages)) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(query, { if (it.length <= 256) query = it }, label = { Text(stringResource(R.string.search_messages)) }, singleLine = true, modifier = Modifier.testTag("server-search-query"))
            if (hasChat) Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(currentOnly, { currentOnly = it }); Text(stringResource(R.string.search_this_chat))
            }
            Button(onClick = { keyboard?.hide(); onSearch(query, currentOnly && hasChat) }, enabled = query.isNotBlank() && !state.busy, modifier = Modifier.testTag("server-search-submit")) { Text(stringResource(R.string.search_messages)) }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.failed) Text(stringResource(R.string.action_failed), color = MaterialTheme.colorScheme.error)
            state.page?.let { page ->
                Text(stringResource(R.string.search_count, page.hits.size, page.total))
                LazyColumn(Modifier.heightIn(max = 300.dp)) {
                    items(page.hits, key = { "${it.peer.key}:${it.message.rid}" }) { hit ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Text(hit.title, style = MaterialTheme.typography.titleSmall)
                            Text(hit.message.text.ifBlank { stringResource(R.string.unsupported_message) }, maxLines = 4, overflow = TextOverflow.Ellipsis)
                            Text(formatTime(hit.message.date), style = MaterialTheme.typography.labelSmall)
                            TextButton(onClick = { onOpen(hit) }) { Text(stringResource(R.string.open_conversation)) }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.close_panel)) } })
}
private fun formatTime(date: Long): String = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(date))
