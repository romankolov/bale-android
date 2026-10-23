package ai.bale.client.feature.messaging

import ai.bale.client.core.designsystem.R
import ai.bale.client.designsystem.Notice
import ai.bale.client.domain.*
import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
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
import kotlinx.coroutines.flow.collect
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
    val section by viewModel.section.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val messages = viewModel.pagedMessages.collectAsLazyPagingItems()
    val account by viewModel.account.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val onlineUsers by viewModel.onlineUsers.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val queuing by viewModel.queuing.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val composer by viewModel.composer.collectAsStateWithLifecycle()
    val search by viewModel.searchState.collectAsStateWithLifecycle()
    val typing by viewModel.typingUsers.collectAsStateWithLifecycle()
    val moreHistory by viewModel.moreHistory.collectAsStateWithLifecycle()
    val moreDialogs by viewModel.moreDialogs.collectAsStateWithLifecycle()
    val downloaded by viewModel.downloadedFiles.collectAsStateWithLifecycle()
    val contacts by viewModel.contacts.collectAsStateWithLifecycle()
    val directoryResults by viewModel.directoryResults.collectAsStateWithLifecycle()
    val stories by viewModel.stories.collectAsStateWithLifecycle()
    val ownProfile by viewModel.ownProfile.collectAsStateWithLifecycle()
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val inspected by viewModel.inspectedProfile.collectAsStateWithLifecycle()

    var confirmLogout by remember { mutableStateOf(false) }
    var showNewCommunity by remember { mutableStateOf(false) }
    var showCreatePoll by remember(selected?.key) { mutableStateOf(false) }
    var action by remember(selected?.key, account?.id) { mutableStateOf<ChatMessage?>(null) }
    var deleting by remember(selected?.key) { mutableStateOf<ChatMessage?>(null) }
    var forwarding by remember(selected?.key) { mutableStateOf<ChatMessage?>(null) }

    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val audioPlayer = remember { AudioClipPlayer() }
    val voiceRecorder = remember(context) { VoiceNoteRecorder(context) }
    var recordingVoice by remember(selected?.key) { mutableStateOf(false) }
    val playingPath by audioPlayer.playingPath.collectAsStateWithLifecycle()

    DisposableEffect(Unit) {
        onDispose {
            audioPlayer.stop()
            voiceRecorder.stopAndDiscard()
        }
    }

    BackHandler(enabled = selected != null || section != AppSection.Chats) {
        if (selected != null) viewModel.back() else viewModel.selectSection(AppSection.Chats)
    }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try { awaitCancellation() } finally {
                viewModel.stopTyping()
                if (recordingVoice) {
                    voiceRecorder.stopAndDiscard()
                    recordingVoice = false
                }
            }
        }
    }

    if (confirmLogout) AlertDialog(
        onDismissRequest = { confirmLogout = false }, title = { Text(stringResource(R.string.logout_title)) },
        text = { Text(stringResource(R.string.logout_detail)) },
        confirmButton = { TextButton(onClick = { confirmLogout = false; viewModel.signOut() }) { Text(stringResource(R.string.logout)) } },
        dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text(stringResource(R.string.cancel)) } },
    )
    if (showNewCommunity) NewCommunityDialog(
        contacts = contacts,
        onCreate = viewModel::createCommunity,
        onJoin = viewModel::joinInviteOrHandle,
        onDismiss = { showNewCommunity = false },
    )
    if (showCreatePoll) CreatePollDialog(
        onCreate = viewModel::createPoll,
        onDismiss = { showCreatePoll = false },
    )
    inspected?.let { profile ->
        PeerInspectorSheet(
            profile = profile,
            contacts = contacts,
            meId = account?.id,
            onDismiss = viewModel::dismissInspector,
            onPinDialog = { viewModel.setDialogPinned(profile.peer, it) },
            onArchiveDialog = { viewModel.setDialogArchived(profile.peer, it) },
            onClearChat = viewModel::clearSelectedChat,
            onDeleteChat = viewModel::deleteSelectedChat,
            onSetBlocked = { viewModel.setBlocked(profile.peer, it) },
            onEditCommunity = { title, about -> viewModel.editCommunity(profile.peer, title, about) },
            onLeaveCommunity = { viewModel.leaveCommunity(profile.peer) },
            onRevokeInvite = { viewModel.revokeInviteLink(profile.peer) },
            onInviteMember = { viewModel.inviteMember(profile.peer, it) },
            onKickMember = { viewModel.kickMember(profile.peer, it) },
            onSetAdmin = { user, admin -> viewModel.setMemberAdmin(profile.peer, user, admin) },
            onUnpinMessage = { viewModel.pinMessage(it, false) },
        )
    }
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
                    if (message.senderId == account?.id && message.attachment == null && message.poll == null) {
                        TextButton(onClick = { viewModel.edit(message); action = null }) { Text(stringResource(R.string.edit_message)) }
                    }
                    TextButton(onClick = { forwarding = message; action = null }) { Text(stringResource(R.string.forward_message)) }
                    TextButton(onClick = { viewModel.pinMessage(message, true); action = null }) { Text(stringResource(R.string.pin_message_action)) }
                    if (message.poll?.closed == false && message.senderId == account?.id) {
                        TextButton(onClick = { viewModel.closePoll(message); action = null }) { Text(stringResource(R.string.close_poll)) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("👍", "❤️", "😂", "😮", "😢").forEach { code ->
                            val alreadyMine = message.reactions.any { it.code == code && it.mine }
                            TextButton(
                                onClick = { viewModel.react(message, code, alreadyMine); action = null },
                                modifier = Modifier.weight(1f),
                            ) { Text(code) }
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
            text = {
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    items(allChats, key = { it.peer.key }) { chat ->
                        TextButton(onClick = { viewModel.forward(message, chat.peer); forwarding = null }, modifier = Modifier.fillMaxWidth()) { Text(chat.title) }
                    }
                }
            }, confirmButton = { TextButton(onClick = { forwarding = null }) { Text(stringResource(R.string.cancel)) } })
    }
    if (search.open) SearchDialog(search, selected != null, viewModel::searchServer, viewModel::closeSearch, viewModel::openSearchHit)

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding()) {
            if (error) Notice(stringResource(R.string.action_failed), Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            BoxWithConstraints(Modifier.weight(1f)) {
                val expanded = maxWidth >= 840.dp
                Row(Modifier.fillMaxSize()) {
                    if (expanded) {
                        NavigationRail {
                            SectionItems().forEach { (item, label) ->
                                NavigationRailItem(
                                    selected = section == item,
                                    onClick = { viewModel.selectSection(item) },
                                    icon = { Text(label.take(1)) },
                                    label = { Text(label) },
                                )
                            }
                        }
                    }
                    when (section) {
                        AppSection.Chats -> {
                            if (expanded || selected == null) ConversationList(
                                chats = chats,
                                query = query,
                                connection = connection,
                                selectedKey = selected?.key,
                                more = moreDialogs,
                                onQuery = viewModel::search,
                                onOpen = viewModel::open,
                                onRefresh = { viewModel.refresh() },
                                onMore = { viewModel.moreConversations() },
                                onSearch = viewModel::openSearch,
                                onNewCommunity = {
                                    viewModel.refreshContacts()
                                    showNewCommunity = true
                                },
                                onLogout = { confirmLogout = true },
                                modifier = if (expanded) Modifier.width(360.dp) else Modifier.weight(1f),
                            )
                            if (expanded) VerticalDivider()
                            val peer = selected
                            if (peer != null) key(account?.id, peer.key) {
                                ChatPane(
                                    title = allChats.firstOrNull { it.peer.key == peer.key }?.title ?: peer.id.toString(),
                                    peer = peer,
                                    peerOnline = peer.kind == 1 && peer.id in onlineUsers,
                                    queuing = queuing,
                                    messages = messages,
                                    me = account?.id,
                                    composer = composer,
                                    typing = typing.isNotEmpty(),
                                    more = moreHistory,
                                    loading = loading,
                                    downloaded = downloaded,
                                    playingPath = playingPath,
                                    recordingVoice = recordingVoice,
                                    onBack = viewModel::back,
                                    onInspect = viewModel::inspectSelectedPeer,
                                    onSend = viewModel::send,
                                    onSendAttachment = { payload, caption -> viewModel.sendAttachment(payload, caption) },
                                    onOpenPollDialog = { showCreatePoll = true },
                                    onStartVoice = {
                                        if (voiceRecorder.start()) recordingVoice = true
                                    },
                                    onFinishVoice = { send ->
                                        recordingVoice = false
                                        if (send) voiceRecorder.finish()?.let { viewModel.sendAttachment(it) }
                                        else voiceRecorder.stopAndDiscard()
                                    },
                                    onDownloadOrOpen = { attachment ->
                                        val existing = downloaded[attachment.fileId]
                                        if (existing != null) {
                                            if (attachment.kind == AttachmentKind.Voice || attachment.kind == AttachmentKind.Audio) {
                                                audioPlayer.toggle(existing)
                                            } else {
                                                openCachedMediaExternally(context, existing, attachment.mimeType)
                                            }
                                        } else {
                                            viewModel.downloadAttachment(attachment) { path ->
                                                if (attachment.kind == AttachmentKind.Voice || attachment.kind == AttachmentKind.Audio) {
                                                    audioPlayer.toggle(path)
                                                } else if (attachment.kind != AttachmentKind.Photo) {
                                                    openCachedMediaExternally(context, path, attachment.mimeType)
                                                }
                                            }
                                        }
                                    },
                                    onVotePoll = viewModel::votePoll,
                                    onBotButton = { button ->
                                        when (button.kind) {
                                            BotButtonKind.OpenUrl -> {
                                                if (button.value.startsWith("https://")) {
                                                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(button.value))) }
                                                }
                                            }
                                            BotButtonKind.CopyText -> {
                                                scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("bot", button.value))) }
                                            }
                                            else -> viewModel.pressBotButton(button)
                                        }
                                    },
                                    onRetry = viewModel::retry,
                                    onOlder = { viewModel.loadOlder() },
                                    onRead = viewModel::read,
                                    onActions = { action = it },
                                    onReaction = { m, code, mine -> viewModel.react(m, code, mine) },
                                    onCancelComposer = viewModel::cancelComposer,
                                    onDraftChanged = viewModel::draftChanged,
                                    onSearch = viewModel::openSearch,
                                    modifier = Modifier.weight(1f),
                                )
                            } else if (expanded) {
                                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                                    Text(stringResource(R.string.select_chat), style = MaterialTheme.typography.headlineMedium)
                                }
                            }
                        }
                        AppSection.Contacts -> ContactsPane(
                            contacts = contacts,
                            directoryResults = directoryResults,
                            onSearchDirectory = viewModel::searchDirectory,
                            onOpenPeer = viewModel::open,
                            onAddContact = viewModel::addContact,
                            onRemoveContact = viewModel::removeContact,
                            onRefresh = { viewModel.refreshContacts() },
                            onNewCommunity = { showNewCommunity = true },
                            modifier = Modifier.weight(1f),
                        )
                        AppSection.Stories -> StoriesPane(
                            stories = stories,
                            myAccountId = account?.id,
                            onRefresh = { viewModel.refreshStories() },
                            onPostTextStory = viewModel::postTextStory,
                            onReactStory = viewModel::reactStory,
                            onDeleteStory = viewModel::deleteStory,
                            modifier = Modifier.weight(1f),
                        )
                        AppSection.Settings -> SettingsPane(
                            account = account,
                            profile = ownProfile,
                            sessions = sessions,
                            onRefresh = { viewModel.refreshSettings() },
                            onSaveProfile = viewModel::saveOwnProfile,
                            onUploadAvatar = viewModel::uploadOwnAvatar,
                            onTerminateSession = viewModel::terminateSession,
                            onTerminateOtherSessions = { viewModel.terminateOtherSessions() },
                            onLogout = { confirmLogout = true },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            if (selected == null) {
                BoxWithConstraints {
                    if (maxWidth < 840.dp) {
                        NavigationBar {
                            SectionItems().forEach { (item, label) ->
                                NavigationBarItem(
                                    selected = section == item,
                                    onClick = { viewModel.selectSection(item) },
                                    icon = { Text(label.take(1)) },
                                    label = { Text(label) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionItems(): List<Pair<AppSection, String>> = listOf(
    AppSection.Chats to stringResource(R.string.chats),
    AppSection.Contacts to stringResource(R.string.nav_contacts),
    AppSection.Stories to stringResource(R.string.nav_stories),
    AppSection.Settings to stringResource(R.string.nav_settings),
)

@Composable
private fun ConversationList(
    chats: List<Conversation>, query: String, connection: Connection, selectedKey: String?, more: Boolean,
    onQuery: (String) -> Unit, onOpen: (Peer) -> Unit, onRefresh: () -> Unit, onMore: () -> Unit,
    onSearch: () -> Unit, onNewCommunity: () -> Unit, onLogout: () -> Unit, modifier: Modifier,
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
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onRefresh) { Text(stringResource(R.string.refresh)) }
                TextButton(onClick = onSearch) { Text(stringResource(R.string.search_messages)) }
                TextButton(onClick = onNewCommunity) { Text(stringResource(R.string.new_community)) }
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
    title: String, peer: Peer, peerOnline: Boolean, queuing: Boolean, messages: LazyPagingItems<ChatMessage>, me: Int?,
    composer: ComposerState, typing: Boolean, more: Boolean, loading: Boolean,
    downloaded: Map<Long, String>, playingPath: String?, recordingVoice: Boolean,
    onBack: () -> Unit, onInspect: () -> Unit,
    onSend: (String, () -> Unit) -> Unit,
    onSendAttachment: (UploadPayload, String) -> Unit,
    onOpenPollDialog: () -> Unit,
    onStartVoice: () -> Unit,
    onFinishVoice: (Boolean) -> Unit,
    onDownloadOrOpen: (Attachment) -> Unit,
    onVotePoll: (ChatMessage, Int) -> Unit,
    onBotButton: (BotButton) -> Unit,
    onRetry: (ChatMessage) -> Unit, onOlder: () -> Unit, onRead: (Long) -> Unit,
    onActions: (ChatMessage) -> Unit, onReaction: (ChatMessage, String, Boolean) -> Unit, onCancelComposer: () -> Unit,
    onDraftChanged: (String) -> Unit, onSearch: () -> Unit, modifier: Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var draft by rememberSaveable(peer.key) { mutableStateOf("") }
    var editingDraft by remember(composer.edit?.rid) { mutableStateOf(composer.edit?.text.orEmpty()) }
    val listState = rememberLazyListState()
    var followNewest by remember { mutableStateOf(true) }
    val newestRid = messages.itemSnapshotList.items.firstOrNull { it.peerKey == peer.key }?.rid

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            runCatching { readPickedUri(context, uri, forcePhotoOrVideo = true) }.getOrNull()?.let { payload ->
                followNewest = true
                val caption = draft
                draft = ""
                onSendAttachment(payload, caption)
            }
        }
    }
    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching { readPickedUri(context, uri) }.getOrNull()?.let { payload ->
                followNewest = true
                val caption = draft
                draft = ""
                onSendAttachment(payload, caption)
            }
        }
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onStartVoice()
    }

    LaunchedEffect(listState) {
        snapshotFlow {
            listState.isScrollInProgress to (listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0)
        }.collect { (scrolling, atNewest) -> if (scrolling) followNewest = atNewest }
    }
    LaunchedEffect(newestRid) {
        if (newestRid != null && followNewest && !listState.isScrollInProgress) {
            listState.scrollToItem(0)
        }
    }
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
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
            Column(Modifier.weight(1f).clickable(onClick = onInspect)) {
                Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                when {
                    typing -> Text(stringResource(R.string.typing), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    peerOnline -> Text(stringResource(R.string.online_status), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
            TextButton(onClick = onInspect) { Text(stringResource(R.string.chat_info)) }
            TextButton(onClick = onSearch) { Text(stringResource(R.string.search_messages)) }
        }
        HorizontalDivider()
        if (messages.itemCount == 0 && messages.loadState.refresh !is LoadState.Loading) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.no_messages))
        } else LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, reverseLayout = true, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(count = messages.itemCount, key = messages.itemKey { "${it.peerKey}:${it.rid}" }) { index ->
                messages[index]?.takeIf { it.peerKey == peer.key }?.let { message ->
                    MessageBubble(
                        message = message,
                        outgoing = message.senderId == me,
                        group = peer.kind == 2,
                        downloadedPath = message.attachment?.let { downloaded[it.fileId] },
                        playingPath = playingPath,
                        onActions = onActions,
                        onRetry = onRetry,
                        onReaction = onReaction,
                        onDownloadOrOpen = onDownloadOrOpen,
                        onVotePoll = onVotePoll,
                        onBotButton = onBotButton,
                    )
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
        if (composer.edit == null && (input.isBlank() || recordingVoice)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (recordingVoice) {
                    Text(stringResource(R.string.recording_voice), Modifier.weight(1f), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge)
                    TextButton(onClick = { onFinishVoice(false) }) { Text(stringResource(R.string.cancel)) }
                    Button(onClick = { followNewest = true; onFinishVoice(true) }) { Text(stringResource(R.string.send_voice)) }
                } else {
                    TextButton(onClick = {
                        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                    }) { Text(stringResource(R.string.attach_media)) }
                    TextButton(onClick = { documentPicker.launch(arrayOf("*/*")) }) { Text(stringResource(R.string.attach_file)) }
                    TextButton(onClick = onOpenPollDialog) { Text(stringResource(R.string.poll_short)) }
                    TextButton(onClick = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            onStartVoice()
                        } else {
                            micPermission.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }) { Text(stringResource(R.string.record_voice)) }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(input, {
                if (it.length <= 4096) {
                    if (composer.edit != null) editingDraft = it else draft = it
                    onDraftChanged(it)
                }
            }, modifier = Modifier.weight(1f).testTag("message-composer"), maxLines = 5, enabled = !queuing,
                shape = RoundedCornerShape(28.dp), placeholder = { Text(stringResource(R.string.message_hint)) })
            Button(onClick = {
                val submitted = input
                chatKeyboard?.hide()
                if (composer.edit == null) followNewest = true
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
    message: ChatMessage,
    outgoing: Boolean,
    group: Boolean,
    downloadedPath: String?,
    playingPath: String?,
    onActions: (ChatMessage) -> Unit,
    onRetry: (ChatMessage) -> Unit,
    onReaction: (ChatMessage, String, Boolean) -> Unit,
    onDownloadOrOpen: (Attachment) -> Unit,
    onVotePoll: (ChatMessage, Int) -> Unit,
    onBotButton: (BotButton) -> Unit,
) {
    if (message.serviceNotice) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                Text(message.text, Modifier.padding(horizontal = 14.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium)
            }
        }
        return
    }
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
                message.attachment?.let { att ->
                    val imagePreview = remember(att.fileId, downloadedPath) {
                        downloadedPath?.let { decodeSampledFileBitmap(it) } ?: decodeSampledBitmap(att.thumbnail, 480)
                    }
                    if (imagePreview != null) {
                        Image(
                            bitmap = imagePreview,
                            contentDescription = att.name,
                            modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).clip(RoundedCornerShape(16.dp)),
                            contentScale = ContentScale.Crop,
                        )
                    }
                    if (att.fileId != 0L) {
                        val isAudio = att.kind == AttachmentKind.Voice || att.kind == AttachmentKind.Audio
                        val isPlaying = downloadedPath != null && playingPath == downloadedPath
                        OutlinedButton(onClick = { onDownloadOrOpen(att) }) {
                            val label = when {
                                isPlaying -> stringResource(R.string.stop_audio)
                                isAudio && downloadedPath != null -> stringResource(R.string.play_audio)
                                downloadedPath != null -> stringResource(R.string.open_attachment)
                                else -> "${stringResource(R.string.download_attachment)} (${att.name})"
                            }
                            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                message.poll?.let { poll ->
                    Text(poll.question, style = MaterialTheme.typography.titleMedium)
                    poll.options.forEach { option ->
                        OutlinedButton(
                            onClick = { onVotePoll(message, option.id) },
                            enabled = !poll.closed,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                (if (option.chosen) "✓ " else "") + "${option.text} (${option.votes})",
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    Text(stringResource(R.string.poll_votes_summary, poll.totalVoters), style = MaterialTheme.typography.labelSmall)
                }
                if (message.poll == null) {
                    Text(when {
                        message.deleted -> stringResource(R.string.deleted_message)
                        message.supported -> message.text
                        else -> stringResource(R.string.unsupported_message)
                    }, style = MaterialTheme.typography.bodyLarge)
                }
                if (message.buttons.isNotEmpty() && !message.deleted) {
                    message.buttons.forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { btn ->
                                FilledTonalButton(onClick = { onBotButton(btn) }, modifier = Modifier.weight(1f)) {
                                    Text(btn.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
                Text(formatTime(message.date) + if (message.edited) " · " + stringResource(R.string.edited_message) else "", style = MaterialTheme.typography.labelSmall)
                if (message.reactions.isNotEmpty() && !message.deleted) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    message.reactions.take(5).forEach { reaction ->
                        FilterChip(
                            selected = reaction.mine,
                            onClick = { onReaction(message, reaction.code, reaction.mine) },
                            label = { Text("${reaction.code} ${reaction.count}") },
                        )
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
