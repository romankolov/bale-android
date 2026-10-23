# Verification record

## Executed in this workspace

- `python tools/generate_protocol.py --check`: PASS.
- `.venv/bin/python tools/verify_protocol.py`: **10 tests PASS**.
  - Every recovered non-WKT message field's number/type/repetition.
  - Every RPC request/response type reference.
  - Handshake, direct RPC envelope, metadata and response/error layout.
  - Empty terminate-session presence and high-number update fields.
  - Int64 message IDs above 2^53, Persian/emoji text, unknown-field retention.
- `.venv/bin/protoc ... --java_out=lite:...`: PASS for both canonical model
  and transport schemas, using verification-tool protoc **30.2**.
- `tools/generate_coverage.py --check`: PASS; 699 endpoints / 9 source paths.
- `git diff --check`: PASS when last checked.

The Python fixture checks validate schema/wire compatibility against the recovered
reference layout; they do **not** execute the Kotlin network engine. The independent
Java code-generation check is not a Java compilation or Android build. The Android
build pins protoc 32.1 / protobuf-javalite 4.32.1 and still needs Gradle verification.

## Attempted but blocked

`./gradlew --version` fails before Gradle starts because there is no `java` or
`JAVA_HOME`. The environment also lacks Android SDK/Gradle installations, and
network access excludes Google repositories, Maven Central and Bale's endpoints.
No successful Gradle build, Kotlin test run, emulator run, live login or APK exists
from this workspace. No remote workflow was triggered and no private account was used.

## Authored, not executed

- JUnit phone normalization tests (international, Persian/Arabic digits, invalid input).
- gRPC-web framing/parser/error/size-bound tests.
- MockWebServer handshake/auth-header and out-of-order RPC correlation test.
- Empty terminate-envelope presence regression test.
- Auth ViewModel double-submit/challenge/reset/error-recovery tests.
- Compose phone → code UI smoke test with a fake repository (English device locale).
- Actions build/unit/lint/test-compilation workflow.

## Required next validation

1. Run `./gradlew :app:assembleDebug` in a normal Android environment. Fix all
   dependency/API/compiler issues before expanding feature breadth.
2. Run domain/network/auth unit tests and Android Lint. Generate/commit Room v1
   schema from KSP; add in-memory Room/account-boundary instrumented tests.
3. Run the Compose test on Android 10+; then add RTL, large text, dark mode and
   adaptive-layout tests. CI currently compiles instrumentation tests, not runs them.
4. Use an authorized test account/device to validate server errors, registration,
   two-step verification, refresh, 4401 handling, real messaging and ID deduplication.
5. Test process death, missed updates, empty history, missing peer hashes, concurrent
   sends, ambiguous timeouts, sign-out/write races and interrupted uploads when added.
6. Complete the roadmap's media/notifications/paging coverage and release tests.

Never put verification codes, JWTs, private message contents or signing credentials
into test fixtures, CI logs, repository issues or chat.
