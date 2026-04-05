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

**Читать на других языках:** [English](README.md), [한국어](README_KOR.md), [اُردو](README_UR.md),
[Español](README_ES.md)

---

**MonoGram** — это современный, молниеносный и элегантный неофициальный клиент Telegram для Android. Созданный с использованием **Jetpack Compose** и **Material Design 3**, он обеспечивает нативный и плавный опыт использования на базе официальной библиотеки **TDLib**.

> [!IMPORTANT]
> MonoGram находится в стадии **активной разработки**. Ожидайте частых обновлений, архитектурных изменений и возможных ошибок.

Поддержите наш проект на [**Boosty**](https://boosty.to/monogram).

---

## Скриншоты

<div align="center">

| | | | |
|:---:|:---:|:---:|:---:|
| <img src="./documents/1.png" width="180" alt="Скриншот 1" /> | <img src="./documents/2.png" width="180" alt="Скриншот 2" /> | <img src="./documents/3.png" width="180" alt="Скриншот 3" /> | <img src="./documents/4.png" width="180" alt="Скриншот 4" /> |

</div>

---

## Ключевые особенности

- **Material Design 3** — Красивый, адаптивный интерфейс, который отлично смотрится на телефонах, планшетах и складных устройствах.
- **Безопасность** — Встроенная биометрическая блокировка и зашифрованное локальное хранилище.
- **Мультимедиа** — Высокопроизводительное воспроизведение медиа с ExoPlayer и Coil 3.
- **Быстродействие** — Работает на Kotlin Coroutines и оптимизирован для производительности.
- **Clean Architecture** — Чёткое разделение ответственности с помощью слоёв Domain, Data и Presentation.
- **Паттерн MVI** — Предсказуемое управление состоянием с использованием MVIKotlin.
- **Без NFT и крипты** — MonoGram никогда не будет включать NFT, подарки и прочие функции, продвигаемые Telegram, которые мы считаем лишними в мессенджере.

---

## Начало работы

Следуйте этим шагам, чтобы настроить проект локально.

### Предварительные требования

- **Android Studio**: Ladybug или новее (рекомендуется).
- **JDK**: Java 17 или новее.

### 1. Клонирование репозитория

```bash
git clone --recurse-submodules https://github.com/monogram-android/monogram.git
cd monogram
```

### 2. Настройка API ключей Telegram

Для подключения к серверам Telegram вам понадобятся собственные учётные данные API.

1. Войдите на [my.telegram.org](https://my.telegram.org/).
2. Перейдите в **API development tools**.
3. Создайте новое приложение, чтобы получить `App api_id` и `App api_hash`.
4. Создайте файл `local.properties` в корневой директории проекта (если он не существует).
5. Добавьте следующие строки:

```properties
API_ID=12345678
API_HASH=your_api_hash_here
```

### 3. Настройка push-уведомлений

1. Войдите в [консоль Firebase](https://console.firebase.google.com).
2. Создайте новый проект.
3. Добавьте новое приложение с нужным `applicationId`. Если в проекте есть несколько вариантов с разными ID, создайте несколько приложений. **По умолчанию `applicationId` для debug и release сборок отличаются.**
4. Скачайте файл `google-services.json` и скопируйте его в корень модуля **app** (`monogram/app/google-services.json`). Если вы создали несколько приложений, скопируйте только самый последний конфиг.
5. Перейдите в раздел **Cloud Messaging**.
6. Нажмите **Manage service accounts**.
7. В открывшемся окне выберите раздел **Keys** вверху.
8. Нажмите **Add key** и выберите вариант **JSON**. Дождитесь скачивания файла.
9. Вернитесь на страницу, где вы получали App ID от Telegram.
10. Нажмите **Update** рядом с разделом FCM credentials.
11. Загрузите JSON сервисного аккаунта на открывшейся странице.

### 4. Первичная настройка: сборка libvpx

Для анимаций требуется собрать libvpx. Это нужно сделать до запуска сборки Gradle, иначе сборка завершится с ошибками.

1. Перейдите в директорию `presentation/src/main/cpp`
2. В `build.sh` укажите ваш `ANDROID_NDK_HOME`
3. Запустите `build.sh` и дождитесь завершения

### 5. Сборка и запуск

1. Откройте проект в **Android Studio**.
2. Увеличьте лимиты индексации IDE, чтобы `TdApi.java` (обёртка над TDLib) корректно индексировался. В **Android Studio** или **IntelliJ IDEA** откройте **Help → Edit Custom Properties...**, вставьте строки ниже и при необходимости перезапустите IDE:

```properties
# size in Kb
idea.max.intellisense.filesize=20480
# size in Kb
idea.max.content.load.filesize=20480
```

3. Синхронизируйте Gradle.
4. Выберите конфигурацию запуска `app`.
5. Подключите устройство или запустите эмулятор.
6. Нажмите **Run**.

---

## Сборка TDLib

Если вам необходимо собрать TDLib из исходного кода, сначала установите необходимые зависимости. Для дистрибутивов на основе Debian/Ubuntu:

```bash
sudo apt-get update
sudo apt-get install build-essential git curl wget php perl gperf unzip zip default-jdk cmake
```

Затем запустите скрипт сборки из корневой директории проекта:

```bash
./build-tdlib.sh
```

---

## Участие в разработке

Мы приветствуем любой вклад в развитие проекта — будь то исправление ошибок, улучшение документации или предложение новых функций.

1. **Проверьте Issues** — Посмотрите открытые задачи или создайте новую для обсуждения ваших идей.
2. **Работайте от `develop`** — Создавайте ветки от `develop` и держите все изменения на её базе.
3. **Fork & Branch** — Сделайте форк репозитория и создайте ветку для вашей функции.
4. **Стиль кода** — Следуйте существующему стилю кода Kotlin и принципам Clean Architecture.
5. **Отправьте PR** — Откройте Pull Request в `develop` с чётким описанием ваших изменений.

> [!IMPORTANT]
> - Соблюдайте [Условия использования Telegram API](https://core.telegram.org/api/terms).
> - Убедитесь, что ваш код проходит все проверки и тесты.

### Сообщения об ошибках и предложения

- **Ошибки** — Откройте issue и используйте тег `[Bug]` в заголовке (например, `[Bug] Приложение падает при запуске`). Список известных ошибок доступен на [**доске багов**](https://github.com/orgs/monogram-android/projects/3/views/1).
- **Запросы функций** — Откройте issue с тегом `[Feature]` (например, `[Feature] Поддержка отложенных сообщений`). Существующие запросы можно найти на [**доске функций**](https://github.com/orgs/monogram-android/projects/5/views/1).

---

## Переводы

MonoGram приветствует переводы от сообщества! Вы можете добавить свой язык, отредактировав файл строковых ресурсов.

Исходные строки находятся в [`presentation/src/main/res/values/string.xml`](https://github.com/monogram-android/monogram/blob/develop/presentation/src/main/res/values/string.xml). Чтобы добавить новый язык, создайте соответствующий файл `values-<locale>/string.xml` (например, `values-de/string.xml` для немецкого) и переведите строки. Откройте PR с вашим переводом — мы его рассмотрим и примем.

---

## Технологический стек

MonoGram использует новейшие инструменты и библиотеки для разработки под Android:

| Категория | Библиотеки |
|:---|:---|
| **Язык** | [Kotlin](https://kotlinlang.org/) |
| **UI Toolkit** | [Jetpack Compose](https://developer.android.com/jetpack/compose) (Material 3) |
| **Архитектура** | [Decompose](https://github.com/arkivanov/Decompose) (Навигация), [MVIKotlin](https://github.com/arkivanov/MVIKotlin) |
| **Внедрение зависимостей** | [Koin](https://insert-koin.io/) |
| **Асинхронность** | Coroutines & Flow |
| **Ядро Telegram** | [TDLib](https://core.telegram.org/tdlib) (Telegram Database Library) |
| **Загрузка изображений** | [Coil 3](https://coil-kt.github.io/coil/) |
| **Медиа** | Media3 (ExoPlayer) |
| **Карты** | [MapLibre](https://maplibre.org/) |
| **Локальная БД** | Room |

---

## Структура проекта

Проект следует многомодульной структуре для обеспечения разделения ответственности и масштабируемости:

| Модуль | Описание |
|:---|:---|
| **:app** | Основной модуль приложения Android. |
| **:domain** | Чистый Kotlin-модуль с бизнес-логикой, use cases и интерфейсами репозиториев. |
| **:data** | Реализация репозиториев, источников данных и интеграция с TDLib. |
| **:presentation** | UI-компоненты, экраны и view models (MVI Stores). |
| **:core** | Общие вспомогательные классы и расширения, используемые во всех модулях. |
| **:baselineprofile** | Baseline Profiles для оптимизации запуска и производительности приложения. |

---

## Лицензия

Этот проект распространяется под лицензией [**GNU General Public License v3.0**](LICENSE).
