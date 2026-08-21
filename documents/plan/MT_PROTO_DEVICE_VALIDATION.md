# Selected MTProto Device Validation

This protocol is the release gate for removing TDLib. It must be run manually on a physical device or emulator with network access. Do not use it to change production DC endpoints or to infer successful behavior from a transport failure.

## Build Under Test

- Commit: record the exact commit SHA.
- Variant: `officialLibreDebug` and, when Firebase services are available, `officialFirebaseDebug`.
- APK: `app/build/outputs/apk/officialLibre/debug/monogram-libre-universal-0.3.1-debug.apk`.
- Account: a dedicated test account selected for `KOTLIN_MTPROTO` before authentication.

## Selecting The Backend

1. Install a debug build, open Settings, then open Debug.
2. Under **Telegram backend**, enable **Use Kotlin MTProto**.
3. Wait for the confirmation message, `Telegram backend switched to KOTLIN_MTPROTO`, before beginning authentication.
4. For rollback validation, return to the same control and disable **Use Kotlin MTProto**. Wait for the `LEGACY` confirmation before inspecting the account.

The switch is debug-only and clears the previous backend before persisting the new selection. Do not use **Drop Databases**, **Drop Cache Database**, **Drop Cache**, or **Drop Prefs** for ordinary restart validation: those controls destroy the state that restart and rollback scenarios must inspect.

## Evidence Rules

For every scenario, record the app version/commit, account backend, device model/API level, timestamp, result, and a redacted log or screen recording. A scenario is not passed merely because it does not crash. Failed transport setup is environmental evidence only and does not validate the feature.

Do not include phone numbers, SMS codes, 2FA passwords, auth keys, session files, or raw update payloads in evidence.

## Authentication And Recovery

1. Select `KOTLIN_MTPROTO`, submit phone number and code, then complete 2FA when enabled.
   - Pass: authorization reaches the ready state without initializing TDLib for the selected account.
2. Stop and relaunch the app after authorization.
   - Pass: the account restores the persisted auth/DC state, recovers differences, and displays projected dialogs without re-authentication.
3. Interrupt network connectivity during an active session, then restore it.
   - Pass: reconnect/recovery completes without duplicate dialogs/messages or an unbounded retry loop.
4. Exercise a known refused transport path only when it occurs naturally.
   - Pass: the error is reported as a recoverable failure; no authorization, projection, or backend-selection state is corrupted.

## Reads, Messages, And Media

1. Open dialogs, page history in both directions, inspect a profile, and send a plain-text message.
   - Pass: data belongs to the selected account, paging cursors are stable, and sent state is reconciled from server updates.
2. Download a profile photo and an installed wallpaper, wait for completion, and reopen the corresponding UI.
   - Pass: paths become available only after an owned download-completion event; no TDLib file ID or path is exposed.
3. Set an installed image or pattern wallpaper as default with blur/motion variants.
   - Pass: the rendered selection reflects the accepted `account.installWallPaper` operation after refresh. Fill wallpapers and wallpaper upload are expected to remain unavailable.
4. Inspect storage usage, clear completed downloads globally, and relaunch.
   - Pass: only selected-account canonical app-private completed transfer files are removed; incomplete transfers and files outside the owned root remain intact.

## Stories And Invites

1. Mark a story read, react with an emoji/custom emoji where allowed, close a story, delete a story owned by the test account, and activate stealth mode.
   - Pass: each mutation is server-confirmed before durable state changes. A failed or rejected mutation must not create a local success state.
2. Join a disposable invited chat.
   - Pass: the result reflects joined versus request-sent status from the server envelope.

## Rollback

1. With the selected MTProto account authorized and projected data present, switch that account to `LEGACY`.
   - Pass: MTProto live transport closes before state cleanup; legacy initializes only after selection; the selected account remains usable.
2. Restart after rollback and inspect the selected account.
   - Pass: no stale MTProto file handles, paths, dialogs, or authorization state are consumed by the legacy backend.

## Recording Results

Record each scenario as `PASS`, `FAIL`, `BLOCKED_ENVIRONMENT`, or `NOT_RUN`. A `BLOCKED_ENVIRONMENT` result requires the failing endpoint/error and a later rerun; it cannot be treated as a pass. TDLib retirement remains blocked until all applicable scenarios pass for Libre and Firebase, or a user-approved goal change explicitly narrows the gate.
