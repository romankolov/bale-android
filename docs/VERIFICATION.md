# Verification

## Release candidate `1.0.0-rc4` — upload parity, caption staging, observable liveness

Third live-device round. What changed, with the official web bundle as the reference:

1. **Non-image uploads** — `GetNasimFileUploadUrl` requests now match the web client exactly:
   `uid` is always the signed-in account (the `exPeer` attributes the file to the chat) and
   `sendType` is sent only when the storage host must transcode (photo/video/voice/audio);
   documents and stickers omit it. Video picks probe width/height/duration with
   `MediaMetadataRetriever` because zero-filled `DocumentExVideo` extensions are rejected.
   Failed sends restore the staged file and caption for a retry without re-picking.
2. **Captions** — the picker stages the file (chip with name/size and remove action); the composer
   text becomes the caption and the send button dispatches it. Nothing uploads at pick time.
3. **Stickers** — receives with only a file location now auto-fetch the image into the media
   cache (bubbles render instead of a bare download chip). Sends keep echoing the collection
   entry's `ImageLocation` reference.
4. **Liveness, observable** — every socket-side update frame is counted (and logged under
   `BaleLive`); the repository poller runs two modes: while the server has never pushed a frame
   to this session it re-reads dialogs + the open chat every 4 s (guaranteeing "live" by polling
   alone), and once pushes flow it only refreshes after 30 s of silence. Opening a chat triggers
   an immediate history catch-up. Settings shows a live-diagnostics line (connection, frames
   received vs applied, poll rounds, last-push age), and failed operations now surface the server's
   own error reason instead of a generic notice.

## Release candidate `1.0.0-rc3` — live-device hardening rounds (rc2 + liveness/upload fix-up)

Ten issues from hands-on testing of the `1.0.0-rc1` APK were fixed at the data/network/UI layers:

1. **Avatars in chat list & chat screen** — `ensureAvatars` is now invoked from the dialog list,
   chat bubbles, story tray and sticker picker; `Users/LoadUsers` hydration is chunked (100 per
   page) and avatar records feed `peerAvatars` even when a dialog row only carried a bare id.
2. **Numeric ids instead of names** — `LoadDialogs` users/groups stubs with blank names are
   re-fetched (cache-guarded against refetch loops); `UserNameChanged` updates rename the Room row
   live; message senders from pushes are queued, debounced (600 ms) and hydrated in one batch.
3. **Non-image uploads failing** — fresh uploads no longer invent an access hash and documents no
   longer carry an empty `ext` message; uploads are always exactly one full-body streaming PUT
   (the storage host never assembles partial chunk PUTs — splitting on `chunkSize` is what made
   PDFs/videos fail in rc2 while images slipped through); HTTP 409 is tolerated, `duplicate` skips the PUT.
4. **Stickers** — `StickerMessage` sends ids + stored `format` **and echoes the collection entry's
   `ImageLocation` file reference** (`animation` for animated packs, `image256` for static) —
   id-only sends were rejected by the server; previews render from the shared file cache.
5. **Presence & typing** — new `PresenceApi` (`SetOnline`, `GetUsersPresence`, `SubscribeFromOnline`,
   `GetGroupOnlineCount`) feeding `peerPresence`/`groupOnlineCounts` state flows plus update
   events 7/8/9/33/34; the header falls back typing → online → last-seen → “N online”.
6. **Stories per user** — the tray groups by owner with resolved name/avatar, pre-warms owner and
   media thumbs through the same cache, and hydrates channel titles from `Groups/LoadGroups`.
7. **Voice codec** — Ogg/Opus 48 kHz 32 kbps via MediaCodec + in-repo Ogg muxer (6 JVM tests),
   automatic AAC fallback.
8. **Download progress** — `FilesApi.download` streams percent; bubbles and the viewer show a
   determinate indicator; the VM clears entries in `finally`.
9. **Chats vanishing after pinning** — the archived overlay is rebuilt only from
   `LoadDialogs(archiveFilter=1)` and rejected when it would hide the entire visible list; pin
   state replaces exactly from `LoadPinnedDialogs`/`PinnedDialogsChanged`; `ChatArchive`,
   `DialogArchiveStatus` and `ChatShow` keep it in sync per event.
10. **Not live until refresh** — socket messages bump `conversations` (date/preview/unread, seeding
    unknown peers) so the UI repaints the instant a push is applied. As a transport-agnostic floor,
    a repository-owned staleness poller re-reads dialogs + the open chat every ~10 s while no push
    arrives (2 s tick, 8 s threshold), and it goes idle by itself when pushes flow. Update fan-out
    is non-suspending (256-slot `DROP_OLDEST` shared flow, 512-deep frame channel) so a busy
    account gate can no longer backpressure the reader into tearing the connection — the failure
    that capped latency at ~1 minute in rc2.

## Release-candidate gates

Each push runs four gates, and a fifth pair runs on both emulators:

1. `protocol` (≈15 s): 12 wire-encoding tests, `generate_coverage.py --check` (**113** implemented
   RPCs of 699, per-service scope), `check_kotlin_consistency.py` (static cross-layer verification)
   and a Java-lite `protoc` generation check.
2. `android`: 90 JVM unit tests across five modules, `:app:assembleDebug`, strict `:app:lintDebug`
   (`warningsAsErrors = true`), `:app:assembleRelease` (R8 + resource shrinking + debug signing) and
   both androidTest APKs, plus the exported Room schema hash check (must stay
   `1c603993d1d1994bc9e00c0cae07c49d` for schema v3).
