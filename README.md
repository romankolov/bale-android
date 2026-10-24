# Bale Android

An independent, native Android Bale messenger client built with **Jetpack Compose**, **Material 3 Expressive**, **MVVM / Clean Architecture**, **Hilt**, **Google Protobuf Java-Lite**, **Retrofit + OkHttp WebSockets**, **Room**, and **Paging 3**. Minimum SDK: **Android 10 (API 29)**; target/compile SDK: **Android 16 (API 36)**.

> **Status: `1.0.0-rc3` release candidate — every capability below is CI-verified on Android 10 (API 29) and Android 16 (API 36) emulators. Beyond the rc1 feature set, live-device hardening landed real avatars & names everywhere, server-shaped uploads for every file class, file-reference-echoed sticker sends, online/last-seen presence, per-user story trays, Ogg/Opus voice notes, upload/download progress, pin/archive list integrity, and chat lists & screens that stay live within ~10 s worst case even on a push-starved network.**
> Voice/video calls, financial/banking operations, third-party FCM push-token server delivery, end-to-end encryption, and production (Play) release signing remain explicitly deferred or outside scope. Release-candidate APKs are signed with the debug key so they install over an existing test build.

Every push publishes two installable artifacts on **[Pull Request #1](https://github.com/romankolov/bale-android/pull/1)**: `development-apk` (debug, line-numbered stacks) and `release-candidate-apk` (R8-minified, resource-shrunk, same signing key).

## Implemented capabilities across five delivery stages

1. **Foundation, canonical wire schema & Material 3 Expressive UI**
   - Multi-module Clean Architecture (`app`, `core:domain`, `core:protocol`, `core:network`, `core:data`, `core:designsystem`, `feature:auth`, `feature:messaging`).
   - Deterministic canonical `.proto` generation across **2,006 runtime message types, 53 services, and 699 RPCs**, plus **13 provenance-verified wire overrides** in `reference/protocol/overrides.json` validated byte-for-byte by automated tests (including repeated sticker entries inside `Collections`).
   - Expressive green tonal theme, light/dark modes, adaptive compact bottom navigation / tablet navigation rail + split list-detail layout, and complete **262-key English + Persian (`fa`) RTL** localization.

2. **Authentication, session security & resilient transport**
   - Phone -> SMS/code -> 2FA password / new-account registration over framed gRPC-web HTTPS (`Retrofit` + `OkHttp`).
   - Hardware-backed Android Keystore `AES-GCM` session vault; automatic connected JWT refresh; session-epoch isolation across WebSocket requests and live updates.
   - Active devices / sessions viewer (`Auth.GetAuthSessions`), per-session termination (`TerminateSession`), terminate-all-others (`TerminateAllSessions`), and bounded remote + local sign-out (`SignOut` + local DB & media cache purge).

3. **Rich offline-first messaging & live synchronization**
   - Room-backed `Paging 3` conversation message stream (`50`-item pages, `300`-item window), older server history pagination, dialog list pagination, and automatic missing `Users` / `Groups` resolution.
   - Stable `int64` `rid` outbox with retryable failure recovery across process death, non-destructive Room migrations (`v1 -> v2 -> v3`), committed KSP schema (`core/data/schemas/.../3.json`), and persistent `message_tombstones` preventing deleted-message resurrection.
   - **Replies** with quote preview, **text editing**, **deletion** (for me / for everyone), **forwarding**, **1-tap toggleable emoji reactions** (with own-reaction highlighting), **message pinning / unpinning**, **dialog pin / archive / clear / delete**, **global & in-chat server search**, **throttled typing indicators**, **online presence**, and **delivery / read receipts**.

4. **Media pipeline, voice notes, polls, bot keyboards, contacts, communities & stories**
   - **Media attachments (`ai.bale.server.Files`)**: zero-permission Android Photo Picker (`PickVisualMedia`), SAF file picker (`OpenDocument`), a single streaming `multipart/form-data` `PUT` per file (`chunkSize` is ignored by the storage host), `sendType` per media class, positive CRC32 metadata, `DocumentMessage.accessHash` population, strict cleartext-URL rejection and host-scoped header stripping so cross-host storage URLs never receive Bale auth headers, bounded streaming downloads into account-scoped cache, downsampled inline photo/thumbnail previews, scoped `FileProvider` external viewer, **AAC `.m4a` voice note recorder**, and **inline voice/audio player**.
   - **Contacts & discovery (`bale.users.v1.Users`)**: saved contacts list, live `@username` / channel directory search, add/remove contact, user profile & bio editor, avatar upload, and block/unblock user.
   - **Groups & channels (`bale.groups.v1.Groups`)**: create group or broadcast channel, join via `ble.ir/join/...` invite link or `@handle`, inspect members & pinned messages, edit title/description, copy/revoke invite links, invite contacts, promote/demote admins, kick members, and leave community.
   - **Interactive polls (`bale.poll.v1.Poll`)**: create anonymous or multi-choice polls, vote/retract live in chat bubbles, view vote counts, and close polls.
   - **Bots & stories (`TemplateMessage`, `bale.story.v1.Story`)**: inline callback/URL/copy & reply bot keyboards, active user/channel story feed, text story posting, story reactions, and story deletion.
   - **Notifications**: Android notification channel with `VISIBILITY_PRIVATE`, runtime `POST_NOTIFICATIONS` permission support on Android 13+, and foreground session message notifications.

5. **Release-candidate feature breadth (folders, media viewing, bots, moderation, settings)**
   - **Chat folders & filters (`Messaging/LoadFolders`, `CreateFolder`, `EditFolder`, `DeleteFolder`, `LoadFolderDialogs`, `LoadPinnedDialogs`, `MarkDialogsAsRead/Unread`, `MentionRead`)**: All / Unread / Personal / Groups / Bots / Saved / Archived filter strip, server-backed folder tabs with unread counters, inline folder creation from the chat list, folder membership from a long-press sheet, and per-account dialog state (draft, pin, mute, marked-unread, mention counters) kept in `SharedPreferences` so Room stays at schema **v3** with no destructive migration. `Saved Messages` opens your own self-chat.
   - **Full-screen media viewer**: photos (downsampled decode), looping video playback (`VideoView`), documents with external open, in-viewer caption, GIF saving, and **voice transcription** through `bale.turing.v1.AI/GetTranscript` with `mustWait` handling.
   - **Stickers & GIFs (`bale.v1.Images`)**: own sticker packs (`LoadOwnStickers`, `LoadStickerCollection`) rendered in a picker sheet, sticker sending by `stickerId` + collection `accessHash`, pack add/remove, and saved-GIF list/send/remove/`UseGif` accounting.
   - **Scheduled sending (`bale.schedule.v1.Scheduler`)**: quick-delay composer sheet (`ScheduleTask` wrapping a real `SendMessage` payload with a `Timestamp`), pending task list (`ListTasks`), send-now (`ExecuteTaskNow`) and cancel (`UnScheduleTask`).
   - **Threads & channel comments**: reply counters read from `History.replies`, `LoadReplies` sheet with inline reply box, and `threadId` set on `SendMessage` so replies land in the right topic.
   - **Bots & Mini-Apps (`bale.ketf.v1.Ketf`, `bale.appzar.v1.Appzar`, `bale.garson.v1.Garson`)**: bot menu button (`GetMenuButton`), slash-command list from `FullUser.botCommands`, Mini-App launch (`GetMiniAppUrl`) inside a `WebView` with a `BaleAndroid` JS bridge plus a `Telegram.WebApp` compatibility shim, answer submission (`SendMiniAppData`), inline bot results (`GetInlineBotResults`), callback answers (`SendInlineCallback`), and recommended bots (`GetRecommendedBots`).
   - **Link previews & summaries (`bale.tldr.v1.TLDR`)**: composer card with `GetLinkPreview` metadata and optional `GetLinkSummary` text.
   - **Shared media gallery (`bale.shared_media.v1.SharedMediaService/LoadMedia`)** per Media / Files / Voice / Links tab inside the chat info sheet.
   - **Real avatars**: `SmallImage` file locations resolved through `Files/GetNasimFileUrl`, decoded with bitmap sampling, cached per file id and shared by bubbles, chat rows, stories, contacts and the inspector (initials remain the fallback).
   - **Moderation & administration**: `Report/ReportInappropriateContent` for a message or a whole chat with reason picker, `Groups/GetBannedUsers`, `FetchGroupAdmins`, `EditGroupAvatar`, `EditChannelNick`, plus `Users/CheckNickName` availability feedback for public links.
   - **Account settings**: theme mode (system / light / dark), Material You dynamic colour toggle, in-app English / Persian language override with RTL flip, two-step verification status with `Enable`/`Disable`, read-only `Users/GetUserFullPrivacy` display, blocked-user list with unblock, and folder management.
   - **New `RichFeaturesApi`** isolates the release-candidate RPCs; **`tools/check_kotlin_consistency.py`** statically validates every cross-layer call (receiver member existence, arity, trailing-lambda arguments, repository overrides against the domain interfaces, domain data-class named arguments and delimiter balance) so drift fails in the 10-second `protocol` job instead of burning a Gradle cycle.

6. **Verification & CI quality gates**
   - **12** Python protocol & wire-encoding checks (`tools/verify_protocol.py`), `tools/generate_coverage.py --check` tracking **113 implemented RPCs** across 16 services, and `tools/check_kotlin_consistency.py` static cross-layer verification.
   - **75 JVM unit tests** across `:core:domain`, `:core:network` (`MockWebServer` WebSocket, single-`PUT` media transport, RPC wire contracts and the release-candidate `RichFeaturesApi` shapes), `:core:data` (message mapping, forwarded envelopes, poll hydration, preference store), `:feature:auth` and `:feature:messaging` (view-model flows and chat filters).
   - **Android correctness lint** with `warningsAsErrors = true`, plus a **release build** (`:app:assembleRelease`) that proves the R8 keep rules for protobuf-lite, Retrofit interfaces and the Mini-App `@JavascriptInterface` bridge.
   - **24 Android instrumentation tests** executed on **both Android 10 (API 29) and Android 16 (API 36)** hardware-accelerated emulators covering app launch, Compose UI flows, Room migrations (`v1 -> v3`, `v2 -> v3`), media upload/caching, polls, join links, deletion tombstones, and cross-account race isolation.

## Build locally

Requires **JDK 17**, **Android SDK 36**, and **Build Tools 35.0.0**:

```sh
./gradlew :core:domain:test :core:network:test :core:data:testDebugUnitTest :feature:auth:testDebugUnitTest :feature:messaging:testDebugUnitTest
./gradlew :app:assembleDebug :app:lintDebug :app:assembleRelease
./gradlew :app:connectedDebugAndroidTest :core:data:connectedDebugAndroidTest
```

## Protocol verification without Android SDK

```sh
python3 -m venv .venv
.venv/bin/pip install -r tools/requirements.txt
python3 tools/generate_protocol.py --check
python3 tools/generate_coverage.py --check
.venv/bin/python tools/verify_protocol.py
```

Further documentation:
- [Implementation plan & batch status](docs/IMPLEMENTATION_PLAN.md)
- [Capability matrix](docs/CAPABILITIES.md)
- [Architecture & security boundaries](docs/ARCHITECTURE.md)
- [Five-stage roadmap](docs/ROADMAP.md)
- [Verification policy & evidence](docs/VERIFICATION.md)
- [Complete 699-RPC inventory](docs/rpc-coverage.csv)
