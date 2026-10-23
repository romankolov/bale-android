# Capability coverage

The initial real-account login, dialog sync, and basic messaging baseline was verified on a live account by the user. All expanded messenger capabilities below compile cleanly and pass automated protocol, JVM, lint, and API 29 / API 36 instrumentation suites in CI; newly added RPCs remain ready for live-account acceptance testing.

| Capability | Status | Notes / Remaining scope |
|---|---|---|
| Canonical protobuf schema (2,006 messages / 699 RPCs) | Implemented & tested | 12 provenance-documented wire overrides in `reference/protocol/overrides.json` |
| Phone / code / 2FA password / registration | User live-verified baseline | Encrypted Keystore `AES-GCM` session storage |
| Connected JWT refresh & session-epoch guards | Implemented & tested | Prevents stale cross-account RPCs or buffered socket updates |
| Active sessions viewer & remote termination | Implemented & tested | `GetAuthSessions`, `TerminateSession`, `TerminateAllSessions`, `SignOut` |
| Conversations, Paging 3 history & pagination | Implemented & tested | 50-row pages, 300-row window, missing user/group resolution |
| Outbox, retry & non-destructive Room migrations | Implemented & tested | Schema `v1 -> v2 -> v3`; KSP schema `3.json` committed; persistent delete tombstones |
| Replies, edits, deletes, forwards & pins | Implemented & tested | Packed `int64` delete dates; local vs everyone delete; message & dialog pins |
| Emoji reactions | Implemented & tested | Multi-emoji reaction state + 1-tap add/remove highlighting user's own reactions |
| Server message search | Implemented & tested | Global or current-conversation conjunctive `Query2` search |
| Typing, online status & delivery/read receipts | Implemented & tested | Coalesced monotonic `MessageRead`; throttled outgoing typing |
| Media upload, chunked PUT & bounded download | Implemented & tested | Photo Picker, SAF files, inline photo/thumb bitmaps, HTTPS enforcement, `FileProvider` |
| Voice notes & audio playback | Implemented & tested | Runtime `RECORD_AUDIO` permission, `.m4a` AAC recorder, inline `MediaPlayer` controls |
| Interactive polls | Implemented & tested | `CreatePoll`, `Vote` / retract, `GetPollResults`, `ClosePoll` |
| Bot keyboards & template messages | Implemented & tested | Inline callback / `https://` URL / copy & reply keyboard buttons |
| Contacts, profiles & user blocking | Implemented & tested | `GetContacts`, `SearchContacts`, `AddContact`, `RemoveContact`, `BlockUser`, profile & avatar edit |
| Groups, channels, invites & moderation | Implemented & tested | Create group/channel, join by `ble.ir/join/...` or `@handle`, members, admin roles, kick, leave |
| Stories | Implemented & tested | Active story feed, text story publishing, emoji reactions & story deletion |
| Local notifications | Implemented & tested | Private lockscreen notification channel + Android 13+ `POST_NOTIFICATIONS` permission flow |
| Adaptive Material 3 Expressive UI & EN/FA RTL | Implemented & tested | 131 verified English + Persian string pairs |
| Voice/video calls, banking/wallets & FCM push | Explicitly deferred | Outside messenger-first scope or requires Bale server push credentials |

Machine-readable RPC tracking lives in [docs/rpc-coverage.csv](rpc-coverage.csv) (**67 source-implemented RPCs** across 10 services out of 699 recovered endpoints).
