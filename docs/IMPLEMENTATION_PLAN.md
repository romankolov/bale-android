# Remaining messenger implementation — 2026-10-23

The user independently tested the initial client against a real account and
reported that it works. This is useful baseline acceptance, **not** automated
live coverage of all RPCs, token-refresh paths, or the new features below.
Calls and financial services remain deferred; Android API 29 remains the minimum.

This plan subdivides the remaining work inside the original five-stage
[roadmap](ROADMAP.md). It does not reset the completed foundation work.

## 1. Messaging completeness — implementation in progress

Implemented in this batch:
- Typed, injectable messaging API; session-bound requests and stamped updates
  prevent a queued old-account request/update crossing into a newer login.
- Reply context persisted before send; explicit retry preserves the stable RID
  and quote. Text editing, confirmed deletion (local/everyone), forwarding to an
  existing conversation, and reaction add/remove paths with Compose controls.
- Targeted reducers for content changes, deletion, delivery/read markers,
  cross-device unread counts, reactions and expiring typing indicators.
- Paging 3 over Room: newest-first query, deterministic date/RID ordering,
  50-row pages, 300-row retained-page budget, explicit older server pages.
- More-dialog loading and missing user/group name/hash resolution.
- Server message-search previews, global or scoped to the selected chat;
  opening a result opens that conversation's latest messages.
- Schema v1 → v2 metadata migration and v2 → v3 durable deletion tombstones;
  existing cache/outbox rows survive. No destructive migration fallback.
- Explicit protocol corrections with provenance in `reference/protocol/overrides.json`.

Gates still open:
- Live-account acceptance of **each new operation**, including permissions,
  date units, reply references, forward destination IDs and reaction responses.
- Durable sequence/state catch-up, missed/out-of-order update recovery and
  authoritative snapshot reconciliation; this is not complete synchronization.
- Search continuation tokens and jump-to-result context, own-reaction state,
  full reaction picker, pins/archive/mute, and complete dialog pagination policy.
- WorkManager outbox policy only after live idempotency/reconciliation evidence.

## 2. Media and file pipeline — next substantial implementation batch

- Audit file URL/upload lifecycle, origin allowlist, redirect behavior and limits.
- Stream SAF/Photo Picker content, not whole files in memory; cancellation,
  progress, temporary-file cleanup and URI grant handling.
- Attachment metadata and safe open/share/download controls; thumbnails/avatars
  using an image pipeline and bounded disk cache.
- Media3 audio/video playback, microphone permission/voice-note recording and
  explicit recording lifecycle. Test unsupported/oversize/failed uploads.

## 3. People, profiles and communities

- Contacts and new-chat discovery; consent before importing device contacts.
- User/group/channel details, members with cursors, avatars, profile settings.
- Role/permission-aware create/join/invite/leave/admin/moderation operations;
  server-enforced authorization and destructive-action confirmations.
- Block/report and invitation/deep-link trust boundaries.

## 4. Everyday messenger features

- Stories, polls, bot keyboards/interactions and remaining messenger RPC triage.
- Privacy/session/settings screens with remote revocation where verified.
- Android notification channels/permission and a viable authenticated push
  registration/delivery mechanism. Do not assume access to Bale's Firebase
  project or promise an always-on invisible socket/background service.
- Eligible WorkManager work, process-death behavior and account-safe cancellation.

## 5. Release hardening and acceptance

- Broader migration/device/race/offline and live two-account regression matrix.
- Persian/English RTL, TalkBack, font scaling, tablet/foldable and process restore.
- Large-history performance, memory/disk limits, battery and release/R8 tests.
- Threat model, message retention/deletion, dependency review and component audit.
- Stable signing/release setup; runner-generated debug signing keys are not a
  stable update channel. Never commit private signing material.

## Verification policy

Every batch must compile and pass correctness lint plus relevant JVM and API
29/36 instrumentation tests before its APK is advertised as verified. Fake RPC
fixtures check client logic, **not** Bale backend acceptance. Coverage tracks
source paths separately from build and live verification. New batches do not
inherit baseline live-test claims.
