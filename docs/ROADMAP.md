# Five-stage implementation roadmap

Scope agreed: full **messenger** first, Android 10+; proceed incrementally through
all five stages. Calls and financial services remain explicitly deferred.
Stages are acceptance gates, not promises that schema generation delivers parity.
The user has reported a working initial real-account flow. The next implementation
work is subdivided into [five remaining batches](IMPLEMENTATION_PLAN.md).

## 1 — Foundation, protocol audit and design system

**Source work delivered**
- Eight modules, Gradle wrapper/catalog, shared Android-library conventions.
- Compose/MVVM with domain repository contracts and Hilt composition.
- Deterministic canonical schema; transport-envelope definitions; inventory of
  all 699 RPCs, field/type verification and Java-lite code generation checks.
- Material 3 Expressive theme, dark/light colors, English/Persian, RTL support,
  adaptive list-detail UI and baseline accessibility semantics.

**Gate outstanding**
- Gradle debug build, Android Lint and startup tests now pass in CI; complete
  design/accessibility acceptance remains outstanding.
- Device/RTL/font-scaling/screen-reader review; design previews/screenshots.
- Commit exported Room schema baselines and validate migration fixtures.

**Exit:** reproducibly buildable shell and reviewed capability inventory.

## 2 — Authentication and transport

**Source work delivered**
- gRPC-web framing/error parsing and bounded response reads over Retrofit/OkHttp.
- Phone → code → password/registration flows with explicit unsupported-mode errors.
- Stable device UUID; Keystore AES-GCM session vault and local logout.
- Initial real-account flow independently confirmed by the user (not a complete
  live regression matrix).
- WebSocket handshake, metadata, request correlation, cancellation/timeouts,
  heartbeat/pong deadlines, reconnect with jitter, unauthorized termination.
- Token refresh scheduling while the process is connected.
- JUnit/MockWebServer transport tests and auth ViewModel tests pass in CI.

**Gate outstanding**
- Kotlin unit tests pass; verify real login/session-refresh/reconnect scenarios.
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
- Messaging batch: replies, edits/deletes/forwarding/reactions, server search
  previews, read/typing reducers/UI, Room Paging 3, more-dialog loading, typed
  session-bound RPCs, cache migrations and persistent deletion tombstones.
- Draft cleared only after successful local enqueue; interrupted attempts recover
  as retryable failures. No automatic replay of ambiguous RPC outcomes.

**Gate outstanding**
- Basic Room/account-isolation/reconciliation tests pass on API 29 and 36.
  Migration baseline, full reconnect catch-up, update ordering/deduplication,
  deletion reconciliation and missed-update recovery remain outstanding.
- Finish server cursor/snapshot policies and test large histories/dialog lists.
  Paging 3 uses explicit server-page loading, not a claimed RemoteMediator.
- Live verification of the new messaging paths; search continuation/jump, full
  reaction state, archive/pin/mute, complete presence and new-chat flows.
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
- Protocol checks, JVM/MockWebServer/auth tests and Compose/Room smoke tests pass.
- CI runs generation, debug APK build, unit tests, correctness lint and device
  tests on API 29 and 36, with downloadable APK/reports.
- Sensitive-data storage/logging/backup baseline and explicit coverage tracking.

**Gate outstanding**
- Basic CI and emulator gates are green. Expand Android instrumentation/Room
  coverage, add migration/race tests and verify physical-device behavior.
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

The initial build/unit/lint/emulator gate is now green; see the recorded
[verification evidence](VERIFICATION.md). Validate stage 2 against authorized real
accounts, complete stage 3's synchronization/message actions/paging, and continue
through media and release hardening. A successful build is not full feature parity.
