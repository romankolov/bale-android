# Capability coverage

Debug compilation, correctness lint, unit tests and English-locale smoke tests
on API 29/36 now pass in CI. This is **not** live-server or full-feature verification;
see [the evidence and remaining validation](VERIFICATION.md).

| Capability | Source status | Remaining verification/work |
|---|---|---|
| Canonical protobuf models | Gradle generation/build pass | Live protocol compatibility |
| Login HTTP framing/errors | Implemented | Auth transport tests, live server |
| Phone/code/2FA/registration | Implemented initial paths | Real flow, resend/cooldowns/recovery |
| Secure session restore/refresh | Implemented | Keystore/device/token-expiry tests |
| Local logout | Implemented | Race/device data-deletion tests |
| Remote logout/session control | Not implemented | Server session revocation |
| WebSocket RPC/reconnect/heartbeat | Implemented; MockWebServer tests pass | Adverse networks, timeouts and live server |
| Conversation list/history/text send | Implemented initial paths | Real account validation and full pagination |
| Local cache/outbox/stable-ID retry | Implemented; basic Room tests pass on API 29/36 | Crash/race tests, migration baseline, server dedup guarantees |
| Incoming text and send acknowledgement | Initial reducers | Full update coverage, ordering and catch-up |
| Mark read | RPC path implemented | Visible-state lifecycle tests and server semantics |
| Read/delivery ticks, presence/typing | Not implemented | Protocol reducers + UI |
| Local title search | Implemented | UI tests |
| Global/in-chat server search | Not implemented | Search service + paging |
| Reply/edit/delete/forward/reactions | Not implemented | RPCs, reducers and UI |
| Attachments/photos/video/voice/files | Not implemented | Streaming, storage, media UI and permissions |
| Profiles/contacts/new chats | Not implemented | Resolution, discovery and permission behavior |
| Groups/channels/admin/invites | Not implemented | Role-aware operations and UI |
| Polls/stories/bot interactions | Not implemented | Feature-specific flows |
| Notifications/background delivery | Not implemented | Verified push strategy and Android restrictions |
| Adaptive layout, English/Persian/RTL | Source provided | Accessibility/device/large-font QA |
| Calls/financial features | Deferred by scope | Separate protocol/authorization/media investigation |

The machine-readable [RPC inventory](rpc-coverage.csv) records all **699**
recovered endpoints. Nine have source implementations. Untriaged services are
not silently excluded: the full messenger definition still needs service-by-service
review (for example stickers and scheduler behavior may cross service boundaries).
The runtime inventory remains the source of truth for routing names.

Not yet supported: end-to-end encryption, multi-account switching, unattended
background outbox, complete incremental synchronization, encrypted message database,
or production release distribution. Unsupported non-text message bodies are shown
as an explicit placeholder, not as if the media had been loaded.
