# Monogram

🌍 **Read this in other languages:** [Русский](README_RU.md)

![Kotlin](https://img.shields.io/badge/Kotlin-2.0+-blue.svg?logo=kotlin)
![Jetpack Compose](https://img.shields.io/badge/Jetpack_Compose-Material_3-4285F4?logo=android)
![TDLib](https://img.shields.io/badge/TDLib-1.8.62-blue)
![Status](https://img.shields.io/badge/Status-Active_Development-orange)

**A modern, lightning-fast, and elegant unofficial Telegram client for Android.**

> **Note:** Monogram is in **active development**. Expect frequent updates, codebase changes, and bugs.

## Overview

Monogram is built to deliver a native and seamless Telegram experience. Powered by the official **TDLib**, it features a fluid **Material Design 3** interface and follows strict **Clean Architecture** and **MVI** principles.

## Tech Stack

* **Architecture & State:** MVI, [Decompose](https://github.com/arkivanov/Decompose) (navigation & lifecycle), Koin (DI).
* **UI:** Jetpack Compose + Material 3 Adaptive (seamless phone-to-tablet scalability).
* **Async:** Kotlin Coroutines & Flow.
* **Media:** Media3/ExoPlayer (playback), Coil 3 (GIF/SVG/video frames), Lottie (animations).
* **Camera & ML:** CameraX + ML Kit Vision (lightning-fast QR/barcode scanning).
* **Maps:** OSMDroid (open-source native map rendering).
* **Security:** Biometric Compose (app locking), Security Crypto (safe local data storage).

##  Getting Started

**1. Clone the repository**
```bash
git clone https://github.com/monogram-android/monogram.git

```

**2. Set up API Keys**
Create a `local.properties` file in the project root and add your Telegram API credentials (you can grab these from [my.telegram.org](https://my.telegram.org/)):

```properties
API_ID=your_api_id
API_HASH=your_api_hash

```

**3. Build & Run**
Open the project in **Android Studio**, let Gradle sync, and fire it up!

### Contribution Guidelines

*   **Respect Telegram's Terms of Service:** Monogram is an unofficial client. We strictly adhere to the [Telegram API Terms of Service](https://core.telegram.org/api/terms). Contributions that facilitate spam, unauthorized data scraping, or any other violations of Telegram's terms will be rejected.
*   **Architecture & Patterns:** Maintain the project's **Clean Architecture** and **MVI** flow. Ensure that business logic resides in the `domain` module, data handling in `data`, and UI logic in `presentation`.
*   **Modern Android Development:** Use Jetpack Compose and Material 3 components. Ensure UI changes are responsive and leverage Material 3 Adaptive for various form factors (phones, tablets, foldables).
*   **Code Style:** Write clean, idiomatic Kotlin. Follow the existing codebase's formatting and naming conventions.
*   **Testing:** Verify your changes on multiple device configurations. Ensure that new features do not break existing functionality or performance.