# Monogram development guide

Monogram is an Android Telegram client. The application uses Kotlin and Jetpack
Compose for its UI and Rust with UniFFI for MTProto. The minimum Android SDK is
24 (Android 7.0) with core-library desugaring enabled. Release and beta use
application id `org.monogram`. Debug builds and instrumented tests use
`org.monogram.debug`, so installing them does not replace a logged-in
`org.monogram` app.

## Repository layout

| Path | Responsibility |
| --- | --- |
| `app` | Entry point, dependency injection, root navigation, sponsor sync, crash screen |
| `core/*` | Shared models, Room database, UI, markup access, sponsor registry |
| `feature/*` | Authentication, chats, dialogs, folders, profiles, and settings |
| `network/bridge` | `MtprotoClient` seam (`MtprotoClient`, `BridgedMtprotoClient`, `ClientApis`), error mapping, and one package per domain (`session`, `updates`, `chat`, `message`, `profile`, `media`, `web`, `notify`) holding that domain's API, Ops interface, and DTO mappings |
| `network/http` | HTTP media downloads, cache, and queue |
| `native/mtproto-rs` | Rust MTProto client and UniFFI exports |
| `native/markup-rs` | Rust Markdown, syntax highlighting, and math parser |
| `native/mtproto` and `native/markup` | Kotlin facades, generated bindings, and native libraries |
| `native/vpx` | Prebuilt libvpx VP9 decoder linked by `native/mtproto-rs` for video stickers |
| `vendor` | Third-party source and submodules; do not edit for application work |

Dependencies point from `app` to features, then to `core` and `network`. Features
must not depend on one another or import generated UniFFI types. Native protocol
calls go through `network/bridge`; protocol logic belongs in Rust.

## Setup

