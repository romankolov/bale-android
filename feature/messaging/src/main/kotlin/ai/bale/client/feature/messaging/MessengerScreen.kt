package ai.bale.client.feature.messaging

import ai.bale.client.core.designsystem.R
import ai.bale.client.designsystem.Notice
import ai.bale.client.domain.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun MessengerScreen(viewModel: MessengerViewModel = hiltViewModel()) {
    val chats by viewModel.filtered.collectAsStateWithLifecycle()
    val allChats by viewModel.conversations.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val account by viewModel.account.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val queuing by viewModel.queuing.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    var confirmLogout by remember { mutableStateOf(false) }
    BackHandler(enabled = selected != null) { viewModel.back() }
    if (confirmLogout) AlertDialog(
        onDismissRequest = { confirmLogout = false },
        title = { Text(stringResource(R.string.logout_title)) },
        text = { Text(stringResource(R.string.logout_detail)) },
        confirmButton = { TextButton(onClick = { confirmLogout = false; viewModel.signOut() }) { Text(stringResource(R.string.logout)) } },
        dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text(stringResource(R.string.cancel)) } },
    )
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().imePadding()) {
            if (error) Notice(stringResource(R.string.network_failed), Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            BoxWithConstraints(Modifier.weight(1f)) {
                val expanded = maxWidth >= 840.dp
                Row(Modifier.fillMaxSize()) {
                    if (expanded || selected == null) ConversationList(
                        chats, query, connection, selected?.key,
                        onQuery = viewModel::search, onOpen = viewModel::open,
                        onRefresh = { viewModel.refresh() }, onLogout = { confirmLogout = true },
                        modifier = if (expanded) Modifier.width(360.dp) else Modifier.weight(1f),
                    )
                    if (expanded) VerticalDivider()
                    val peer = selected
                    if (peer != null) {
                        ChatPane(
                            title = allChats.firstOrNull { it.peer.key == peer.key }?.title ?: peer.id.toString(),
                            peer = peer, queuing = queuing, messages = messages.filter { it.peerKey == peer.key }, me = account?.id,
                            onBack = viewModel::back, onSend = viewModel::send, onRetry = viewModel::retry,
                            onOlder = { viewModel.loadOlder() }, onRead = viewModel::read, modifier = Modifier.weight(1f),
                        )
                    } else if (expanded) {
                        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(stringResource(R.string.select_chat), style = MaterialTheme.typography.headlineMedium)
                                Text(stringResource(R.string.select_chat_detail), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationList(
    chats: List<Conversation>, query: String, connection: Connection, selectedKey: String?,
    onQuery: (String) -> Unit, onOpen: (Peer) -> Unit, onRefresh: () -> Unit, onLogout: () -> Unit, modifier: Modifier,
) {
    Column(modifier.fillMaxHeight()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.chats), style = MaterialTheme.typography.headlineLarge)
            Text(stringResource(R.string.chats_subtitle), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(when (connection) {
                Connection.Connected -> R.string.connected
                Connection.Connecting -> R.string.connecting
                Connection.Reconnecting -> R.string.reconnecting
                Connection.Disconnected -> R.string.offline
                Connection.Unauthorized -> R.string.session_expired
            }), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Row {
                TextButton(onClick = onRefresh) { Text(stringResource(R.string.refresh)) }
                TextButton(onClick = onLogout) { Text(stringResource(R.string.logout)) }
            }
            OutlinedTextField(query, onQuery, modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(stringResource(R.string.search_chats)) }, shape = RoundedCornerShape(24.dp))
        }
        if (chats.isEmpty()) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.no_chats), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.no_chats_detail), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
        }
    }
}

@Composable
private fun ChatPane(
    title: String, peer: Peer, queuing: Boolean, messages: List<ChatMessage>, me: Int?, onBack: () -> Unit,
    onSend: (String, () -> Unit) -> Unit, onRetry: (Long) -> Unit, onOlder: () -> Unit, onRead: (Long) -> Unit, modifier: Modifier,
) {
    var draft by rememberSaveable(peer.key) { mutableStateOf("") }
    val newestDate = messages.filter { it.delivery == Delivery.Sent }.maxOfOrNull { it.date }
    LaunchedEffect(peer.key, newestDate) { if (newestDate != null) onRead(newestDate) }
    Column(modifier.fillMaxHeight()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        HorizontalDivider()
        if (messages.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.no_messages)) }
        else LazyColumn(Modifier.weight(1f).fillMaxWidth(), reverseLayout = true, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(messages.asReversed(), key = { it.rid }) { message ->
                val outgoing = message.senderId == me
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start) {
                    Surface(shape = RoundedCornerShape(24.dp), color = if (outgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(0.88f)) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (!outgoing && peer.kind == 2) Text(message.senderId.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            Text(if (message.supported) message.text else stringResource(R.string.unsupported_message), style = MaterialTheme.typography.bodyLarge)
                            Text(formatTime(message.date), style = MaterialTheme.typography.labelSmall)
                            if (outgoing) {
                                if (message.delivery == Delivery.Failed) TextButton(onClick = { onRetry(message.rid) }) { Text(stringResource(R.string.retry)) }
                                else Text(stringResource(if (message.delivery == Delivery.Pending) R.string.sending else R.string.sent), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
            item { TextButton(onClick = onOlder, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.load_older)) } }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(draft, { if (it.length <= 4096) draft = it }, modifier = Modifier.weight(1f), maxLines = 5,
                shape = RoundedCornerShape(28.dp), placeholder = { Text(stringResource(R.string.message_hint)) })
            Button(onClick = { val submitted = draft; onSend(submitted) { if (draft == submitted) draft = "" } }, enabled = draft.isNotBlank() && !queuing, modifier = Modifier.heightIn(min = 56.dp)) { Text(stringResource(R.string.send)) }
        }
    }
}
private fun formatTime(date: Long): String = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(date))
