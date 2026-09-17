# monogram-mtproto

Monogram's native Telegram client. This Rust library manages MTProto connections,
phone and password authentication, dialogs, message history, updates, and media
downloads. The Android app calls it through UniFFI and `network/bridge`.

## Dependencies

The protocol crates and generated Telegram types come from
[gdlbo/telers-mtproto-impl](https://github.com/gdlbo/telers-mtproto-impl).
`Cargo.toml` expects that repository at `../telers-mtproto-impl`, beside the
Monogram checkout. The submodule at `vendor/telers-mtproto-impl` is not the build
dependency.

The crate also uses `vendor/tlottie`. Initialize the project's submodules from
the repository root:

```console
git submodule update --init --recursive
```

Android builds require Rust, `cargo-ndk`, and an Android NDK. Set `sdk.dir` in
`local.properties` or provide `ANDROID_SDK_ROOT`. `ANDROID_NDK_HOME` can select a
specific NDK installation.

## Build and test

Run host tests from the repository root:

```console
cargo test --manifest-path native/mtproto-rs/Cargo.toml
```

The Android build compiles `monogram_mtproto` for `armeabi-v7a`, `arm64-v8a`,
and `x86_64`. On Windows, the native library can be built separately with:

```console
./gradlew.bat :native:mtproto:buildNativeMtproto
```

Use `./gradlew` on Linux or macOS. `:app:assembleDebug` also runs this task.
For Kotlin-only changes, `-PskipNativeBuild=true` reuses existing native libraries.

The equivalent manual command, run from `native/mtproto-rs`, is:

```console
cargo ndk -t armeabi-v7a -t arm64-v8a -t x86_64 -o ../mtproto/src/main/jniLibs build --release
```

## Kotlin bindings

`:native:mtproto:buildNativeMtproto` regenerates UniFFI Kotlin into
`native/mtproto/src/main/java` from the built `libmonogram_mtproto.so`.
`-PskipNativeBuild=true` skips both the native compile and that generate step.

To regenerate by hand from `native/mtproto-rs`:

```console
cargo run --features bindgen-cli --bin uniffi-bindgen -- generate --library ../mtproto/src/main/jniLibs/arm64-v8a/libmonogram_mtproto.so --language kotlin --out-dir ../mtproto/src/main/java --no-format
```

## Session storage

Android creates the client with `create_encrypted_client`. Session data is
encrypted with AES-256-GCM and includes the authorization snapshot, user ID,
cached peers, update position, and media index.

A non-exportable Android Keystore key wraps a random data key stored in the
adjacent `.key` file. Only the data key crosses UniFFI; Telegram authorization
keys and session JSON stay in Rust. A missing Keystore key or a damaged encrypted
file prevents restoration without overwriting the saved session. Copying a
device backup to another device does not transfer access to the session.

Existing JSON sessions migrate on the first encrypted restore. Replacement is
atomic, so a failed migration leaves the original file intact. The unencrypted
`create_client` entry point remains available for host tests.
