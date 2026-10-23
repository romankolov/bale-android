# Five-stage implementation roadmap

Scope agreed: full **messenger** first, Android 10+; proceed incrementally through
all five stages. Calls and financial services remain explicitly deferred.
Stages are acceptance gates, not promises that schema generation delivers parity.

## 1 — Foundation, protocol audit and design system

**Source work delivered**
- Eight modules, Gradle wrapper/catalog, shared Android-library conventions.
- Compose/MVVM with domain repository contracts and Hilt composition.
- Deterministic canonical schema; transport-envelope definitions; inventory of
  all 699 RPCs, field/type verification and Java-lite code generation checks.
- Material 3 Expressive theme, dark/light colors, English/Persian, RTL support,
  adaptive list-detail UI and baseline accessibility semantics.

**Gate outstanding**
- First successful Gradle sync, debug build and Android Lint run.
- Device/RTL/font-scaling/screen-reader review; design previews/screenshots.
- Commit Room's generated v1 schema after the actual annotation processor runs.

**Exit:** reproducibly buildable shell and reviewed capability inventory.

## 2 — Authentication and transport

**Source work delivered**
- gRPC-web framing/error parsing and bounded response reads over Retrofit/OkHttp.
- Phone → code → password/registration flows with explicit unsupported-mode errors.
- Stable device UUID; Keystore AES-GCM session vault and local logout.
- WebSocket handshake, metadata, request correlation, cancellation/timeouts,
  heartbeat/pong deadlines, reconnect with jitter, unauthorized termination.
- Token refresh scheduling while the process is connected.
- JUnit/MockWebServer transport tests and auth ViewModel tests authored.

**Gate outstanding**
- Run authored Kotlin tests and real login/session-refresh/reconnect scenarios.
- Resend/rate-limit/cooldown handling, recovery flows and remote session management.
- Verify current build metadata, endpoint reachability and registration/JWT behavior.
- Review certificate/platform trust and retry policies with real failure traces
  that have had all user data removed. Do not disable TLS validation.

**Exit:** real account login/resume/reconnect/logout with automated regression tests.

## 3 — Offline-first messaging

**Source work delivered**
- Account-scoped Room conversation/history persistence and reactive UI.
- Initial dialog/history loading, manual older-history loading, text sending,
  stable-rid outbox, explicit retry, read RPC and basic incoming-message updates.
- Local conversation-title filtering, adaptive phone/tablet chat screens.
- Draft cleared only after successful local enqueue; interrupted attempts recover
  as retryable failures. No automatic replay of ambiguous RPC outcomes.

**Gate outstanding**
- Room/instrumented tests, migration baseline, full reconnect catch-up and update
  ordering/deduplication, deletion reconciliation and missed-update recovery.
- Paging 3/RemoteMediator, complete dialog pagination, missing peer resolution.
- Replies, edits/deletes/forwards, reactions, typing/presence, server search,
  delivery/read ticks, archive/pin/mute and new-chat flows.
- Persistent-work policy and WorkManager integration for eligible outbox work.
  Retries must be reconciled and idempotent, not blindly resent.

**Exit:** two real accounts exchange messages and recover correctly after process
kill, network changes, duplicate events and interrupted sends.

## 4 — Media and complete messenger capabilities

**Not implemented yet — no fake enabled controls.**
- Files RPCs, secure URL validation, streaming uploads/downloads, progress,
  resume/cancellation, cache limits and Android Photo Picker/SAF integration.
- Coil image loading, Media3 playback, microphone permissions, voice recording.
- Contacts/profiles, privacy/block/report, group/channel membership/admin roles,
  invite links, polls, stories and bot interactions supported by recovered APIs.
- Notifications/channels, Android 13+ runtime permission handling and a verified
  push registration/delivery strategy. Do not assume access to Bale's Firebase
  project or keep an invisible perpetual background service alive.
- Device/account settings, remote logout, session management, recovery flows.
- Individually triage every remaining messenger-relevant RPC and update variant.

**Exit:** every agreed messenger feature implemented and tested, or explicitly
blocked by missing protocol details/permissions with an approved limitation.
A `.proto` signaling service is not sufficient to implement calls or authorize
financial transactions; those remain a separate deferred scope.

## 5 — Hardening and release

**Started**
- Automated protocol checks pass; JVM/MockWebServer/Compose tests authored.
- CI configuration for generation, build, unit tests, lint and test compilation.
- Sensitive-data storage/logging/backup baseline and explicit coverage tracking.

**Gate outstanding**
- Green CI, Android instrumentation and Room tests; emulator/device matrix
  covering Android 10 and current target behavior.
- Persian/English, RTL, TalkBack, 200% font scale, dark mode, tablet/foldable,
  rotation/process restoration, permissions and offline regression passes.
- Startup/scroll performance, large-history/paging/memory/battery benchmarks,
  baseline profiles and R8 release smoke tests.
- Dependency/security review, Android exported-component/deep-link audit,
  generated model shrinking tests, data deletion/retention policy.
- Real-account end-to-end acceptance, coverage closure and signing/release setup.

**Exit:** reproducible signed release candidate with tested feature coverage,
privacy documentation and a truthful known-limitations list.

## Immediate next gate

Run the Android workflow or open the project in an Android toolchain environment.
Resolve build/test/lint findings **before** multiplying unverified feature code.
Then complete stage 2's live acceptance gate and stage 3's message actions/paging;
proceed into stage 4 and release hardening without treating any partial stage as done.
