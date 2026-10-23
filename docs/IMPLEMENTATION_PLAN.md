# Messenger implementation batches — completed source delivery

Following user confirmation that the initial login/messaging build worked against a real Bale account, all five messenger implementation batches have been delivered on branch `arena/d6902ab6-bale-android`:

1. **Batch 1 — Rich messaging & synchronization**
   - Replies with quote preview, edits (`UpdateMessage`), local/everyone deletes (`DeleteMessage` with packed `int64` dates and durable `message_tombstones`), forwards (`ForwardMessages`), multi-emoji toggleable reactions (`Abacus`), message & dialog pins/archive/clear/delete, global/chat `SearchMessages`, typing/online presence, read/delivery ticks, session-epoch isolation, and Room `Paging 3` + `v1 -> v2 -> v3` migrations.
2. **Batch 2 — Media, attachments & voice notes**
   - `ai.bale.server.Files` upload/download client (`GetNasimFileUploadUrl` single & chunked `multipart/form-data` `PUT`, `GetNasimFileUrl` bounded HTTPS streaming), zero-permission Android Photo Picker & SAF document picker, downsampled inline image/thumbnail previews, scoped `FileProvider` viewer, `MediaRecorder` `.m4a` voice notes, and inline `MediaPlayer` audio playback.
3. **Batch 3 — Contacts, profiles, groups & channels**
   - Contacts tab (`GetContacts`, `SearchContacts`, `AddContact`, `RemoveContact`), user profile/bio/username editor & avatar upload, user block/unblock, community creation (groups & broadcast channels), `ble.ir/join/...` & `@handle` joining, full peer/group inspector sheet, invite link copy/reset, member directory, admin promotion/demotion, kick, and leave.
4. **Batch 4 — Polls, bot keyboards, stories, sessions & notifications**
   - Poll creation, live voting/retraction & closing (`bale.poll.v1.Poll`); bot `TemplateMessage` inline/reply button rows (`TemplateMessageResponse`); Stories tab (`GetAllStories`, `AddStory`, `ReactToStory`, `RemoveStory`); Active Devices management (`GetAuthSessions`, `TerminateSession`, `TerminateAllSessions`, bounded `SignOut`); and private-visibility foreground Android notifications.
5. **Batch 5 — Schema baseline, lint & cross-version emulator regression**
   - Committed KSP Room schema (`core/data/schemas/ai.bale.client.data.BaleDatabase/3.json`), `CommandLineArgumentProvider` Gradle cache wiring, strict `warningsAsErrors = true` lint compliance, 131 verified EN/FA string resources, 12 protocol tests, 47 JVM tests, and 21 instrumentation tests across Android 10 (API 29) and Android 16 (API 36).

## Remaining post-merge / live-acceptance items
- Live two-account QA across newly wired endpoints (chunked media sizes, group admin permissions, polls, stories, session revocation).
- Background push wakeups require third-party push credentials or unified push relay outside web-client JWT scope.
- Calls (`bale.call.*`) and financial/wallet services remain intentionally deferred.
