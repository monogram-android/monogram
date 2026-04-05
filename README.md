<h1 align="center">
  <br>
  <a href="https://github.com/monogram-android/monogram"><img width="130" height="130" alt="MonoGram" src="./documents/monogram.png" />
</a>
  <br>
  <b>MonoGram</b>
  <br>
</h1>

<h1 align="center">
  <a href="https://github.com/monogram-android/monogram/blob/develop/LICENSE">
    <img src="https://img.shields.io/badge/License-GPLv3-blue.svg" width="120">
  </a>
  <a href="https://github.com/monogram-android/monogram/stargazers">
    <img src="https://img.shields.io/github/stars/monogram-android/monogram" width="120">
  </a>
  <img src="https://img.shields.io/badge/Kotlin-2.0+-blue.svg?logo=kotlin" width="130">
  <img src="https://img.shields.io/badge/TDLib-1.8.62-blue" width="120">
  <img src="https://img.shields.io/badge/Status-Active_Development-orange" width="170">
  <a href="https://boosty.to/monogram">
    <img src="https://img.shields.io/badge/Boosty-Support_the_project-ff6f61?logo=boosty&logoColor=white" width="200">
  </a>
</h1>

**Read this in other languages:** [Русский](README_RU.md), [한국어](README_KOR.md), [اُردو](README_UR.md), [Español](README_ES.md)


---

**MonoGram** is a modern, lightning-fast, and elegant unofficial Telegram client for Android. Built with **Jetpack Compose** and **Material Design 3**, it delivers a native and fluid experience powered by the official **TDLib**.

> [!IMPORTANT]
> MonoGram is currently in **active development**. Expect frequent updates, architectural changes, and the occasional bug.

