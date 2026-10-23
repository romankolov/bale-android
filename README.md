# Bale Android

An independent, native Android Bale client under development. **Android 10+ (API 29).**

> **Status: build-verified development client, not feature-complete or release-ready.**
> [GitHub Actions verification passed](https://github.com/romankolov/bale-android/actions/runs/39882404720)
> at source commit `57ea672`: debug APK, Android Lint, 23 unit tests, 10 protocol
> checks, and six instrumentation tests on each of Android 10 and Android 16.
> **Live Bale login/messaging and production readiness remain unverified.**

[Download the development APK artifact](https://github.com/romankolov/bale-android/actions/runs/39882404720/artifacts/12471416312)
(unzip `app-debug.apk`; GitHub sign-in may be required). Fresh-runner debug signing
keys may differ between builds; a signature mismatch requires uninstalling the
previous development build, which deletes its local data. Do not treat this as a
production release. Artifacts are subject to GitHub retention limits.

## Agreed five-stage delivery

1. Foundation, protocol audit and Material 3 Expressive design system.
2. Secure authentication and reliable HTTP/WebSocket transport.
3. Offline-first messaging.
4. Media and full messenger capabilities.
5. Hardening, device testing and release.

See [the roadmap and acceptance gates](docs/ROADMAP.md),
[current feature coverage](docs/CAPABILITIES.md), and the
[699-RPC inventory](docs/rpc-coverage.csv). Calls and financial services are
**deferred**, per the messenger-first scope decision. Generated models are not
counted as working features.

## Stack

- Kotlin, Jetpack Compose, Material 3 **Expressive**, MVVM/unidirectional state flow.
- Clean Architecture boundaries, Hilt, Coroutines/Flow, Room/KSP.
- Google protobuf Gradle plugin and generated **Java-lite** models.
- Retrofit for the HTTP boundary, explicit gRPC-web framing, OkHttp WebSockets.
- Gradle Kotlin DSL, version catalog, shared Android-library convention plugin.
- JUnit, MockWebServer, Compose UI tests, Android Lint, GitHub Actions.

Material 3 is explicitly pinned to **1.5.0-alpha01** because the 1.4 stable API
surface does not contain `MaterialExpressiveTheme`. The opt-in is isolated in the
design-system module. This is a deliberate prerelease dependency; verify/update
the entire Compose/toolchain set together before release. Dependencies are pinned
baselines, not a claim that they are the newest available versions.

## Open/build

Use Android Studio with **JDK 17**, Android SDK **36** and Build Tools **35.0.0**.
The wrapper pins Gradle **8.13** and AGP **8.13.0**. Normal Android/Maven repository
access is required.

```sh
# Set ANDROID_HOME or add sdk.dir=/path/to/Android/Sdk to ignored local.properties.
./gradlew :app:assembleDebug
./gradlew :core:domain:test :core:network:test :feature:auth:testDebugUnitTest
./gradlew :app:lintDebug :app:assembleDebugAndroidTest
# With an Android 10+ emulator/device (English locale for current UI smoke test):
./gradlew :app:connectedDebugAndroidTest
```

Debug APK output (after a successful build):
`app/build/outputs/apk/debug/app-debug.apk`.
Release signing is intentionally **not** configured; never commit signing keys.
The Actions workflow installs/uses JDK 17 and SDK 36 on pinned Ubuntu 24.04
runners, builds/tests/lints, uploads reports and a **development** APK, and runs
app-launch/Compose/Room tests on API 29 and 36 emulators with KVM acceleration.
This path is verified; this local sandbox still cannot fetch the Android toolchain.
Dependency/SDK upgrade advisories are deliberately separate from correctness lint
(see [the verification policy](docs/VERIFICATION.md)).

## Verify the protocol without Android

```sh
python3 -m venv .venv
.venv/bin/pip install -r tools/requirements.txt
.venv/bin/python tools/verify_protocol.py
.venv/bin/python tools/generate_coverage.py --check
mkdir -p .venv/java
.venv/bin/protoc -Icore/protocol/src/main/proto \
  --java_out=lite:.venv/java core/protocol/src/main/proto/*.proto
```

The verification-only Python tooling is not part of the app. Do not commit venvs,
generated Java files, credentials, phone numbers, session exports or private chats.

## Source layout

```text
app                    Application/activity and Hilt composition root
core/domain            Pure Kotlin entities, repository contracts, phone normalization
core/protocol          Recovered .proto models and documented transport envelopes
core/network           gRPC-web codec, Retrofit boundary, WebSocket RPC engine
core/data              Keystore session vault, Room, repository implementations
core/designsystem      Expressive theme, shared components, English/Persian resources
feature/auth           Login state machine, ViewModel, Compose screen
feature/messaging      Conversation list, adaptive chat pane, ViewModel
reference/protocol     Supplied protocol docs and deduplicated runtime schema
```

Feature modules depend on domain contracts, never network/database implementations.
Wire DTOs are mapped in data. There are no ceremonial pass-through use-case classes;
introduce focused domain use cases as cross-repository business rules emerge.

## Protocol provenance and limitations

`bale-start.zip` is preserved untouched. Its split proto files **do not compile**
because cross-package types are unresolved, and their service declarations do not
preserve full service names. `tools/generate_protocol.py` deterministically emits a
single valid schema from the archive's deduplicated `baleclient/schema.json`.
It preserves recovered field numbers, scalar/message types and repetition.

The runtime inventory has **2,006 message types, 53 services and 699 RPCs**.
Some extracted fields are opaque `bytes`, some names are recovered placeholders,
and enum semantics are not fully recovered. These limitations are not silently
"fixed" by guessing. Services use string routing from the inventory, **not native
gRPC stubs**. Only nine RPC paths currently have source implementations.

The executable reference transport places `RequestBody` directly in WebSocket
field 1, whereas an illustrative documentation snippet adds another wrapper.
The app follows the reference encoder; byte-level tests lock down that choice.
No production account/network verification has been performed. Host and
protocol build metadata reflect the supplied archive, not a discovery of today's
server requirements. Bale may restrict access by network/location.

## Security/behavior notes

- Token and account metadata encrypted using AES-GCM with Android Keystore;
  no plaintext fallback, HTTP logging interceptor or embedded user credentials.
- OS backup/device transfer excluded; cleartext traffic disabled; screenshots
  protected with `FLAG_SECURE`.
- Room data uses app-private **unencrypted** storage and device filesystem
  protection. Do not advertise database-level encryption or E2EE.
- Account-scoped rows and a session/write gate prevent logout from racing late
  database inserts. Logout is **local**, not remote session revocation.
- Stable random message IDs persisted before sending. Retries reuse IDs; a
  timeout is an **unknown outcome**, never proof the server did not send it.
- Interrupted pending messages become retryable failures on restore. There is
  no unattended outbox worker yet and no always-on background socket guarantee.
- No login codes/passwords are saved to disk or SavedStateHandle. Verification
  follows only the documented authenticated flow; never share credentials in issues.

Read [architecture decisions](docs/ARCHITECTURE.md) and
[verification results](docs/VERIFICATION.md) before extending the client.
