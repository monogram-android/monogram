# Monogram

**Read this in other languages:** [Русский](README_RU.md)

![Kotlin](https://img.shields.io/badge/Kotlin-2.0+-blue.svg?logo=kotlin)
![Jetpack Compose](https://img.shields.io/badge/Jetpack_Compose-Material_3-4285F4?logo=android)
![TDLib](https://img.shields.io/badge/TDLib-1.8.62-blue)
![Status](https://img.shields.io/badge/Status-Active_Development-orange)
![Boosty](https://img.shields.io/badge/Boosty-Support_the_project-ff6f61?logo=boosty&logoColor=white)
![License](https://img.shields.io/badge/License-GPLv3-blue.svg)

**Monogram** is a modern, lightning-fast, and elegant unofficial Telegram client for Android. Built with **Jetpack
Compose** and **Material Design 3**, it aims to provide a native and fluid experience while leveraging the power of the
official **TDLib**.

> **Note:** Monogram is currently in **active development**. Expect frequent updates, architectural changes, and the
> occasional bug.

Support the project on [Boosty](https://boosty.to/monogram).

---

## Key Features

* **Material Design 3**: A beautiful, adaptive UI that looks great on phones, tablets, and foldables.
* **Clean Architecture**: Separation of concerns with Domain, Data, and Presentation layers.
* **MVI Pattern**: Predictable state management using MVIKotlin.
* **Secure**: Built-in biometric locking and encrypted local storage.
* **Media Rich**: High-performance media playback with ExoPlayer and Coil 3.
* **Fast & Efficient**: Powered by Kotlin Coroutines and optimized for performance.

---

## Tech Stack

Monogram leverages the latest Android development tools and libraries:

| Category                 | Libraries                                                                                                             |
|:-------------------------|:----------------------------------------------------------------------------------------------------------------------|
| **Language**             | [Kotlin](https://kotlinlang.org/)                                                                                     |
| **UI Toolkit**           | [Jetpack Compose](https://developer.android.com/jetpack/compose) (Material 3)                                         |
| **Architecture**         | [Decompose](https://github.com/arkivanov/Decompose) (Navigation), [MVIKotlin](https://github.com/arkivanov/MVIKotlin) |
| **Dependency Injection** | [Koin](https://insert-koin.io/)                                                                                       |
| **Async**                | Coroutines & Flow                                                                                                     |
| **Telegram Core**        | [TDLib](https://core.telegram.org/tdlib) (Telegram Database Library)                                                  |
| **Image Loading**        | [Coil 3](https://coil-kt.github.io/coil/)                                                                             |
| **Media**                | Media3 (ExoPlayer)                                                                                                    |
| **Maps**                 | [MapLibre](https://maplibre.org/)                                                                                     |
| **Local DB**             | Room                                                                                                                  |

---

## Project Structure

The project follows a multi-module structure to ensure separation of concerns and scalability:

* **:app** - The main Android application module.
* **:domain** - Pure Kotlin module containing business logic, use cases, and repository interfaces.
* **:data** - Implementation of repositories, data sources, and TDLib integration.
* **:presentation** - UI components, screens, and view models (MVI Stores).
* **:core** - Common utility classes and extensions used across modules.
* **:baselineprofile** - Baseline Profiles for optimizing app startup and performance.

---

## Getting Started

Follow these steps to set up the project locally.

### Prerequisites

* **Android Studio**: Ladybug or newer (recommended).
* **JDK**: Java 17 or newer.

### 1. Clone the Repository
```bash
git clone https://github.com/monogram-android/monogram.git
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

### 3. How to configure push notifications

1. Log in to the [Firebase console](https://console.firebase.google.com)
2. Create a new project.
3. Add a new application with the applicationId you need (if you have multiple applications with different IDs, you will need to create multiple applications). **By default, the applicationId for debug and release builds is different!**
4. The console will create a `google-services.json` file, which you can copy to the root of the **app** module (`monogram/app/google-services.json`). If you have created multiple applications, only copy the most recent config.
5. Go to the "Cloud Messaging" section.
6. Click on the **Manage service accounts** link.
7. In the window that opens, select the top **Keys** section.
8. Click on the **Add key** button and select the **JSON** option in the dialog box. Wait for the file to be downloaded to your computer.
9. Return to the page where you received the App ID from Telegram.
10. Click on the Update button next to the FCM credentials section.
11. Upload the JSON of the service account in the page that opens.

### 4. Build and Run

1. Open the project in **Android Studio**.
2. Increase the IDE indexing limits so `TdApi.java` (the TDLib wrapper) is indexed correctly:

```properties
# custom IntelliJ IDEA properties (expand/override 'bin\idea.properties')

# size in Kb
idea.max.intellisense.filesize=20480
# size in Kb
idea.max.content.load.filesize=20480
```

3. In **Android Studio** or **IntelliJ IDEA**, open **Help -> Edit Custom Properties...**, paste the lines above, and restart the IDE if prompted.
4. Sync Gradle.
5. Select the `app` run configuration.
6. Connect a device or start an emulator.
7. Click **Run**.

---
## Building TDLib

If you need to build TDLib from source, you must first install the required dependencies on your system. For Debian/Ubuntu-based distributions, you can install them by running:

```bash
sudo apt-get update
sudo apt-get install build-essential git curl wget php perl gperf unzip zip default-jdk cmake
```

Once the dependencies are installed, you can start the build process by executing the build script from the root of your project:

```bash
./build-tdlib.sh
```

---
## Contributing

We welcome contributions! Whether it's fixing bugs, improving documentation, or suggesting new features.

1. **Check the Issues**: Look for open issues or create a new one to discuss your ideas.
2. **Work from `develop`**: Create your branch from `develop` and keep your work based on that branch.
3. **Fork & Branch**: Fork the repo and create a feature branch.
4. **Code Style**: Please follow the existing Kotlin coding style and Clean Architecture guidelines.
5. **Submit a PR**: Open a Pull Request to `develop` with a clear description of your changes.

**Important**:

* Respect the [Telegram API Terms of Service](https://core.telegram.org/api/terms).
* Ensure your code passes all checks and tests.

---

## License

This project is licensed under the GNU General Public License v3.0. See `LICENSE` for the full text.