Support the project on [**Boosty**](https://boosty.to/monogram).

---

## Screenshots

<div align="center">

| | | | |
|:---:|:---:|:---:|:---:|
| <img src="./documents/1.png" width="180" alt="Screenshot 1" /> | <img src="./documents/2.png" width="180" alt="Screenshot 2" /> | <img src="./documents/3.png" width="180" alt="Screenshot 3" /> | <img src="./documents/4.png" width="180" alt="Screenshot 4" /> |

</div>

---

## Key Features

- **Independent Client** — Not a fork of Telegram for Android. MonoGram is built entirely from scratch as a standalone project.
- **Material Design 3** — A beautiful, adaptive UI that looks great on phones, tablets, and foldables.
- **Secure** — Built-in biometric locking and encrypted local storage.
- **Media Rich** — High-performance media playback with ExoPlayer and Coil 3.
- **Fast & Efficient** — Powered by Kotlin Coroutines and optimized for performance.
- **Clean Architecture** — Clear separation of concerns with Domain, Data, and Presentation layers.
- **MVI Pattern** — Predictable state management using MVIKotlin.
- **No NFT or Crypto** — MonoGram will never include NFT promotions, gifts or any other features pushed by Telegram that we consider outside the scope of a messaging app.

---

## Getting Started

Follow these steps to set up the project locally.

### Prerequisites

- **Android Studio**: Ladybug or newer (recommended).
- **JDK**: Java 17 or newer.

### 1. Clone the Repository

```bash
git clone --recurse-submodules https://github.com/monogram-android/monogram.git
cd monogram
```

### 2. Configure Telegram API Keys

To connect to Telegram servers, you need your own API credentials.

1. Log in to [my.telegram.org](https://my.telegram.org/).
2. Go to **API development tools**.
3. Create a new application to get your `App api_id` and `App api_hash`.
4. Create a file named `local.properties` in the root directory of the project (if it doesn't exist).
5. Add the following lines:

```properties
API_ID=12345678
API_HASH=your_api_hash_here
```

### 3. Configure Push Notifications

1. Log in to the [Firebase console](https://console.firebase.google.com).
2. Create a new project.
3. Add a new application with the `applicationId` you need. If you have multiple applications with different IDs, create multiple Firebase applications. **By default, the `applicationId` for debug and release builds is different.**
4. Download the `google-services.json` file and copy it to the root of the **app** module (`monogram/app/google-services.json`). If you created multiple applications, copy only the most recent config.
5. Go to the **Cloud Messaging** section.
6. Click **Manage service accounts**.
7. Select the **Keys** section at the top of the window that opens.
8. Click **Add key** and select the **JSON** option. Wait for the file to download.
9. Return to the Telegram API page where you received your App ID.
10. Click **Update** next to the FCM credentials section.
11. Upload the service account JSON on the page that opens.

### 4. First Time Setup: Building libvpx

The animations require libvpx to be compiled. This has to be done before starting a Gradle build or it will cause build failures.

1. Change your working directory to `presentation/src/main/cpp`
2. In `build.sh`, add your `ANDROID_NDK_HOME`
3. Run `build.sh` and wait for it to finish

### 5. Build and Run

1. Open the project in **Android Studio**.
2. Increase the IDE indexing limits so `TdApi.java` (the TDLib wrapper) is indexed correctly. In **Android Studio** or **IntelliJ IDEA**, open **Help → Edit Custom Properties...**, paste the lines below, and restart the IDE if prompted:

```properties
# size in Kb
idea.max.intellisense.filesize=20480
# size in Kb
idea.max.content.load.filesize=20480
```

3. Sync Gradle.
4. Select the `app` run configuration.
5. Connect a device or start an emulator.
6. Click **Run**.

---

## Building TDLib

If you need to build TDLib from source, first install the required dependencies. For Debian/Ubuntu-based distributions:

```bash
sudo apt-get update
sudo apt-get install build-essential git curl wget php perl gperf unzip zip default-jdk cmake
```

Then run the build script from the root of your project:

```bash
./build-tdlib.sh
```

---

## Contributing

We welcome contributions! Whether it's fixing bugs, improving documentation, or suggesting new features.

1. **Check the Issues** — Look for open issues or create a new one to discuss your ideas.
2. **Work from `develop`** — Create your branch from `develop` and keep your work based on that branch.
3. **Fork & Branch** — Fork the repo and create a feature branch.
4. **Code Style** — Follow the existing Kotlin coding style and Clean Architecture guidelines.
5. **Submit a PR** — Open a Pull Request to `develop` with a clear description of your changes.

> [!IMPORTANT]
> - Respect the [Telegram API Terms of Service](https://core.telegram.org/api/terms).
> - Ensure your code passes all checks and tests.

### Reporting Bugs & Suggesting Features

- **Bugs** — Open an issue and use the `[Bug]` tag in the title (e.g. `[Bug] App crashes on startup`). You can also browse all known bugs on the [**Bug Tracker**](https://github.com/orgs/monogram-android/projects/3/views/1).
- **Feature Requests** — Open an issue with the `[Feature]` tag (e.g. `[Feature] Support scheduled messages`). Existing feature requests can be found on the [**Feature Board**](https://github.com/orgs/monogram-android/projects/5/views/1).

---

## Translations

MonoGram welcomes community translations! You can contribute your own language by editing the strings resource file.

The source strings are located at [`presentation/src/main/res/values/string.xml`](https://github.com/monogram-android/monogram/blob/develop/presentation/src/main/res/values/string.xml). To add a new language, create a corresponding `values-<locale>/string.xml` file (e.g. `values-de/string.xml` for German) and translate the strings there. Open a PR with your translation and we'll get it merged.

---

## Tech Stack

MonoGram leverages the latest Android development tools and libraries:

| Category | Libraries |
|:---|:---|
| **Language** | [Kotlin](https://kotlinlang.org/) |
| **UI Toolkit** | [Jetpack Compose](https://developer.android.com/jetpack/compose) (Material 3) |
| **Architecture** | [Decompose](https://github.com/arkivanov/Decompose) (Navigation), [MVIKotlin](https://github.com/arkivanov/MVIKotlin) |
| **Dependency Injection** | [Koin](https://insert-koin.io/) |
| **Async** | Coroutines & Flow |
| **Telegram Core** | [TDLib](https://core.telegram.org/tdlib) (Telegram Database Library) |
| **Image Loading** | [Coil 3](https://coil-kt.github.io/coil/) |
| **Media** | Media3 (ExoPlayer) |
| **Maps** | [MapLibre](https://maplibre.org/) |
| **Local DB** | Room |

---

## Project Structure

The project follows a multi-module structure to ensure separation of concerns and scalability:

| Module | Description |
|:---|:---|
| **:app** | The main Android application module. |
| **:domain** | Pure Kotlin module containing business logic, use cases, and repository interfaces. |
| **:data** | Implementation of repositories, data sources, and TDLib integration. |
| **:presentation** | UI components, screens, and view models (MVI Stores). |
| **:core** | Common utility classes and extensions used across modules. |
| **:baselineprofile** | Baseline Profiles for optimizing app startup and performance. |

---

## License

This project is licensed under the [**GNU General Public License v3.0**](LICENSE).