Copy `local.properties.example` to `local.properties` and set `sdk.dir`,
`API_ID`, and `API_HASH` from [my.telegram.org/apps](https://my.telegram.org/apps).
Keep this file untracked and never log credentials. CI writes secrets
`GOOGLE_SERVICES` to `app/google-services.json`, `KEYS` to `local.properties`
(API id/hash and `RELEASE_*` signing keys), and base64 `KEYSTORE` to
`release.keystore`. Missing secrets still assemble (debug-signed). Login needs
a real API id. Artifacts are `monogram-debug` and `monogram-release`.

Push notifications: keep `app/google-services.json` and any
`firebase-adminsdk` file untracked. Register the FCM/GCM key for this API id
at my.telegram.org. Test FCM on a Google Play emulator; UnifiedPush needs a
distributor. The implementation lives in `app/src/main/java/org/monogram/push/`.

The MTProto crate expects [gdlbo/telers-mtproto-impl](https://github.com/gdlbo/telers-mtproto-impl)
at `../telers-mtproto-impl`. Initialize submodules with:

```console
git submodule update --init --recursive
```

## Build and test

Use `./gradlew.bat` on Windows and `./gradlew` on Unix-like systems.

```console
./gradlew.bat test
./gradlew.bat :app:assembleDebug
cargo test --manifest-path native/mtproto-rs/Cargo.toml
cargo test --manifest-path native/markup-rs/Cargo.toml
git diff --check
```

During development, run the test task for the module you changed, such as
`:core:models:test`, `:core:database:test`, `:network:bridge:test`,
`:feature:dialog:test`, or `:app:test`.

The debug APK is written to `app/build/outputs/apk/debug/`. Install it with
`:app:installDebug` on a running emulator or device. Report device behavior only
after actually running the device check.

## Native builds

Rust, `cargo-ndk`, and an Android NDK are required. Gradle builds
`armeabi-v7a`, `arm64-v8a`, and `x86_64` automatically when native inputs change.

```console
./gradlew.bat :native:mtproto:buildNativeMtproto
./gradlew.bat :native:markup:buildNativeMarkup
```

Use `-PskipNativeBuild=true` for Kotlin-only builds; it skips both the native
compile and the UniFFI binding regeneration. A native rebuild regenerates the
UniFFI Kotlin bindings from a host debug library (release Android `.so` files
are stripped).

## Kotlin conventions

Use Decompose for navigation and MVIKotlin stores for stateful features. Compose
screens receive immutable state and intent callbacks; stores stay outside
composables. Collect flows with `collectAsStateWithLifecycle`, use stable lazy
keys, and run blocking work off the main thread.

Network state includes loading, empty, error, offline, and retry paths. Map native
failures to `TelegramError` in `network/bridge` and preserve cancellation. Put
strings in resources, provide descriptions for icon-only actions, and reuse the
Material 3 theme and components in `core/ui`.

Root navigation is list-detail: from 840 dp of width the chat list and the open
conversation share the screen, the list pane can be dragged and its width persists
in `AppearanceSettings` (`list_pane_width`), and the selected chat lives in the
navigation stack rather than in local composable state.

## Data and security

Room is a cache. Every schema change needs a forward migration and version bump;
login identity must survive migration. Do not replace a complete Telegram peer
with a `*Min` update. Outgoing messages use a new random id and remain pending
across process death.

Never log authorization keys, passwords, phone codes, message bodies, API
credentials, or session snapshots. Native session files are encrypted and
replaced atomically. Media downloads are bounded, cancellable, and must not
buffer an entire file in memory.

The crash screen (`app/.../CrashActivity.kt`, reached on the next launch after a
crash) reads `<cacheDir>/crash.log`, written by the uncaught-exception handler in
`core/common/AppLog.kt`. That file is size-capped and masks the session path;
never write credentials, codes, bodies, or keys into it.

Protocol behavior follows official Telegram documentation and the pinned Tellers
crates. Live Telegram credentials are excluded from the default test suite.

## Native protocol boundary

```text
feature -> network/bridge -> native/mtproto -> native/mtproto-rs
         -> telers-mtproto-impl -> tellers-mtproto (generated TL, layer 229)
```

`network/bridge` owns `Outcome`, `TelegramError`, and domain mapping. Kotlin owns
component lifecycles, process death, and the socket adapter. Rust owns the
protocol state machine, update cursors, history, media parts, and authentication.
The Tellers runtime provides codec, crypto, transport, session, and engine
primitives; it is not itself a Telegram client.

Protocol layer 229 is pinned. A layer change requires regenerating from
[tellers-tl](https://github.com/gdlbo/tellers-tl), updating the Rust client,
regenerating UniFFI, rebuilding all ABIs, and running a Kotlin facade contract
test.

## Protocol invariants

- Keep authorization keys native-private. Never log API hashes, phone codes,
  passwords, snapshots, auth keys, `future_auth_token`, or message bodies.
- Persist `pts`, `qts`, `seq`, and `date` per account and channel `pts` separately.
  Apply an update only when the local cursor plus its count equals the remote
  cursor; otherwise recover with `getDifference` or `getChannelDifference`.
- A non-media DC permits a single main session. Keep home-DC traffic (reads, file
  transfers) on that session; open extra parallel sessions only when `config.tmp_sessions`
  grants them. Media-DC file transfer sessions are exempt. More than the allowance
  returns 406 `AUTH_KEY_DUPLICATED`, which invalidates the authorization and forces
  a new login.
- `folder_id` 0 is the main list and 1 is archive. UI folders are dialog filters,
  not a second dialog model.
- Use Telegram history offsets (`offset_id`, `add_offset`) and keep `limit` at or
  below 100. Generate a fresh `random_id` for every outgoing message and persist
  pending sends until `updateMessageID` is received.
- Media parts are at least 1 KiB and divide 512 KiB; use 512 KiB where possible.
  Keep downloads bounded and refresh an expired file reference once at the same
  offset. `FILE_MIGRATE_X` changes only the media DC.
- Security checks follow the [MTProto security guidelines](https://core.telegram.org/mtproto/security_guidelines):
  trusted RSA keys, DH validation, authenticated `msg_key`, valid session and
  message IDs, time bounds, replay rejection, encrypted atomic session storage.

## TelegramError mapping

Map errors in `network/bridge`; UI code does not branch on raw TL names.
`CancellationException` remains cancellation.

| Class | Examples | Handling |
| --- | --- | --- |
| 303 | `PHONE_MIGRATE_X`, `USER_MIGRATE_X`, `FILE_MIGRATE_X` | Switch DC and retry once |
| 400 | `PHONE_CODE_INVALID`, `PEER_ID_INVALID` | Show a field error; do not retry blindly |
| 401 | `AUTH_KEY_UNREGISTERED`, `SESSION_REVOKED`, `SESSION_EXPIRED` | Clear the session and return to auth |
| 403 | blocked or restricted | Show the restriction |
| 406 | `AUTH_KEY_DUPLICATED` | Create a new auth key and authenticate again |
| 420 | `FLOOD_WAIT_X`, `SLOWMODE_WAIT_X` | Parse the wait and apply a bounded delay |
| files | `FILE_REFERENCE_EXPIRED` | Refresh the source and retry once |
| 2FA | `SESSION_PASSWORD_NEEDED` | Move the auth store to the password step |
| 500 / unknown layer | internal error or unexpected constructor | Reconnect, initialize the layer, then fetch a difference |

The catalog in `core/common/.../telegram/TelegramErrorCatalog.kt` is generated
from [errors.json](https://core.telegram.org/api/errors.json) (layer 227, 818
rows). Its generator is not part of the repository, so regenerate the catalog
from that JSON and update `LAYER` and `SIZE` together; do not hand-edit rows.

## References

| Topic | Reference |
| --- | --- |
| MTProto 2.0 | https://core.telegram.org/mtproto |
| Encryption and `msg_key` | https://core.telegram.org/mtproto/description |
| Transports | https://core.telegram.org/mtproto/mtproto-transports |
| Service messages | https://core.telegram.org/mtproto/service_messages |
| Auth-key vectors | https://core.telegram.org/mtproto/samples-auth_key |
| Security | https://core.telegram.org/mtproto/security_guidelines |
| Authentication and SRP | https://core.telegram.org/api/auth and https://core.telegram.org/api/srp |
| Datacenters | https://core.telegram.org/api/datacenter |
| Invoking and layers | https://core.telegram.org/api/invoking and https://core.telegram.org/api/layers |
| Updates and errors | https://core.telegram.org/api/updates and https://core.telegram.org/api/errors |
| Files and references | https://core.telegram.org/api/files and https://core.telegram.org/api/file-references |
| Folders and offsets | https://core.telegram.org/api/folders and https://core.telegram.org/api/offsets |
| API id and terms | https://core.telegram.org/api/obtaining_api_id and https://core.telegram.org/api/terms |
| Tellers TL | https://github.com/gdlbo/tellers-tl |
| Tellers MTProto runtime | https://github.com/gdlbo/telers-mtproto-impl |
| Telegram Android | https://github.com/DrKLO/Telegram |
| Grammers | https://github.com/LonamiWebs/grammers |
| Telegram Desktop | https://github.com/telegramdesktop/tdesktop |