package ai.bale.client.feature.messaging

import ai.bale.client.core.designsystem.R
import ai.bale.client.domain.*
import android.Manifest
import android.content.ClipData
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
internal fun ContactsPane(
    contacts: List<ContactEntry>,
    directoryResults: List<ContactEntry>,
    onSearchDirectory: (String) -> Unit,
    onOpenPeer: (Peer) -> Unit,
    onAddContact: (Peer) -> Unit,
    onRemoveContact: (Peer) -> Unit,
    onRefresh: () -> Unit,
    onNewCommunity: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var search by rememberSaveable { mutableStateOf("") }
    Column(modifier.fillMaxHeight().padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.nav_contacts), style = MaterialTheme.typography.headlineLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onRefresh) { Text(stringResource(R.string.refresh)) }
                FilledTonalButton(onClick = onNewCommunity) { Text(stringResource(R.string.new_community)) }
            }
        }
        OutlinedTextField(
            value = search,
            onValueChange = {
                search = it
                onSearchDirectory(it)
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            label = { Text(stringResource(R.string.search_directory_hint)) },
        )
        val shown = if (search.isNotBlank()) directoryResults else contacts
        if (shown.isEmpty()) {
            Text(stringResource(R.string.no_contacts), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp))
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(shown, key = { it.peer.key }) { item ->
                val isSaved = contacts.any { it.peer.key == item.peer.key }
                Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(
                        Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Surface(Modifier.size(44.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(item.name.take(1), style = MaterialTheme.typography.titleMedium)
                            }
                        }
                        Column(Modifier.weight(1f)) {
                            Text(item.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (item.handle.isNotBlank()) {
                                Text("@${item.handle}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        if (!item.isGroup) {
                            TextButton(onClick = { if (isSaved) onRemoveContact(item.peer) else onAddContact(item.peer) }) {
                                Text(stringResource(if (isSaved) R.string.remove_contact else R.string.add_contact))
                            }
                        }
                        Button(onClick = { onOpenPeer(item.peer) }) { Text(stringResource(R.string.open_conversation)) }
                    }
                }
            }
        }
    }
}