3. `device-tests (29)` and `device-tests (36)`: 24 instrumentation tests on hardware-accelerated
   emulators for Android 10 and Android 16.
4. Artifacts: `development-apk`, `release-candidate-apk`, `verification-reports` and per-API
   `device-test-reports-*`.
 record

## Successful GitHub Actions run

[Run 39882404720](https://github.com/romankolov/bale-android/actions/runs/39882404720)
verified source commit **`57ea67247c1bf2ce133e024171a7053ef43c286d`**.
All four jobs passed. Documentation/coverage updates reference this concrete
source snapshot rather than assuming that any later commit has already passed.

| Gate | Result |
|---|---|
| Protocol/model checks | 10 tests pass; deterministic schema/coverage checks pass |
| Java-lite generation | Pass in standalone verification and the Android Gradle build |
| Debug app + instrumentation APK build | Pass |
| Android correctness lint | Pass under the policy below |
| Domain unit tests | 5 pass, 0 skipped |
| Network/MockWebServer unit tests | 15 pass, 0 skipped |
| Auth ViewModel unit tests | 3 pass, 0 skipped |
| Android 10 / API 29 emulator | 2 app/Compose tests + 4 Room tests pass, 0 skipped |
| Android 16 / API 36 emulator | 2 app/Compose tests + 4 Room tests pass, 0 skipped |

The real application launch test exercises the manifest, Hilt composition,
initial session/cache restoration and login UI. The Compose flow uses a fake
repository; **it does not log in to Bale**. Room tests cover account-scoped
queries, stable-rid acknowledgement reconciliation, cross-account separation,
interrupted-outbox recovery and clearing cached rows. They do not prove absence
of all session/write races or validate a future migration.

Runner/toolchain: Ubuntu 24.04, JDK 17, Gradle 8.13, AGP 8.13.0, Android SDK 36,
Build Tools 35.0.0, protobuf compiler 32.1 / Java-lite 4.32.1. Device jobs use
hardware-accelerated x86_64 Google APIs emulators with animations disabled and
English locale. This does not cover physical devices or API 37.

## Downloadable build and reports

- [Development APK ZIP](https://github.com/romankolov/bale-android/actions/runs/39882404720/artifacts/12471416312)
- [Build/unit/lint reports](https://github.com/romankolov/bale-android/actions/runs/39882404720)
- Device reports are separate `device-test-reports-api-29` / `...-36` artifacts.

Extract and install `app-debug.apk`. Artifact access may require GitHub sign-in;
retention limits apply. Debug signing is temporary, not production signing. A
new runner can use a different debug key; uninstalling an incompatible old build
also deletes its local data. Release/R8/signing validation is still outstanding.

## CI and lint policy

- SDK 36 is an intentional baseline, not a claim of using the newest SDK.
- `OldTargetApi`, `GradleDependency` and `AndroidGradlePluginVersion` advisories
  are excluded from the app lint gate. SDK/AGP/Compose upgrades require deliberate,
  coordinated validation and are tracked through the roadmap/Dependabot.
- All other enabled correctness lint checks remain fatal, including warnings.
  No blanket lint baseline or broad warning suppression was added.
- CI corrected a Kotlin coroutine return-type error and genuine launcher-resource
  issues (obsolete qualifier and missing monochrome icon), rather than suppressing them.
- Commands preserve failure exit codes. API-visible annotations include compiler,
  lint and JUnit failures plus actual report-derived counts; full logs/reports
  remain artifacts. Independent tasks continue after a failure to expose more issues.

## Local verification and environment boundary

This sandbox still lacks Java/Android SDK and cannot reach Maven/Google/Bale
hosts. **GitHub Actions is the verified build/run environment**, not the sandbox.
Locally executed checks include:

- `python tools/generate_protocol.py --check`: pass.
- `.venv/bin/python tools/verify_protocol.py`: 10 tests pass (field types,
  RPC references, envelopes, metadata, int64 IDs, Unicode and unknown fields).
- `.venv/bin/protoc ... --java_out=lite:...`: pass using standalone protoc 30.2.
- `python tools/generate_coverage.py --check`: pass; 699 RPCs / 9 source paths.
- Python tool compilation and staged whitespace checks: pass.

Python fixture checks validate the recovered layout; Kotlin's network engine is
covered separately by the passing JVM tests. Generated models do not establish
server availability, feature completeness or authorization.

## Remaining validation

1. Review/commit the actual KSP-exported Room v1 schema from the report artifact,
   then add migration tests before changing the schema.
2. Use authorized test accounts/devices to validate real login, registration,
   two-step verification, token refresh/expiry, 4401 handling, messaging and rid
   deduplication. No live account, private credentials or real chats were used in CI.
3. Add RTL, large-text, dark-mode, adaptive-layout, accessibility and physical-device
   tests. The current English-locale smoke suite is intentionally small.
4. Test process death, missed updates, missing peer hashes, concurrent sends,
   ambiguous timeouts, logout/write races and interrupted uploads when implemented.
5. Complete the roadmap's media, message actions, notifications, paging and release
   performance/security/R8/signing gates. Calls and financial features remain deferred.

Never put verification codes, JWTs, private messages or signing credentials into
fixtures, CI logs, issues or chat.
