# HarmonyOS 7 visual prototype

Форк Monogram с визуальным прототипом в духе HarmonyOS 7. Сетевой слой остаётся на Rust
(`native/mtproto-rs` + UniFFI) и в прототипе не изменяется.

## Что добавлено

- `core/harmony-ui` — дизайн-система прототипа:
  - `HarmonyTokens.kt` — токены HarmonyOS Design (4vp-сетка, размеры шрифта 11/12/14/16/18/20/24/28/36,
    радиусы 4/8/12/16/24, высота CONTROL 48, три уровня тени).
  - `HarmonyTheme.kt` — светлая/тёмная темы и два акцента: `HarmonyBlue` (`#0A59F7`) и
    `DnaNeon` из темы `HuaweiDnaTheme` (`#5AFF9E` / `#5ADCFF` / фон `#02090C`).
  - `GlassSurface.kt` — Immersive Light только в разрешённых Huawei зонах: navigation,
    dialog, control. Обычное содержимое остаётся непрозрачным.
  - `SpatialLayer.kt` — параллакс шапки и слои глубины (тень + лёгкое масштабирование).
  - `ServiceCards.kt` — карточки-плитки, строки чатов, пузыри сообщений.
  - `HarmonyGallery.kt` — три статических экрана: чаты, диалог, виджеты.
- `HarmonyGalleryActivity` в `app/src/debug` — хост прототипа без логина в Telegram.

## Запуск

Нужны JDK 17, Android SDK 37, NDK r28c, Rust 1.98+ и `cargo-ndk`. Скопируйте
`local.properties.example` в `local.properties`, укажите `sdk.dir`; API-ключи для прототипа
не нужны. Подключите подмодули:

```powershell
git submodule update --init --recursive
./gradlew.bat :app:installDebug
adb shell am start -n org.monogram.debug/org.monogram.HarmonyGalleryActivity
```

Для x86_64-эмулятора на Windows нужен Android Emulator hypervisor driver (AEHD); без него
запустите прототип на физическом устройстве или включите WHPX.

В Android Studio те же экраны доступны через `@Preview` в `HarmonyGallery.kt`.

## Ограничение Immersive Light

С сентября 2026 Huawei ограничивает материал навигацией, диалогами и контролами.
Поэтому стекло в прототипе применяется к верхней/нижней панели, полю ввода, слайдеру
и тумблерам, а карточки чатов сделаны плотными. Это осознанное соответствие платформе,
а не недостаток реализации.

Compose не имеет backdrop-filter, поэтому матовый эффект достигается размытием
декоративного слоя `GlassBackdrop` под панелями, а не размытием произвольного контента.
На API < 31 размытие отключается и остаётся полупрозрачный градиент.
