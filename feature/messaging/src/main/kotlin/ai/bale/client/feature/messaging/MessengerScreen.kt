package ai.bale.client.feature.messaging

import ai.bale.client.core.designsystem.R
import ai.bale.client.designsystem.AppLocaleStore
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessengerScreen(viewModel: MessengerViewModel = hiltViewModel()) {
    val allChats by viewModel.conversations.collectAsStateWithLifecycle()
    val section by viewModel.section.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val messages = viewModel.pagedMessages.collectAsLazyPagingItems()
    val account by viewModel.account.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val onlineUsers by viewModel.onlineUsers.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val uploadingName by viewModel.uploadingAttachment.collectAsStateWithLifecycle()
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
    val avatarPaths by viewModel.avatarPaths.collectAsStateWithLifecycle()
    val chatFolders by viewModel.folders.collectAsStateWithLifecycle()
    val dialogFilter by viewModel.dialogFilter.collectAsStateWithLifecycle()
    val folderFilter by viewModel.folderFilter.collectAsStateWithLifecycle()
    val viewer by viewModel.viewer.collectAsStateWithLifecycle()
    val picker by viewModel.picker.collectAsStateWithLifecycle()
    val threadState by viewModel.thread.collectAsStateWithLifecycle()
    val scheduleState by viewModel.schedule.collectAsStateWithLifecycle()
    val miniApp by viewModel.miniApp.collectAsStateWithLifecycle()
    val linkPreview by viewModel.linkPreview.collectAsStateWithLifecycle()
    val voters by viewModel.voters.collectAsStateWithLifecycle()
    val reportTarget by viewModel.reportTarget.collectAsStateWithLifecycle()
    val security by viewModel.security.collectAsStateWithLifecycle()
    val sharedMedia by viewModel.sharedMedia.collectAsStateWithLifecycle()
    val preferences by viewModel.preferences.collectAsStateWithLifecycle()
    val handleAvailable by viewModel.handleAvailable.collectAsStateWithLifecycle()
    val chats by viewModel.visibleChats.collectAsStateWithLifecycle()

    var confirmLogout by remember { mutableStateOf(false) }
    var chatActions by remember { mutableStateOf<Conversation?>(null) }
    var reportConversation by remember { mutableStateOf(false) }
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
            sharedMedia = sharedMedia,
            onOpenSharedMedia = { viewModel.openSharedMedia() },
            onSelectSharedMediaTab = viewModel::selectSharedMediaTab,
            onCloseSharedMedia = viewModel::closeSharedMedia,
            onOpenMedia = { message -> viewModel.openViewer(message) },
            onReportPeer = { reportConversation = true },
            onUploadCommunityAvatar = viewModel::uploadCommunityAvatar,
            onUpdateHandle = viewModel::updateCommunityHandle,
            onCheckHandle = viewModel::checkCommunityHandle,
            handleAvailable = handleAvailable,
            avatars = avatarPaths,
            pinned = allChats.firstOrNull { it.peer.key == profile.peer.key }?.pinned ?: false,
            muted = allChats.firstOrNull { it.peer.key == profile.peer.key }?.muted ?: false,
            onTogglePin = { viewModel.setDialogPinned(profile.peer, !(allChats.firstOrNull { it.peer.key == profile.peer.key }?.pinned ?: false)) },
            onToggleMute = { viewModel.toggleMute(profile.peer) },
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
                    if (message.senderId == account?.id && message.attachment == null && message.poll == null && message.forwardedFrom == null) {
                        TextButton(onClick = { viewModel.edit(message); action = null }) { Text(stringResource(R.string.edit_message)) }
                    }
                    TextButton(onClick = { forwarding = message; action = null }) { Text(stringResource(R.string.forward_message)) }
                    TextButton(onClick = { viewModel.pinMessage(message, true); action = null }) { Text(stringResource(R.string.pin_message_action)) }
                    if (message.poll?.closed == false && message.poll?.options?.any { it.chosen } == true) {
                        TextButton(onClick = { viewModel.retractPollVote(message); action = null }) {
                            Text(stringResource(R.string.poll_retract_vote))
                        }
                    }
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
    viewer?.let { state ->
        MediaViewerOverlay(
            state = state,
            onDismiss = viewModel::closeViewer,
            onOpenExternally = {
                openExternallyOrWarn(context, state.path, state.message.attachment?.mimeType.orEmpty(), viewModel::reportOperationError)
            },
            onTranscribe = viewModel::transcribeVoiceInViewer,
            onSaveGif = viewModel::saveViewerGif,
        )
    }
    if (picker.mode != null) {
        PickerOverlay(
            state = picker,
            onMode = viewModel::openPicker,
            onSendSticker = viewModel::sendSticker,
            onSendGif = viewModel::sendGif,
            onRemoveGif = viewModel::removeGif,
            onDismiss = viewModel::closePicker,
        )
    }
    threadState?.let { state ->
        ThreadOverlay(state = state, meId = account?.id, onSend = viewModel::sendThreadReply, onDismiss = viewModel::closeThread)
    }
    voters?.let { state -> VotersOverlay(state = state, onDismiss = viewModel::closeVoters) }
    miniApp?.let { state ->
        MiniAppOverlay(state = state, onSubmitQuery = viewModel::submitMiniAppQuery, onDismiss = viewModel::closeMiniApp)
    }
    if (reportConversation) {
        ReportSheet(
            isMessage = false,
            onSubmit = { reason -> reportConversation = false; viewModel.reportConversation(reason) },
            onDismiss = { reportConversation = false },
        )
    }
    reportTarget?.let { message ->
        ReportSheet(
            isMessage = true,
            onSubmit = { reason -> viewModel.reportMessageFor(message, reason); viewModel.dismissReport() },
            onDismiss = { viewModel.dismissReport() },
        )
    }
    chatActions?.let { chat ->
        ChatActionSheet(
            conversation = chat,
            onDismiss = { chatActions = null },
            onPin = { viewModel.setDialogPinned(chat.peer, !chat.pinned) },
            onMute = { viewModel.toggleMute(chat.peer) },
            onUnread = { viewModel.toggleMarkedUnread(chat.peer) },
            onArchive = { viewModel.setDialogArchived(chat.peer, !chat.archived) },
            onReport = { chatActions = null; viewModel.reportMessage(null); reportConversation = true },
            onFolder = { folderId -> viewModel.assignChatToFolder(chat.peer, folderId) },
            folders = chatFolders,
        )
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
                                onlineIds = onlineUsers,
                                chats = chats,
                                avatars = avatarPaths,
                                filter = dialogFilter,
                                folderId = folderFilter,
                                folders = chatFolders,
                                onFilter = viewModel::setDialogFilter,
                                onFolder = viewModel::selectFolder,
                                onCreateFolder = viewModel::createFolder,
                                onDeleteFolder = viewModel::deleteFolder,
                                onOpenSaved = { viewModel.openSavedMessages() },
                                onActions = { chatActions = it },
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
                                    connection = connection,
                                    peerOnline = peer.kind == 1 && peer.id in onlineUsers,
                                    queuing = queuing,
                                    uploadingName = uploadingName,
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
                                    onAttachmentPickFailed = viewModel::reportOperationError,
                                    onOpenPollDialog = { showCreatePoll = true },
                                    onStartVoice = {
                                        if (voiceRecorder.start()) recordingVoice = true else viewModel.reportOperationError()
                                    },
                                    onFinishVoice = { send ->
                                        recordingVoice = false
                                        if (send) {
                                            val note = voiceRecorder.finish()
                                            if (note != null) viewModel.sendAttachment(note) else viewModel.reportOperationError()
                                        } else {
                                            voiceRecorder.stopAndDiscard()
                                        }
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
                                    onSubmitPollSelection = viewModel::submitPollSelection,
                                    onRetractPollVote = viewModel::retractPollVote,
                                    onRefreshPolls = viewModel::refreshSelectedPolls,
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
                                    onDraftChanged = { viewModel.draftChanged(it); viewModel.persistDraft(peer, it) },
                                    onSearch = viewModel::openSearch,
                                    avatars = avatarPaths,
                                    savedDraft = allChats.firstOrNull { it.peer.key == peer.key }?.draft.orEmpty(),
                                    schedule = scheduleState,
                                    preview = linkPreview,
                                    isBotPeer = allChats.firstOrNull { it.peer.key == peer.key }?.isBot == true,
                                    onSchedule = { minutes -> viewModel.scheduleMessage(peer, minutes) },
                                    onOpenScheduled = viewModel::openScheduledList,
                                    onDismissSchedule = { viewModel.closeScheduleComposer(); viewModel.closeScheduledList() },
                                    onSendNow = viewModel::sendScheduledNow,
                                    onCancelScheduled = viewModel::cancelScheduled,
                                    onPreviewLink = { url, withSummary -> viewModel.previewLink(url, withSummary) },
                                    onDismissPreview = viewModel::clearLinkPreview,
                                    onOpenStickers = { viewModel.openPicker(PickerMode.Stickers) },
                                    onOpenGifs = { viewModel.openPicker(PickerMode.Gifs) },
                                    onOpenThread = viewModel::openThread,
                                    onOpenMedia = viewModel::openViewer,
                                    onBotMenu = viewModel::openMiniApp,
                                    onShowPollVoters = viewModel::showPollVoters,
                                    onShowReactionVoters = viewModel::showReactionVoters,
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
                            preferences = preferences,
                            security = security,
                            folders = chatFolders,
                            avatarPaths = avatarPaths,
                            onThemeMode = viewModel::setThemeMode,
                            onDynamicColor = viewModel::setDynamicColor,
                            onLanguage = { language ->
                                viewModel.setLanguage(language)
                                AppLocaleStore.setTag(context, language.tag)
                                (context as? android.app.Activity)?.recreate()
                            },
                            onRefreshSecurity = viewModel::refreshSecurity,
                            onEnableTwoFactor = viewModel::enableTwoFactor,
                            onDisableTwoFactor = viewModel::disableTwoFactor,
                            onUnblock = { viewModel.setBlocked(it, false) },
                            onDeleteFolder = viewModel::deleteFolder,
                            onOpenSaved = viewModel::openSavedMessages,
                            onRefresh = { viewModel.refreshSettings() },
                            onSaveProfile = viewModel::saveOwnProfile,
                            onUploadAvatar = viewModel::uploadOwnAvatar,
                            onUploadFailed = viewModel::reportOperationError,
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
    onlineIds: Set<Int> = emptySet(),
    chats: List<Conversation>, query: String, connection: Connection, selectedKey: String?, more: Boolean,
    onQuery: (String) -> Unit, onOpen: (Peer) -> Unit, onRefresh: () -> Unit, onMore: () -> Unit,
    onSearch: () -> Unit, onNewCommunity: () -> Unit, onLogout: () -> Unit, modifier: Modifier,
    avatars: Map<Long, String> = emptyMap(),
    filter: DialogFilter = DialogFilter.All,
    onFilter: (DialogFilter) -> Unit = {},
    folders: List<ChatFolder> = emptyList(),
    folderId: Int = 0,
    onFolder: (Int) -> Unit = {},
    onCreateFolder: (String) -> Unit = {},
    onDeleteFolder: (Int) -> Unit = {},
    onOpenSaved: () -> Unit = {},
    onActions: (Conversation) -> Unit = {},
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
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onOpenSaved, modifier = Modifier.testTag("open_saved_messages")) { Text(stringResource(R.string.saved_messages)) }
                TextButton(onClick = onLogout) { Text(stringResource(R.string.logout)) }
            }
            OutlinedTextField(query, onQuery, modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(stringResource(R.string.search_chats)) }, shape = RoundedCornerShape(24.dp))
            DialogFilterStrip(
                filter = filter,
                onFilter = onFilter,
                folders = folders,
                folderId = folderId,
                onFolder = onFolder,
                onCreateFolder = onCreateFolder,
                onDeleteFolder = onDeleteFolder,
            )
        }
        if (chats.isEmpty()) Text(
            text = stringResource(if (folderId != 0 || filter != DialogFilter.All) R.string.no_filtered_chats else R.string.no_chats),
            modifier = Modifier.fillMaxWidth().padding(24.dp).testTag("empty_chat_list"),
        )
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(chats, key = { it.peer.key }) { chat ->
                Surface(
                    shape = RoundedCornerShape(26.dp),
                    color = if (chat.peer.key == selectedKey) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.testTag("conversation_${chat.peer.key}")
                        .combinedClickable(onClick = { onOpen(chat.peer) }, onLongClick = { onActions(chat) }),
                ) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        BaleAvatar(avatar = chat.avatar, paths = avatars, label = chat.title, size = 48.dp, online = chat.peer.kind == 1 && chat.peer.id in onlineIds)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(chat.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (chat.pinned) Text("📌", style = MaterialTheme.typography.labelSmall)
                                if (chat.muted) Text("🔕", style = MaterialTheme.typography.labelSmall)
                                if (chat.markedUnread) Text("•", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                            Text(
                                if (chat.draft.isNotBlank()) stringResource(R.string.draft_prefix, chat.draft)
                                else chat.preview.ifBlank { stringResource(R.string.unsupported_message) },
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (chat.draft.isNotBlank()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (chat.unread > 0) Badge { Text(stringResource(R.string.unread_count, chat.unread)) }
                            if (chat.unreadMentions > 0) Text(
                                stringResource(R.string.mentions_count, chat.unreadMentions),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
            if (more) item { TextButton(onClick = onMore, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.more_conversations)) } }
        }
    }
}

@Composable
private fun ChatPane(
    title: String, peer: Peer, connection: Connection, peerOnline: Boolean, queuing: Boolean,
    uploadingName: String?, messages: LazyPagingItems<ChatMessage>, me: Int?,
    composer: ComposerState, typing: Boolean, more: Boolean, loading: Boolean,
    downloaded: Map<Long, String>, playingPath: String?, recordingVoice: Boolean,
    onBack: () -> Unit, onInspect: () -> Unit,
    onSend: (String, () -> Unit) -> Unit,
    onSendAttachment: (UploadPayload, String) -> Unit,
    onAttachmentPickFailed: () -> Unit,
    onOpenPollDialog: () -> Unit,
    onStartVoice: () -> Unit,
    onFinishVoice: (Boolean) -> Unit,
    onDownloadOrOpen: (Attachment) -> Unit,
    onVotePoll: (ChatMessage, Int) -> Unit,
    onSubmitPollSelection: (ChatMessage, List<Int>) -> Unit,
    onRetractPollVote: (ChatMessage) -> Unit,
    onRefreshPolls: () -> Unit,
    onBotButton: (BotButton) -> Unit,
    onRetry: (ChatMessage) -> Unit, onOlder: () -> Unit, onRead: (Long) -> Unit,
    onActions: (ChatMessage) -> Unit, onReaction: (ChatMessage, String, Boolean) -> Unit, onCancelComposer: () -> Unit,
    onDraftChanged: (String) -> Unit, onSearch: () -> Unit, modifier: Modifier,
    avatars: Map<Long, String> = emptyMap(),
    savedDraft: String = "",
    schedule: ScheduleState = ScheduleState(),
    preview: LinkPreviewState? = null,
    isBotPeer: Boolean = false,
    onOpenScheduleComposer: () -> Unit = {},
    onSchedule: (Long) -> Unit = {},
    onOpenScheduled: () -> Unit = {},
    onDismissSchedule: () -> Unit = {},
    onSendNow: (Long) -> Unit = {},
    onCancelScheduled: (Long) -> Unit = {},
    onPreviewLink: (String, Boolean) -> Unit = { _, _ -> },
    onDismissPreview: () -> Unit = {},
    onOpenStickers: () -> Unit = {},
    onOpenGifs: () -> Unit = {},
    onOpenThread: (ChatMessage) -> Unit = {},
    onOpenMedia: (ChatMessage) -> Unit = {},
    onBotMenu: () -> Unit = {},
    onShowPollVoters: (ChatMessage) -> Unit = {},
    onShowReactionVoters: (ChatMessage) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var draft by rememberSaveable(peer.key) { mutableStateOf(savedDraft) }
    var editingDraft by remember(composer.edit?.rid) { mutableStateOf(composer.edit?.text.orEmpty()) }
    val listState = rememberLazyListState()
    var followNewest by remember { mutableStateOf(true) }
    val newestRid = messages.itemSnapshotList.items.firstOrNull { it.peerKey == peer.key }?.rid

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            runCatching { readPickedUri(context, uri, forcePhotoOrVideo = true) }
                .onSuccess { payload ->
                    followNewest = true
                    val caption = draft
                    draft = ""
                    onSendAttachment(payload, caption)
                }
                .onFailure { onAttachmentPickFailed() }
        }
    }
    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching { readPickedUri(context, uri) }
                .onSuccess { payload ->
                    followNewest = true
                    val caption = draft
                    draft = ""
                    onSendAttachment(payload, caption)
                }
                .onFailure { onAttachmentPickFailed() }
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
    val hasOpenPolls by remember(messages, peer.key) { derivedStateOf {
        (0 until messages.itemCount).any { idx ->
            val msg = messages.peek(idx)
            msg?.peerKey == peer.key && msg.poll?.closed == false
        }
    } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(peer.key, visibleIncoming, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            visibleIncoming?.let(onRead)
            awaitCancellation()
        }
    }
    LaunchedEffect(peer.key, connection, hasOpenPolls, lifecycle) {
        if (connection == Connection.Connected && hasOpenPolls) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    delay(10_000)
                    onRefreshPolls()
                }
            }
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
            TextButton(onClick = onInspect, modifier = Modifier.testTag("chat_info_button")) { Text(stringResource(R.string.chat_info)) }
            TextButton(onClick = onSearch) { Text(stringResource(R.string.search_messages)) }
            TextButton(onClick = onOpenScheduled, modifier = Modifier.testTag("scheduled_list")) { Text(stringResource(R.string.scheduled_messages)) }
            if (isBotPeer) {
                TextButton(onClick = onBotMenu, modifier = Modifier.testTag("bot_menu_button")) { Text(stringResource(R.string.open_bot_menu)) }
            }
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
                        onSubmitPollSelection = onSubmitPollSelection,
                        onRetractPollVote = onRetractPollVote,
                        onBotButton = onBotButton,
                        avatars = avatars,
                        onOpenMedia = onOpenMedia,
                        onOpenThread = onOpenThread,
                        onShowPollVoters = onShowPollVoters,
                        onShowReactionVoters = onShowReactionVoters,
                    )
                }
            }
            if (messages.loadState.append is LoadState.Loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (messages.loadState.append.endOfPaginationReached && more) item {
                TextButton(onClick = onOlder, enabled = !loading, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.load_older)) }
            }
        }
        uploadingName?.let { activeName ->
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(
                        stringResource(R.string.uploading_file, activeName),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
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
        var showAttachBar by rememberSaveable(peer.key) { mutableStateOf(false) }
        val input = if (composer.edit != null) editingDraft else draft
        if (composer.edit == null && (showAttachBar || recordingVoice)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (recordingVoice) {
                    Text(stringResource(R.string.recording_voice), Modifier.weight(1f), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge)
                    TextButton(onClick = { onFinishVoice(false) }) { Text(stringResource(R.string.cancel)) }
                    Button(onClick = { followNewest = true; showAttachBar = false; onFinishVoice(true) }) { Text(stringResource(R.string.send_voice)) }
                } else {
                    TextButton(onClick = {
                        showAttachBar = false
                        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                    }) { Text(stringResource(R.string.attach_media)) }
                    TextButton(onClick = {
                        showAttachBar = false
                        documentPicker.launch(arrayOf("*/*"))
                    }) { Text(stringResource(R.string.attach_file)) }
                    TextButton(onClick = {
                        showAttachBar = false
                        onOpenPollDialog()
                    }) { Text(stringResource(R.string.poll_short)) }
                    TextButton(onClick = { showAttachBar = false; onOpenStickers() }, modifier = Modifier.testTag("attach_stickers")) {
                        Text(stringResource(R.string.picker_stickers))
                    }
                    TextButton(onClick = { showAttachBar = false; onOpenGifs() }, modifier = Modifier.testTag("attach_gifs")) {
                        Text(stringResource(R.string.picker_gifs))
                    }
                    TextButton(onClick = { showAttachBar = false; onOpenScheduleComposer() }, modifier = Modifier.testTag("attach_schedule")) {
                        Text(stringResource(R.string.schedule_title))
                    }
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
        val linkedUrl = firstUrlIn(input)
        if (linkedUrl != null && preview == null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = { onPreviewLink(linkedUrl, false) }, modifier = Modifier.testTag("link_preview_button")) {
                    Text(stringResource(R.string.preview_link))
                }
            }
        }
        LinkPreviewCard(
            state = preview,
            onToggleSummary = { withSummary -> onPreviewLink(preview?.url ?: linkedUrl.orEmpty(), withSummary) },
            onDismiss = onDismissPreview,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        if (schedule.open || schedule.composing) {
            ScheduleOverlay(
                state = schedule,
                hasDraft = input.isNotBlank(),
                onPick = { minutes -> onSchedule(minutes) },
                onSendNow = onSendNow,
                onCancel = onCancelScheduled,
                onDismiss = onDismissSchedule,
            )
        }
        val chatKeyboard = LocalSoftwareKeyboardController.current
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
            if (composer.edit == null) {
                FilledTonalButton(
                    onClick = { showAttachBar = !showAttachBar },
                    modifier = Modifier.heightIn(min = 56.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp),
                ) { Text("+") }
            }
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
    onSubmitPollSelection: (ChatMessage, List<Int>) -> Unit,
    onRetractPollVote: (ChatMessage) -> Unit,
    onBotButton: (BotButton) -> Unit,
    avatars: Map<Long, String> = emptyMap(),
    onOpenMedia: (ChatMessage) -> Unit = {},
    onOpenThread: (ChatMessage) -> Unit = {},
    onShowPollVoters: (ChatMessage) -> Unit = {},
    onShowReactionVoters: (ChatMessage) -> Unit = {},
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
                if (!outgoing && group) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        BaleAvatar(
                            avatar = message.senderAvatar,
                            paths = avatars,
                            label = message.senderName.ifBlank { message.senderId.toString() },
                            size = 22.dp,
                        )
                        Text(
                            message.senderName.ifBlank { message.senderId.toString() },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                message.forwardedFrom?.let { fwd ->
                    val originLabel = when {
                        fwd.authorName.isNotBlank() -> stringResource(R.string.forwarded_from_author, fwd.authorName)
                        fwd.publicGroupId != 0 -> stringResource(R.string.forwarded_from_author, "#${fwd.publicGroupId}")
                        fwd.senderUserId != 0 -> stringResource(R.string.forwarded_from_author, "#${fwd.senderUserId}")
                        else -> stringResource(R.string.forwarded_header)
                    }
                    Text(
                        "↪ $originLabel",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
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
                            modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).clip(RoundedCornerShape(16.dp))
                                .clickable(onClick = { onOpenMedia(message) }).testTag("media_thumb_${message.rid}"),
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
                    PollBubbleSection(
                        poll = poll,
                        onVoteSingle = { optionId -> onVotePoll(message, optionId) },
                        onSubmitMultiple = { ids -> onSubmitPollSelection(message, ids) },
                        onRetract = { onRetractPollVote(message) },
                    )
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
                if (message.repliesCount > 0) {
                    TextButton(onClick = { onOpenThread(message) }, modifier = Modifier.testTag("thread_button_${message.rid}")) {
                        Text(stringResource(R.string.thread_replies, message.repliesCount))
                    }
                }
                val pollView = message.poll
                if (pollView != null && pollView.totalVoters > 0) {
                    TextButton(onClick = { onShowPollVoters(message) }, modifier = Modifier.testTag("poll_voters_${message.rid}")) {
                        Text(stringResource(R.string.show_poll_voters))
                    }
                }
                if (message.reactions.isNotEmpty()) {
                    TextButton(onClick = { onShowReactionVoters(message) }, modifier = Modifier.testTag("reaction_voters_${message.rid}")) {
                        Text(stringResource(R.string.show_reaction_voters))
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
private fun PollBubbleSection(
    poll: PollView,
    onVoteSingle: (Int) -> Unit,
    onSubmitMultiple: (List<Int>) -> Unit,
    onRetract: () -> Unit,
) {
    val anyChosen = poll.options.any { it.chosen }
    var multiDraft by remember(poll.pollId, poll.options) {
        mutableStateOf(poll.options.filter { it.chosen }.map { it.id }.toSet())
    }
    val sumVotes = poll.options.sumOf { it.votes }
    val denominator = maxOf(poll.totalVoters, sumVotes).coerceAtLeast(1)
    val summaryCount = if (poll.multipleChoice) poll.totalVoters else maxOf(poll.totalVoters, sumVotes)

    Text(poll.question, style = MaterialTheme.typography.titleMedium)
    val metaParts = buildList {
        add(stringResource(if (poll.anonymous) R.string.poll_anonymous_badge else R.string.poll_public_badge))
        if (poll.multipleChoice) add(stringResource(R.string.poll_multiple))
        if (poll.closed) add(stringResource(R.string.poll_closed_badge))
    }
    Text(
        metaParts.joinToString(" · "),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    poll.options.forEach { option ->
        val isSelected = if (poll.multipleChoice && !anyChosen && !poll.closed) {
            option.id in multiDraft
        } else {
            option.chosen
        }
        val marker = when {
            poll.multipleChoice -> if (isSelected) "☑" else "☐"
            else -> if (isSelected) "●" else "○"
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            OutlinedButton(
                onClick = {
                    if (poll.multipleChoice && !anyChosen) {
                        multiDraft = if (option.id in multiDraft) multiDraft - option.id else multiDraft + option.id
                    } else if (!poll.multipleChoice) {
                        onVoteSingle(option.id)
                    } else {
                        val updated = if (option.id in multiDraft) multiDraft - option.id else multiDraft + option.id
                        multiDraft = updated
                        onSubmitMultiple(updated.toList())
                    }
                },
                enabled = !poll.closed,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(marker, modifier = Modifier.padding(end = 8.dp))
                Text("${option.text} (${option.votes})", modifier = Modifier.weight(1f))
                if (sumVotes > 0 || poll.totalVoters > 0) {
                    val pct = ((option.votes.toFloat() / denominator.toFloat()) * 100f).roundToInt().coerceIn(0, 100)
                    Text("$pct%", style = MaterialTheme.typography.labelSmall)
                }
            }
            if (sumVotes > 0 || poll.totalVoters > 0) {
                LinearProgressIndicator(
                    progress = { (option.votes.toFloat() / denominator.toFloat()).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                )
            }
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.poll_votes_summary, summaryCount), style = MaterialTheme.typography.labelSmall)
        when {
            poll.multipleChoice && !anyChosen && !poll.closed -> {
                Button(
                    onClick = { onSubmitMultiple(multiDraft.toList()) },
                    enabled = multiDraft.isNotEmpty(),
                ) {
                    Text(stringResource(R.string.poll_submit_vote))
                }
            }
            anyChosen && !poll.closed -> {
                TextButton(onClick = onRetract) {
                    Text(stringResource(R.string.poll_retract_vote))
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
