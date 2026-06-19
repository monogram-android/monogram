# AGENTS.md

Internal handbook for agents working in this repository.

Use this file to avoid re-discovering the project, preserve the current architecture, and move quickly in a large Android codebase without introducing stack drift.

Primary source of truth:

- `README.md`
- `settings.gradle.kts`
- `gradle/libs.versions.toml`

## Non-Negotiables

- Preserve the current stack and architecture unless the user explicitly asks to change them.
- Follow the existing MVI approach in `presentation`.
- Keep non-trivial screen state and side effects in stores/components, not in scattered composable state.
- Keep flavor-specific behavior aligned across `app`, `data`, and `presentation`.
- Write or update tests when changes affect logic, parsing, mapping, repositories, stores, reducers, state transitions, or bug fixes.
- Pure visual copy/layout tweaks may not need tests; behavior changes usually do.
- If a meaningful test should exist and you do not add one, say so explicitly.
- Do not replace core libraries with parallel stacks without explicit user approval.

## Architecture

Gradle modules:

- `:app` = final Android application module
- `:presentation` = Compose UI, navigation, MVI stores, feature logic
- `:data` = repository implementations, TDLib integration, Room, push, services
- `:domain` = shared contracts, domain models, managers
- `:core` = low-level shared utilities
- `:baselineprofile` = startup/performance benchmarking

Dependency direction:

- `app` depends on `presentation`, `data`, `domain`, `core`
- `presentation` depends on `domain`, `core`
- `data` depends on `domain`, `core`
- `domain` and `core` should remain platform-light

Typical feature flow:

1. Android startup and app wiring happen in `app`
2. `presentation/root` selects startup and navigation flow
3. a `Default*Component` wires a `Store`
4. the `Store` processes intents and uses domain contracts
5. `data` implements those contracts and talks to TDLib / DB / push / platform services
6. results are mapped into UI state rendered by `*Content.kt`

## Module Details

### `:app`

Role:

- Android entrypoint
- build types, flavors, signing, APK naming/copying
- app shell wiring and top-level Android integration

Main areas:

- `app/src/main/java/org/monogram/app/components`
- `app/src/main/java/org/monogram/app/di`
- `app/src/main/java/org/monogram/app/ui`
- `app/src/main/java/org/monogram/app/ui/theme`

Belongs here:

- application startup
- Android app shell concerns
- theme/bootstrap wiring
- flavor/runtime-specific app setup

Does not belong here:

- repository logic
- TDLib implementation details
- large reusable feature flows already living in `presentation`

### `:presentation`

Role:

- main UI module
- Compose screens, navigation, MVI state management, feature interaction logic

Top-level areas:

- `presentation/root` = app-wide startup/navigation
- `presentation/di` = Koin wiring and presentation-level setup
- `presentation/core` = shared UI/media/util infrastructure
- `presentation/features` = product flows
- `presentation/settings` = settings flows
- `presentation/src/main/cpp` = native media/animation support

Common file roles:

- `*Content.kt` = UI rendering
- `*Store.kt` / `*StoreFactory.kt` = state, intents, side effects
- `Default*Component.kt` = concrete wiring and navigation integration

Important subareas:

- `features/chats`
  - largest and most fragile feature area
  - chat list, conversation, message rendering, input bar, scrolling, selection, editor flows
- `features/auth`
  - auth/login flow
- `features/webapp`, `features/webview`
  - mini apps and embedded web flows
- `settings/privacy`, `settings/proxy`, `settings/storage`
  - settings areas with substantial behavior/state

Belongs here:

- screen state
- user intent handling
- navigation state
- Compose UI
- presentation-level mapping and UI orchestration

Does not belong here:

- low-level TDLib calls
- Room access
- repository implementation details

### `:data`

Role:

- concrete data layer
- repository implementations and integration boundary with TDLib/platform/storage

Main areas:

- `data/chats`
- `data/datasource/cache`
- `data/datasource/remote`
- `data/db/dao`
- `data/db/model`
- `data/di`
- `data/gateway`
- `data/infra`
- `data/mapper`
- `data/notifications`
- `data/push`
- `data/repository`
- `data/service`
- `data/stickers`
- `data/src/official/jniLibs`
- `data/src/telemt/jniLibs`

Belongs here:

- repository implementations
- TDLib access
- local cache and DB
- push implementation
- data mapping between external/raw and internal models

Does not belong here:

