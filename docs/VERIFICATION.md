# Verification

## Release-candidate gates

Each push runs four gates, and a fifth pair runs on both emulators:

1. `protocol` (≈15 s): 12 wire-encoding tests, `generate_coverage.py --check` (**113** implemented
   RPCs of 699, per-service scope), `check_kotlin_consistency.py` (static cross-layer verification)
   and a Java-lite `protoc` generation check.
2. `android`: 75 JVM unit tests across five modules, `:app:assembleDebug`, strict `:app:lintDebug`
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
