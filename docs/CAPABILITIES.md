# Capability coverage

The initial real-account login, dialog sync, and basic messaging baseline were verified on a live account by the user, together with the file-upload, forwarded-message and poll-voting repairs. Everything else below is CI-verified (protocol checks, static cross-layer checks, 75 JVM tests, strict lint, R8 release build and 24 instrumentation tests on Android 10 and Android 16 emulators) and awaits live-account acceptance for the newly wired RPCs.

| Capability | Status | Notes / Remaining scope |
|---|---|---|
| Canonical protobuf schema (2,006 messages / 699 RPCs) | Implemented & tested | 13 provenance-documented wire overrides in `reference/protocol/overrides.json` |
| Phone / code / 2FA password / registration | User live-verified baseline | Encrypted Keystore `AES-GCM` session storage |
| Connected JWT refresh & session-epoch guards | Implemented & tested | Prevents stale cross-account RPCs or buffered socket updates |
| Active sessions viewer & remote termination | Implemented & tested | `GetAuthSessions`, `TerminateSession`, `TerminateAllSessions`, `SignOut` |
| Conversations, Paging 3 history & pagination | Implemented & tested | 50-row pages, 300-row window, missing user/group resolution |
| Outbox, retry & non-destructive Room migrations | Implemented & tested | Schema `v1 -> v2 -> v3`; KSP schema `3.json` committed; persistent delete tombstones |
| Replies, edits, deletes, forwards & pins | Implemented & tested | Packed `int64` delete dates; local vs everyone delete; message & dialog pins |
| Emoji reactions | Implemented & tested | Multi-emoji reaction state + 1-tap add/remove highlighting user's own reactions |
| Server message search | Implemented & tested | Global or current-conversation conjunctive `Query2` search |
| Typing, online status & delivery/read receipts | Implemented & tested | Coalesced monotonic `MessageRead`; throttled outgoing typing; `Presence/GetUsersPresence` + `SetOnline` + `SubscribeFromOnline` push online/last-seen (“recently/week/month” privacy buckets included) onto dialog rows and the chat header, `GetGroupOnlineCount` + `GroupOnline` for “N online” in groups |
| Media upload, single streaming PUT & bounded download | Implemented & tested | Photo Picker, SAF files, `sendType` per media class, inline photo/thumb bitmaps, HTTPS enforcement, `FileProvider` |
| Voice notes & audio playback | Implemented & tested | Runtime `RECORD_AUDIO` permission; Ogg/Opus 48 kHz mono @ 32 kbps recorder (MediaCodec + in-repo `OggOpusWriter` muxer, unit-tested) matching the web client’s container, with an automatic `.m4a` AAC fallback when a device lacks the Opus encoder; inline `MediaPlayer` controls |
| Interactive polls | Implemented & tested | `CreatePoll`, `Vote` / retract, `GetPollResults`, `ClosePoll` |
| Bot keyboards & template messages | Implemented & tested | Inline callback / `https://` URL / copy & reply keyboard buttons |
| Contacts, profiles & user blocking | Implemented & tested | `GetContacts`, `SearchContacts`, `AddContact`, `RemoveContact`, `BlockUser`, profile & avatar edit |
| Groups, channels, invites & moderation | Implemented & tested | Create group/channel, join by `ble.ir/join/...` or `@handle`, members, admin roles, kick, leave |
| Stories | Implemented & tested | Active story feed, text story publishing, emoji reactions & story deletion |
| Local notifications | Implemented & tested | Private lockscreen notification channel + Android 13+ `POST_NOTIFICATIONS` permission flow |
| Chat folders, filters & dialog flags | Implemented & tested | `LoadFolders`, `CreateFolder`, `EditFolder`, `DeleteFolder`, `LoadFolderDialogs`, `LoadPinnedDialogs`, `MarkDialogsAsRead/Unread`, `MentionRead`; draft/pin/mute/unread/mention state in `SharedPreferences` so Room stays at schema v3 |
| Full-screen media viewer & voice transcript | Implemented & tested | Sampled image decode, looping `VideoView`, document hand-off, `bale.turing.v1.AI/GetTranscript` with `mustWait` handling |
| Stickers & saved GIFs | Implemented & tested | `LoadOwnStickers`, `LoadStickerCollection`, `Add/RemoveStickerCollection`, `GetSavedGifs`, `AddGif`, `RemoveGif`, `UseGif`; sticker send by `stickerId` + collection `accessHash` + stored `format` (no invented file locations — the server resolves the binary); picker previews reuse the same file cache as avatars |
| Scheduled sending | Implemented & tested | `ScheduleTask` (wraps a real `SendMessage` + `Timestamp`), `ListTasks`, `ExecuteTaskNow`, `UnScheduleTask` |
| Threads & channel comments | Implemented & tested | `History.replies` counters, `LoadReplies`, `GetMessagesRepliesInfo`, `threadId` on `SendMessage` |
| Bots, inline results & Mini-Apps | Implemented & tested | `GetMenuButton`, `GetMiniAppUrl` + `WebView` JS bridge (with a `Telegram.WebApp` shim), `SendMiniAppData`, `GetInlineBotResults`, `SendInlineCallback`, `GetRecommendedBots`, bot command list |
| Link preview & summary | Implemented & tested | `bale.tldr.v1.TLDR/GetLinkPreview` + `GetLinkSummary`, composer card with toggle |
| Shared media gallery | Implemented & tested | `SharedMediaService/LoadMedia` per Media / Files / Voice / Links tab inside the chat info sheet |
| Real avatars | Implemented & tested | `Avatar.smallImage` file locations resolved through `Files/GetNasimFileUrl`, cached per file id, shared by bubbles, rows, stories, contacts and inspector; initials as fallback |
| Moderation & administration | Implemented & tested | `Report/ReportInappropriateContent`, `GetBannedUsers`, `FetchGroupAdmins`, `EditGroupAvatar`, `EditChannelNick`, `CheckNickName` |
| Appearance, 2FA & privacy settings | Implemented & tested | Theme mode, Material You dynamic colour, EN/FA in-app locale override with RTL flip, `IsTwoFactorAuthenticationEnabled` + enable/disable, read-only `GetUserFullPrivacy`, blocked-user list with unblock |
| Live updates & upload/download progress | Implemented & tested | Socket-pushed messages bump dialog date/preview/unread in Room directly (chats reorder without a manual refresh, unseen peers are seeded); uploads are one full-body PUT (sendType only where the host transcodes; uid always the signer) with percent callbacks streamed into the composer chip, staged picks gain a caption row before sending, and a Settings diagnostics line reports push/poll health; downloads report percent on bubbles and in the viewer; a staleness poller re-reads dialogs/history every ~10 s while no push arrives, idle whenever pushes flow |
| Adaptive Material 3 Expressive UI & EN/FA RTL | Implemented & tested | 270 verified English + Persian string pairs |
| Voice/video calls, banking/wallets & FCM push | Explicitly deferred | Outside messenger-first scope or requires Bale server push credentials |

Machine-readable RPC tracking lives in [docs/rpc-coverage.csv](rpc-coverage.csv) (**113 source-implemented RPCs** across 16 services out of 699 recovered endpoints).