- Compose UI
- screen state
- app shell concerns

### `:domain`

Role:

- shared contract/model layer between `presentation` and `data`

Main areas:

- `domain/models`
- `domain/models/webapp`
- `domain/repository`
- `domain/managers`
- `domain/proxy`

Belongs here:

- interfaces/contracts
- stable domain models
- business-level abstractions

Does not belong here:

- Android framework code
- Room entities/DAOs
- Compose/UI code
- TDLib implementation details

### `:core`

Role:

- low-level shared helpers with low coupling

Known area:

- `core/date`

Belongs here:

- generic reusable utilities

Does not belong here:

- feature-specific behavior
- app-specific orchestration

### `:baselineprofile`

Role:

- performance/startup benchmark module targeting `:app`

Main files:

- `BaselineProfileGenerator.kt`
- `StartupBenchmarks.kt`

## Task Recipes

- UI issue on one screen:
  - start at the nearest `*Content.kt`
  - then inspect the related `Store`
  - then inspect the `Default*Component` if navigation/wiring is involved
- MVI/state bug:
  - start from `*Store.kt` and `*StoreFactory.kt`
  - check intents, state transitions, side effects, and any reducer/executor-style logic
- Chat bug:
  - start in `presentation/features/chats`
  - if it smells like data/update/state source issue, follow into `data/chats`, `data/gateway`, `data/datasource/remote`
- TDLib/API shape bug:
  - inspect `data/gateway` and `data/datasource/remote`
  - inspect `data/src/official/java/org/drinkless/tdlib/TdApi.java` or `data/src/telemt/java/org/drinkless/tdlib/TdApi.java`
  - then check official TDLib docs
- Mapper/parsing bug:
  - start in `data/mapper`, then trace caller repository/datasource usage
- Push or flavor bug:
  - inspect `firebase/libre` source sets in `app`, `data`, and `presentation`
  - confirm the same flavor assumption across modules before changing code
- Storage/database bug:
  - start in `data/db`, `data/datasource/cache`, and affected repository
- Build or Android SDK bug:
  - if errors look environment-related, assume sandbox may be the cause and escalate quickly

## TDLib Reference

The project uses `TdApi.java` as the generated TDLib Java API surface.

Important local copies:

- `data/src/official/java/org/drinkless/tdlib/TdApi.java`
- `data/src/telemt/java/org/drinkless/tdlib/TdApi.java`

Upstream/reference copies also exist in:

- `td/example/android/tdlib/java/org/drinkless/tdlib/TdApi.java`
- `td-telemt/example/android/tdlib/java/org/drinkless/tdlib/TdApi.java`

When working on TDLib-related behavior:

- inspect `TdApi.java` first for object/function types and field names
- inspect `data/gateway` for the project’s TDLib boundary
- inspect `data/datasource/remote` and repositories for call sites

External TDLib references:

- Telegram API docs: `https://core.telegram.org/api`
- TDLib C++/API docs: `https://core.telegram.org/tdlib/docs/td__api_8h.html`

IDE note from `README.md`:

- `TdApi.java` is large enough that Android Studio indexing limits may need to be increased

## Dependency Usage Guide

All dependency versions are centralized in `gradle/libs.versions.toml`.

Rules:

- change versions in the version catalog, not ad hoc in module files
- extend the current stack before adding a new library
- do not introduce a parallel stack unless the user explicitly asks for it

Current stack and intended use:

- `Kotlin` + `kotlinx.coroutines` + `Flow`
  - default async/concurrency model
  - do not introduce `RxJava`
- `Jetpack Compose` + `Material 3` + adaptive Compose libs
  - main UI toolkit
  - do not pivot new UI work to an XML-first architecture
- `Decompose`
  - navigation and component lifecycle in `presentation`
  - do not replace with another navigation stack without request
- `MVIKotlin`
  - screen state management and MVI flow in `presentation`
  - keep non-trivial presentation logic in the existing MVI shape
- `Koin`
  - dependency injection across `app`, `presentation`, and `data`
  - do not replace with `Dagger` or `Hilt`
- `kotlinx.serialization`
  - serialization/parsing layer
  - avoid introducing `Gson` or `Moshi` without explicit need
- `Room` + `KSP`
  - persistence and codegen in `data/db`
  - do not replace with another persistence stack casually
- `Coil 3`
  - image, GIF, SVG, and video loading in UI
  - do not replace with `Glide` or `Picasso`
