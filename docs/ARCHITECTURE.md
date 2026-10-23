# Architecture and implementation decisions

## Dependency rule

```text
app ──> feature/auth, feature/messaging, core/designsystem
  └──> core/data ──> core/network ──> core/protocol
               └──> core/domain <── features
```

Domain is JVM-only and Android/protobuf/Retrofit/Room-free. Repository contracts
expose domain objects and Flow/StateFlow. ViewModels convert user intent to state
and invoke contracts; Compose collects with lifecycle awareness. Hilt binds the
application data runtime to interfaces. HTTP/WebSocket/storage implementation
types are not exposed in the application DI signatures.

Repositories are sufficient for the current single-repository operations. Add
use cases for actual business orchestration rather than wrapping every method
for the sake of a layer. There is no Python/WebView backend in the Android app.

## Wire model generation

The split archive protos fail type resolution. Generate one namespace from the
supplied deduplicated runtime schema, maintaining field numbers, primitive types
and repeated flags. Keep original placeholder names/opaque bytes; do not infer
missing semantics. The original ZIP and runtime JSON provide provenance.
Generated Java-lite code is a build output, not committed source. Full Java-lite
generation was validated independently of Android; the app's pinned compiler still
needs to run in Gradle.

Service routing is custom. The reference encoder puts RpcRequest directly in
SocketRequest.field1. Metadata string values are nested at field 2 → field 1;
response request IDs are field 3. Byte-level tests lock this down. A native gRPC
client or Retrofit JSON converter is not a substitute for these envelopes.

## Network ownership

One application-scoped socket belongs to one saved account. The read loop routes
responses by a monotonic int64 request ID; pending calls have timeouts and removal
on cancellation. An independent heartbeat enforces pong deadlines. Reconnect
uses capped exponential backoff with jitter; invalid sessions stop reconnecting.
Incoming frames and HTTP bodies are bounded. Empty termination messages retain
field presence. Disconnect fails pending calls and does not replay them.

Expected RPC errors are typed, while UI errors are intentionally sanitized.
No request-body/header logging is installed. Standard platform certificate trust
remains enabled. Host/build values mirror the supplied web reference, not an
unverified guess at Android app credentials.

Current socket lifetime is process scoped. Android may kill/suspend it in the
background; it is **not** a notification strategy. Future push/WorkManager work
must respect Android restrictions and not promise persistent hidden connectivity.

## Persistence and account isolation

Room is the UI source of truth. Rows are keyed by account plus peer/message ID.
The session gate serializes account transitions with inserts so a late network
response cannot refill a logged-out account cache. Reads follow account state;
logout clears session and cached rows. Keystore keys remain installed on logout
and may encrypt a later session; the encrypted session value is removed.

The outbox is represented by message rows and an explicit Pending/Sent/Failed
state. A cryptographically random positive int64 rid is saved before transmission.
Explicit retry reuses that rid. Reconnection alone does not resend a request with
an unknown server outcome. Incoming acknowledgements/history reconcile by rid.
Pending rows surviving process death become retryable failures.

Current limitations: no disk/paging policy, no migration beyond schema 1, no
complete incremental update state, no full snapshot deletion reconciliation and
no worker-based outbox. These are release blockers, not hidden implementation details.

## UI/design system

Expressive theming is isolated behind `BaleTheme`, with green tonal surfaces,
large rounded chat/list containers, system dark mode and adaptive list/detail
panes. Persian/English resources and Android's bidi support are used instead of
manual text reshaping. Interactive controls have standard Material semantics and
minimum touch targets; actual TalkBack/font-scale/contrast/device QA remains due.

OTP/password fields are memory-only and cleared on submission. Draft text is
cleared only after a Room enqueue succeeds. The selected peer is restored from
primitive SavedStateHandle fields. Stored peer hashes/names can still require
server resolution before a restored conversation is fully usable.

## Security boundaries

Tokens and account metadata: AES-GCM with Keystore key, fresh per-write nonce,
alias-bound authenticated data, private preferences. Corrupt/inaccessible state
requires reauthentication, never plaintext fallback. Database: app-private but
**not** independently encrypted. Neither this architecture nor recovered encrypted
message fields constitute an end-to-end encryption implementation.

Backup/device-transfer exclusions, disabled cleartext and FLAG_SECURE provide a
baseline. This is not a completed threat assessment. Before release, validate
logout races, crypto key invalidation, database remnants/WAL behavior, exported
components, permissions, file URL trust, dependency updates and R8 shrinking.
