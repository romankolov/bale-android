# Bale Android

An independent, native Android Bale messenger client built with **Jetpack Compose**, **Material 3 Expressive**, **MVVM / Clean Architecture**, **Hilt**, **Google Protobuf Java-Lite**, **Retrofit + OkHttp WebSockets**, **Room**, and **Paging 3**. Minimum SDK: **Android 10 (API 29)**; target/compile SDK: **Android 16 (API 36)**.

> **Status: feature-rich messenger development build — verified in CI across API 29 & API 36 emulators; initial real-account login/chat verified by user.**
> Calls, financial/banking operations, third-party FCM push token server delivery, end-to-end encryption, and production release signing remain explicitly deferred or outside current scope.

See the latest GitHub Actions run and downloadable `development-apk` (`app-debug.apk`) on **[Pull Request #1](https://github.com/romankolov/bale-android/pull/1)**.

## Implemented capabilities across five delivery stages

1. **Foundation, canonical wire schema & Material 3 Expressive UI**
   - Multi-module Clean Architecture (`app`, `core:domain`, `core:protocol`, `core:network`, `core:data`, `core:designsystem`, `feature:auth`, `feature:messaging`).
   - Deterministic canonical `.proto` generation across **2,006 runtime message types, 53 services, and 699 RPCs**, plus **12 provenance-verified wire overrides** in `reference/protocol/overrides.json` validated byte-for-byte by automated tests.
   - Expressive green tonal theme, light/dark modes, adaptive compact bottom navigation / tablet navigation rail + split list-detail layout, and complete **131-key English + Persian (`fa`) RTL** localization.

2. **Authentication, session security & resilient transport**
   - Phone -> SMS/code -> 2FA password / new-account registration over framed gRPC-web HTTPS (`Retrofit` + `OkHttp`).
   - Hardware-backed Android Keystore `AES-GCM` session vault; automatic connected JWT refresh; session-epoch isolation across WebSocket requests and live updates.
   - Active devices / sessions viewer (`Auth.GetAuthSessions`), per-session termination (`TerminateSession`), terminate-all-others (`TerminateAllSessions`), and bounded remote + local sign-out (`SignOut` + local DB & media cache purge).

3. **Rich offline-first messaging & live synchronization**
   - Room-backed `Paging 3` conversation message stream (`50`-item pages, `300`-item window), older server history pagination, dialog list pagination, and automatic missing `Users` / `Groups` resolution.
   - Stable `int64` `rid` outbox with retryable failure recovery across process death, non-destructive Room migrations (`v1 -> v2 -> v3`), committed KSP schema (`core/data/schemas/.../3.json`), and persistent `message_tombstones` preventing deleted-message resurrection.
   - **Replies** with quote preview, **text editing**, **deletion** (for me / for everyone), **forwarding**, **1-tap toggleable emoji reactions** (with own-reaction highlighting), **message pinning / unpinning**, **dialog pin / archive / clear / delete**, **global & in-chat server search**, **throttled typing indicators**, **online presence**, and **delivery / read receipts**.

4. **Media pipeline, voice notes, polls, bot keyboards, contacts, communities & stories**
   - **Media attachments (`ai.bale.server.Files`)**: zero-permission Android Photo Picker (`PickVisualMedia`), SAF file picker (`OpenDocument`), chunked `multipart/form-data` HTTPS uploads, strict cleartext-URL rejection, bounded streaming downloads into account-scoped cache, downsampled inline photo/thumbnail previews, scoped `FileProvider` external viewer, **AAC `.m4a` voice note recorder**, and **inline voice/audio player**.
   - **Contacts & discovery (`bale.users.v1.Users`)**: saved contacts list, live `@username` / channel directory search, add/remove contact, user profile & bio editor, avatar upload, and block/unblock user.
   - **Groups & channels (`bale.groups.v1.Groups`)**: create group or broadcast channel, join via `ble.ir/join/...` invite link or `@handle`, inspect members & pinned messages, edit title/description, copy/revoke invite links, invite contacts, promote/demote admins, kick members, and leave community.
   - **Interactive polls (`bale.poll.v1.Poll`)**: create anonymous or multi-choice polls, vote/retract live in chat bubbles, view vote counts, and close polls.
   - **Bots & stories (`TemplateMessage`, `bale.story.v1.Story`)**: inline callback/URL/copy & reply bot keyboards, active user/channel story feed, text story posting, story reactions, and story deletion.
   - **Notifications**: Android notification channel with `VISIBILITY_PRIVATE`, runtime `POST_NOTIFICATIONS` permission support on Android 13+, and foreground session message notifications.

5. **Verification & CI quality gates**
   - **12** Python protocol & wire-encoding checks (`tools/verify_protocol.py` + `tools/generate_coverage.py --check` tracking **67 implemented RPCs** across 10 services).
   - **47** JVM unit tests across `:core:domain`, `:core:network` (`MockWebServer` WebSocket + chunked media HTTP + RPC wire tests), `:core:data`, `:feature:auth`, and `:feature:messaging`.
   - **Android correctness lint** with `warningsAsErrors = true`.
   - **21 Android instrumentation tests** executed on **both Android 10 (API 29) and Android 16 (API 36)** hardware-accelerated emulators covering app launch, Compose UI flows, Room migrations (`v1 -> v3`, `v2 -> v3`), media upload/caching, polls, join links, deletion tombstones, and cross-account race isolation.

## Build locally

Requires **JDK 17**, **Android SDK 36**, and **Build Tools 35.0.0**:

```sh
./gradlew :core:domain:test :core:network:test :core:data:testDebugUnitTest :feature:auth:testDebugUnitTest :feature:messaging:testDebugUnitTest
./gradlew :app:assembleDebug :app:lintDebug
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