- `Media3 / ExoPlayer`
  - media playback
- `CameraX`
  - camera capture and scanner flows
- `ZXing`
  - QR/barcode processing
- `TDLib`
  - Telegram core integration
- `Firebase Messaging`
  - push for `firebase` variants
- `UnifiedPush`
  - non-Firebase compatible push path and abstraction
- `MapLibre Compose`
  - map UI
- `AndroidX Security Crypto` + `Biometric`
  - local security and biometric flows
- `libphonenumber`
  - phone number parsing/formatting
- `OSS Licenses plugin` + `play-services-oss-licenses`
  - licenses screen support

## Build, Flavors, Sandbox

Flavor dimensions:

- `tdlib`: `official`, `telemt`
- `runtime`: `firebase`, `libre`

Meaning:

- `official` / `telemt` = TDLib source/binaries
- `firebase` = Firebase-backed push/runtime path
- `libre` = non-Firebase path

Required local config in `local.properties`:

```properties
API_ID=12345678
API_HASH=your_api_hash_here
```

Optional release signing:

```properties
RELEASE_STORE_FILE=keystore/release.jks
RELEASE_STORE_PASSWORD=...
RELEASE_KEY_ALIAS=...
RELEASE_KEY_PASSWORD=...
```

Firebase variants also require:

- `app/google-services.json`

Native prerequisite for `presentation`:

1. go to `presentation/src/main/cpp`
2. set `ANDROID_NDK_HOME` in `build.sh`
3. run `build.sh`

TDLib build commands:

```bash
./build-tdlib.sh official
./build-tdlib.sh telemt
./build-tdlib.sh both
```

WSL examples from Windows PowerShell:

```powershell
wsl -d Ubuntu -- bash -lc "cd /mnt/z/GitHub/monogram && ./build-tdlib.sh official"
wsl -d Ubuntu -- bash -lc "cd /mnt/z/GitHub/monogram && ./build-tdlib.sh telemt"
```

Useful Gradle tasks:

```powershell
.\gradlew.bat :app:assembleOfficialLibreDebug
.\gradlew.bat :app:assembleTelemtLibreDebug
.\gradlew.bat :data:testOfficialLibreDebugUnitTest
.\gradlew.bat :presentation:testOfficialLibreDebugUnitTest
.\gradlew.bat :core:test
```

Sandbox rule:

- If Gradle, Android SDK, NDK, emulator, ADB, or related tooling fails because SDK paths, environment access, local components, or process spawning appear restricted, assume sandbox is the likely cause.
- Do not waste time on random workaround guesses first.
- Automatically request an out-of-sandbox build/test run with escalation.
- This especially applies to Android SDK lookup errors, missing SDK components that are known to exist locally, emulator/ADB access issues, and Gradle failures that look environment-related rather than code-related.

## Testing Expectations

Add or update tests for:

- bug fixes
- store/reducer/side-effect logic changes
- repository behavior changes
- mapper/parser/utility changes
- chat behavior changes
- webapp/proxy logic changes
- state machine or branching logic changes

Check existing tests first in:

- `presentation/src/test/java`
- `data/src/test/java`
- `data/src/officialUnitTest/java`
- `data/src/telemtUnitTest/java`
- `core/src/test`

Verification rule:

- run the narrowest relevant test task first
- then run broader compile/build validation only if needed

## Navigation Heuristics

Usually start from:

- `app` for startup/theme/bootstrap problems
- `presentation` for UI, navigation, MVI state, and user interaction issues
- `data` for TDLib, repository, cache, DB, push, and mapper issues
- `domain` for interfaces/contracts/model boundaries
- `core` for generic shared utility problems

Usually ignore unless debugging build internals:

- `.git/`
- `.gradle/`
- `.idea/`
- `.kotlin/`
- `build/`
- `app/build/`
- `data/build/`
- `presentation/build/`
- `presentation/.cxx/`
- `domain/build/`
- `core/build/`
- `baselineprofile/build/`

## Hotspots / Risks

- `presentation/features/chats`
  - largest UI/logic surface and easiest place to introduce regressions
- flavor alignment across `app`, `data`, and `presentation`
  - one-module-only fixes often break another variant
- `data/gateway`
  - TDLib boundary; small changes can fan out widely
- `data/mapper`
  - easy place for subtle model regressions
- `presentation/src/main/cpp`
  - native build/setup can fail for environment reasons unrelated to app code
