# Monogram

🌍 **Читать на других языках:** [English](README.md)

![Kotlin](https://img.shields.io/badge/Kotlin-2.0+-blue.svg?logo=kotlin)
![Jetpack Compose](https://img.shields.io/badge/Jetpack_Compose-Material_3-4285F4?logo=android)
![TDLib](https://img.shields.io/badge/TDLib-1.8.62-blue)
![Status](https://img.shields.io/badge/Status-Active_Development-orange)

**Современный, молниеносный и элегантный неофициальный клиент Telegram для Android.**

> **Примечание:** Monogram находится в стадии **активной разработки**. Ожидайте частых обновлений, изменений в кодовой базе и ошибок.

## Обзор

Monogram создан для обеспечения нативного и бесшовного опыта использования Telegram. Работающий на базе официальной библиотеки **TDLib**, он отличается плавным интерфейсом **Material Design 3** и следует строгим принципам **Clean Architecture** и **MVI**.

## Технологический стек

* **Архитектура и состояние:** MVI, [Decompose](https://github.com/arkivanov/Decompose) (навигация и жизненный цикл), Koin (DI).
* **UI:** Jetpack Compose + Material 3 Adaptive (бесшовная масштабируемость от телефона до планшета).
* **Асинхронность:** Kotlin Coroutines и Flow.
* **Медиа:** Media3/ExoPlayer (воспроизведение), Coil 3 (GIF/SVG), Lottie (анимации).
* **Камера и ML:** CameraX + ML Kit Vision (молниеносное сканирование QR/штрих-кодов).
* **Карты:** OSMDroid (рендеринг карт с открытым исходным кодом).
* **Безопасность:** Biometric Compose (блокировка приложения), Security Crypto (безопасное локальное хранение данных).

## Начало работы

**1. Клонируйте репозиторий**
```bash
git clone https://github.com/monogram-android/monogram.git
```

**2. Настройте API ключи**
Создайте файл `local.properties` в корне проекта и добавьте ваши учетные данные Telegram API (вы можете получить их на [my.telegram.org](https://my.telegram.org/)):

```properties
API_ID=your_api_id
API_HASH=your_api_hash
```

**3. Сборка и запуск**
Откройте проект в **Android Studio**, дождитесь синхронизации Gradle и запускайте!

### Руководство по участию в разработке

*   **Соблюдайте условия использования Telegram:** Monogram — это неофициальный клиент. Мы строго придерживаемся [Условий использования Telegram API](https://core.telegram.org/api/terms). Изменения, способствующие спаму, несанкционированному сбору данных или любым другим нарушениям условий Telegram, будут отклонены.
*   **Архитектура и паттерны:** Поддерживайте **Clean Architecture** и поток **MVI** проекта. Убедитесь, что бизнес-логика находится в модуле `domain`, обработка данных — в `data`, а логика UI — в `presentation`.
*   **Современная разработка под Android:** Используйте компоненты Jetpack Compose и Material 3. Убедитесь, что изменения UI адаптивны и используют Material 3 Adaptive для различных форм-факторов (телефоны, планшеты, складные устройства).
*   **Стиль кода:** Пишите чистый, идиоматичный Kotlin. Следуйте соглашениям о форматировании и именовании существующей кодовой базе.
*   **Тестирование:** Проверяйте свои изменения на различных конфигурациях устройств. Убедитесь, что новые функции не нарушают существующую функциональность или производительность.