@Composable
internal fun StoriesPane(
    stories: List<StoryItem>,
    myAccountId: Int?,
    onRefresh: () -> Unit,
    onPostTextStory: (String) -> Unit,
    onReactStory: (String, String) -> Unit,
    onDeleteStory: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by rememberSaveable { mutableStateOf("") }
    Column(modifier.fillMaxHeight().padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.nav_stories), style = MaterialTheme.typography.headlineLarge)
            TextButton(onClick = onRefresh) { Text(stringResource(R.string.refresh)) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft,
                onValueChange = { if (it.length <= 500) draft = it },
                modifier = Modifier.weight(1f),
                maxLines = 3,
                shape = RoundedCornerShape(22.dp),
                placeholder = { Text(stringResource(R.string.story_hint)) },
            )
            Button(
                onClick = {
                    val text = draft
                    draft = ""
                    onPostTextStory(text)
                },
                enabled = draft.isNotBlank(),
            ) { Text(stringResource(R.string.post_story)) }
        }
        if (stories.isEmpty()) {
            Text(stringResource(R.string.no_stories), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp))
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(stories, key = { it.id }) { story ->
                val thumbBitmap = remember(story.id, story.media) {
                    story.media?.thumbnail?.let { decodeSampledBitmap(it, 512) }
                }
                Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(story.ownerTitle, style = MaterialTheme.typography.titleMedium)
                            if (story.ownerId == myAccountId) {
                                TextButton(onClick = { onDeleteStory(story.id) }) {
                                    Text(stringResource(R.string.delete_message), color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                        if (thumbBitmap != null) {
                            Image(
                                bitmap = thumbBitmap,
                                contentDescription = story.ownerTitle,
                                modifier = Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(16.dp)),
                                contentScale = ContentScale.Crop,
                            )
                        }
                        if (story.text.isNotBlank()) {
                            Text(story.text, style = MaterialTheme.typography.bodyLarge)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("❤️", "👍", "🔥", "👏").forEach { emoji ->
                                FilterChip(
                                    selected = story.myReaction == emoji,
                                    onClick = { onReactStory(story.id, emoji) },
                                    label = { Text(emoji) },
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
internal fun SettingsPane(
    account: Account?,
    profile: PeerProfile?,
    sessions: List<AuthSessionInfo>,
    onRefresh: () -> Unit,
    onSaveProfile: (String, String, String) -> Unit,
    onUploadAvatar: (UploadPayload) -> Unit,
    onTerminateSession: (Int) -> Unit,
    onTerminateOtherSessions: () -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember(profile?.title, account?.name) { mutableStateOf(profile?.title ?: account?.name.orEmpty()) }
    var handle by remember(profile?.handle) { mutableStateOf(profile?.handle.orEmpty()) }
    var about by remember(profile?.about) { mutableStateOf(profile?.about.orEmpty()) }
    var notifGranted by remember { mutableStateOf(Build.VERSION.SDK_INT < 33) }

    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            runCatching { readPickedUri(context, uri, forcePhotoOrVideo = true) }.getOrNull()?.let(onUploadAvatar)
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notifGranted = granted
    }

    LazyColumn(
        modifier = modifier.fillMaxHeight(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.nav_settings), style = MaterialTheme.typography.headlineLarge)
                TextButton(onClick = onRefresh) { Text(stringResource(R.string.refresh)) }
            }
        }
        item {
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.profile_section), style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.display_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(handle, { handle = it }, label = { Text(stringResource(R.string.username_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(about, { about = it }, label = { Text(stringResource(R.string.bio_label)) }, maxLines = 3, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onSaveProfile(name, handle, about) }) { Text(stringResource(R.string.save_message)) }
                        OutlinedButton(onClick = {
                            avatarPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }) { Text(stringResource(R.string.change_avatar)) }
                    }
                }
            }
        }
        item {
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.notifications_section), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.notifications_detail), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (Build.VERSION.SDK_INT >= 33 && !notifGranted) {
                        OutlinedButton(onClick = { permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                            Text(stringResource(R.string.enable_notifications))
                        }
                    }
                }
            }
        }
        item {
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.active_sessions), style = MaterialTheme.typography.titleMedium)
                        if (sessions.size > 1) {
                            TextButton(onClick = onTerminateOtherSessions) {
                                Text(stringResource(R.string.terminate_others), color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    sessions.forEach { s ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${s.deviceTitle} · ${s.appTitle}", style = MaterialTheme.typography.bodyLarge)
                                if (s.location.isNotBlank()) Text(s.location, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = { onTerminateSession(s.id) }) {
                                Text(stringResource(R.string.terminate_session), color = MaterialTheme.colorScheme.error)
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
        item {
            Button(
                onClick = onLogout,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.logout)) }
        }
    }
}

@Composable
internal fun NewCommunityDialog(
    contacts: List<ContactEntry>,
    onCreate: (String, Boolean, String, List<Peer>) -> Unit,
    onJoin: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by rememberSaveable { mutableStateOf("") }
    var handle by rememberSaveable { mutableStateOf("") }
    var joinLink by rememberSaveable { mutableStateOf("") }
    var isChannel by rememberSaveable { mutableStateOf(false) }
    val picked = remember { mutableStateListOf<String>() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.new_community)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    OutlinedTextField(joinLink, { joinLink = it }, label = { Text(stringResource(R.string.join_link_hint)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (joinLink.isNotBlank()) {
                        Button(onClick = { onJoin(joinLink); onDismiss() }, modifier = Modifier.padding(top = 6.dp)) {
                            Text(stringResource(R.string.join_community))
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.community_title)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(handle, { handle = it }, label = { Text(stringResource(R.string.username_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(isChannel, { isChannel = it })
                        Text(stringResource(R.string.create_as_channel))
                    }
                }
                items(contacts, key = { it.peer.key }) { contact ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = contact.peer.key in picked,
                            onCheckedChange = { checked -> if (checked) picked += contact.peer.key else picked -= contact.peer.key },
                        )
                        Text(contact.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val members = contacts.filter { it.peer.key in picked }.map { it.peer }
                    onCreate(title, isChannel, handle, members)
                    onDismiss()
                },
                enabled = title.isNotBlank(),
            ) { Text(stringResource(R.string.create_action)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
internal fun CreatePollDialog(
    onCreate: (String, List<String>, Boolean, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var question by rememberSaveable { mutableStateOf("") }
    var opt1 by rememberSaveable { mutableStateOf("") }
    var opt2 by rememberSaveable { mutableStateOf("") }
    var opt3 by rememberSaveable { mutableStateOf("") }
    var anonymous by rememberSaveable { mutableStateOf(true) }
    var multiple by rememberSaveable { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.create_poll)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(question, { question = it }, label = { Text(stringResource(R.string.poll_question)) }, singleLine = true)
                OutlinedTextField(opt1, { opt1 = it }, label = { Text(stringResource(R.string.poll_option_1)) }, singleLine = true)
                OutlinedTextField(opt2, { opt2 = it }, label = { Text(stringResource(R.string.poll_option_2)) }, singleLine = true)
                OutlinedTextField(opt3, { opt3 = it }, label = { Text(stringResource(R.string.poll_option_3)) }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(anonymous, { anonymous = it }); Text(stringResource(R.string.poll_anonymous))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(multiple, { multiple = it }); Text(stringResource(R.string.poll_multiple))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onCreate(question, listOf(opt1, opt2, opt3), anonymous, multiple)
                    onDismiss()
                },
                enabled = question.isNotBlank() && listOf(opt1, opt2, opt3).count { it.isNotBlank() } >= 2,
            ) { Text(stringResource(R.string.send)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PeerInspectorSheet(
    profile: PeerProfile,
    contacts: List<ContactEntry>,
    meId: Int?,
    onDismiss: () -> Unit,
    onPinDialog: (Boolean) -> Unit,
    onArchiveDialog: (Boolean) -> Unit,
    onClearChat: () -> Unit,
    onDeleteChat: () -> Unit,
    onSetBlocked: (Boolean) -> Unit,
    onEditCommunity: (String, String) -> Unit,
    onLeaveCommunity: () -> Unit,
    onRevokeInvite: () -> Unit,
    onInviteMember: (Peer) -> Unit,
    onKickMember: (Peer) -> Unit,
    onSetAdmin: (Peer, Boolean) -> Unit,
    onUnpinMessage: (ChatMessage) -> Unit,
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var editTitle by remember(profile.peer.key, profile.title) { mutableStateOf(profile.title) }
    var editAbout by remember(profile.peer.key, profile.about) { mutableStateOf(profile.about) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 28.dp),
        ) {
            item {
                Text(profile.title, style = MaterialTheme.typography.headlineSmall)
                if (profile.handle.isNotBlank()) Text("@${profile.handle}", color = MaterialTheme.colorScheme.primary)
                if (profile.about.isNotBlank()) Text(profile.about, style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { onPinDialog(true) }) { Text(stringResource(R.string.pin_chat)) }
                    TextButton(onClick = { onArchiveDialog(true) }) { Text(stringResource(R.string.archive_chat)) }
                    TextButton(onClick = onClearChat) { Text(stringResource(R.string.clear_chat)) }
                    TextButton(onClick = onDeleteChat) { Text(stringResource(R.string.delete_chat), color = MaterialTheme.colorScheme.error) }
                }
                if (!profile.isGroup) {
                    OutlinedButton(onClick = { onSetBlocked(!profile.isBlocked) }) {
                        Text(stringResource(if (profile.isBlocked) R.string.unblock_user else R.string.block_user))
                    }
                }
            }
            if (profile.pinnedMessages.isNotEmpty()) {
                item {
                    Text(stringResource(R.string.pinned_messages_title), style = MaterialTheme.typography.titleMedium)
                    profile.pinnedMessages.forEach { pinned ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(pinned.text, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            TextButton(onClick = { onUnpinMessage(pinned) }) { Text(stringResource(R.string.unpin_action)) }
                        }
                    }
                }
            }
            if (profile.isGroup) {
                item {
                    OutlinedTextField(editTitle, { editTitle = it }, label = { Text(stringResource(R.string.community_title)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(editAbout, { editAbout = it }, label = { Text(stringResource(R.string.bio_label)) }, maxLines = 3, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onEditCommunity(editTitle, editAbout) }) { Text(stringResource(R.string.save_message)) }
                        OutlinedButton(onClick = onLeaveCommunity) {
                            Text(stringResource(R.string.leave_community), color = MaterialTheme.colorScheme.error)
                        }
                    }
                    if (profile.inviteUrl.isNotBlank()) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(profile.inviteUrl, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = {
                                scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("invite", profile.inviteUrl))) }
                            }) { Text(stringResource(R.string.copy_link)) }
                            TextButton(onClick = onRevokeInvite) { Text(stringResource(R.string.revoke_link)) }
                        }
                    }
                    val invitable = contacts.filter { c -> profile.members.none { it.userId == c.peer.id } }
                    if (invitable.isNotEmpty()) {
                        Text(stringResource(R.string.invite_contact), style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            invitable.take(4).forEach { c ->
                                AssistChip(onClick = { onInviteMember(c.peer) }, label = { Text("+ ${c.name}") })
                            }
                        }
                    }
                    Text(stringResource(R.string.members_count, profile.membersCount), style = MaterialTheme.typography.titleMedium)
                }
                items(profile.members, key = { it.userId }) { member ->
                    val memberPeer = Peer(member.userId, 1, member.accessHash)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(member.name + if (member.isAdmin) " · Admin" else "", style = MaterialTheme.typography.bodyLarge)
                            if (member.title.isNotBlank()) Text(member.title, style = MaterialTheme.typography.labelSmall)
                        }
                        if (profile.isOwner && member.userId != meId) {
                            Row {
                                TextButton(onClick = { onSetAdmin(memberPeer, !member.isAdmin) }) {
                                    Text(stringResource(if (member.isAdmin) R.string.remove_admin else R.string.make_admin))
                                }
                                TextButton(onClick = { onKickMember(memberPeer) }) {
                                    Text(stringResource(R.string.kick_member), color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
