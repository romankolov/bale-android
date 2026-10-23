# Messenger implementation batches — completed source delivery

Following user confirmation that the initial login/messaging build worked against a real Bale account, all five messenger implementation batches have been delivered on branch `arena/d6902ab6-bale-android`:

1. **Batch 1 — Rich messaging & synchronization**
   - Replies with quote preview, edits (`UpdateMessage`), local/everyone deletes (`DeleteMessage` with packed `int64` dates and durable `message_tombstones`), forwards (`ForwardMessages`), multi-emoji toggleable reactions (`Abacus`), message & dialog pins/archive/clear/delete, global/chat `SearchMessages`, typing/online presence, read/delivery ticks, session-epoch isolation, and Room `Paging 3` + `v1 -> v2 -> v3` migrations.
2. **Batch 2 — Media, attachments & voice notes**
   - `ai.bale.server.Files` upload/download client (`GetNasimFileUploadUrl` + one streaming `multipart/form-data` `PUT` per file with `sendType`, CRC32 and `DocumentMessage.accessHash`; `GetNasimFileUrl` bounded HTTPS streaming), zero-permission Android Photo Picker & SAF document picker, downsampled inline image/thumbnail previews, scoped `FileProvider` viewer, `MediaRecorder` `.m4a` voice notes, and inline `MediaPlayer` audio playback.
3. **Batch 3 — Contacts, profiles, groups & channels**
   - Contacts tab (`GetContacts`, `SearchContacts`, `AddContact`, `RemoveContact`), user profile/bio/username editor & avatar upload, user block/unblock, community creation (groups & broadcast channels), `ble.ir/join/...` & `@handle` joining, full peer/group inspector sheet, invite link copy/reset, member directory, admin promotion/demotion, kick, and leave.
4. **Batch 4 — Polls, bot keyboards, stories, sessions & notifications**
   - Poll creation, live voting/retraction & closing (`bale.poll.v1.Poll`); bot `TemplateMessage` inline/reply button rows (`TemplateMessageResponse`); Stories tab (`GetAllStories`, `AddStory`, `ReactToStory`, `RemoveStory`); Active Devices management (`GetAuthSessions`, `TerminateSession`, `TerminateAllSessions`, bounded `SignOut`); and private-visibility foreground Android notifications.
5. **Batch 5 — Schema baseline, lint & cross-version emulator regression**
   - Committed KSP Room schema (`core/data/schemas/ai.bale.client.data.BaleDatabase/3.json`), `CommandLineArgumentProvider` Gradle cache wiring, strict `warningsAsErrors = true` lint compliance, 262 verified EN/FA string resources, 12 protocol tests, 75 JVM tests, and 24 instrumentation tests across Android 10 (API 29) and Android 16 (API 36).

6. **Release candidate `1.0.0-rc1` — feature breadth**
   - Chat folders and filter tabs, per-account dialog state (draft, pin, mute, unread, mention counters) stored outside Room so schema v3 stays untouched.
   - Full-screen media viewer with photo/video/document rendering, voice transcription and GIF saving; sticker pack and saved-GIF pickers wired to `bale.v1.Images`.
   - Scheduled sending through `bale.schedule.v1.Scheduler`, threads/channel comments through `LoadReplies` + `threadId`, shared media gallery through `SharedMediaService`.
   - Bot menu buttons, Mini-App `WebView` host with a `BaleAndroid` JS bridge and `Telegram.WebApp` shim, inline bot results, link previews with optional AI summary, message/chat reporting, group avatar and public-link editing with availability check, banned-member and admin listings.
   - Real avatar loading and caching across every surface; two-step verification, privacy display and blocked-user management in Settings; theme mode, dynamic colour and in-app EN/FA language switch with RTL flip.
   - `RichFeaturesApi` isolates the new RPCs; `tools/check_kotlin_consistency.py` statically verifies cross-layer call names, arity, overrides, data-class named arguments and delimiter balance in the fast `protocol` job; CI now also builds and publishes `:app:assembleRelease` (R8 + resource shrinking, debug-signed for install-over-testing) next to the debug APK.

## Remaining post-merge / live-acceptance items
- Live two-account QA across the newly wired release-candidate endpoints (single-`PUT` media sizes up to 50 MB, folder membership, stickers/GIFs, scheduled delivery, threads, shared media gallery, Mini-App answers, reports, 2FA toggles).
- Background push wakeups require third-party push credentials or unified push relay outside web-client JWT scope.
- Calls (`bale.call.*`) and financial/wallet services remain intentionally deferred.
