# Monogram

**Read this in other languages:** [Русский](README_RU.md)

![Kotlin](https://img.shields.io/badge/Kotlin-2.0+-blue.svg?logo=kotlin)
![Jetpack Compose](https://img.shields.io/badge/Jetpack_Compose-Material_3-4285F4?logo=android)
![TDLib](https://img.shields.io/badge/TDLib-1.8.62-blue)
![Status](https://img.shields.io/badge/Status-Active_Development-orange)
![Boosty](https://img.shields.io/badge/Boosty-Support_the_project-ff6f61?logo=boosty&logoColor=white)

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

### 3. Build and Run

1. Open the project in **Android Studio**.
2. Sync Gradle.
3. Select the `app` run configuration.
4. Connect a device or start an emulator.
5. Click **Run**.

---

## Contributing

We welcome contributions! Whether it's fixing bugs, improving documentation, or suggesting new features.

1. **Check the Issues**: Look for open issues or create a new one to discuss your ideas.
2. **Fork & Branch**: Fork the repo and create a feature branch.
3. **Code Style**: Please follow the existing Kotlin coding style and Clean Architecture guidelines.
4. **Submit a PR**: Open a Pull Request with a clear description of your changes.

**Important**:

* Respect the [Telegram API Terms of Service](https://core.telegram.org/api/terms).
* Ensure your code passes all checks and tests.
