<h1 align="center">
  <a href="https://github.com/monogram-android/monogram"><img width="130" height="130" alt="Monogram" src="./documents/monogram.png" /></a>
  <br />Monogram
</h1>

<p align="center">
  <a href="./LICENSE"><img alt="GPLv3" src="https://img.shields.io/badge/License-GPLv3-blue.svg" /></a>
  <a href="https://github.com/monogram-android/monogram/stargazers"><img alt="GitHub stars" src="https://img.shields.io/github/stars/monogram-android/monogram" /></a>
  <a href="https://deepwiki.com/monogram-android/monogram"><img alt="Ask DeepWiki" src="https://deepwiki.com/badge.svg" /></a>
  <img alt="Kotlin + Rust" src="https://img.shields.io/badge/Kotlin_+_Rust-MTProto-blue" />
  <a href="https://boosty.to/monogram"><img alt="Boosty" src="https://img.shields.io/badge/Boosty-Support-ff6f61" /></a>
</p>

<p align="center"><a href="README.md">English</a> · <a href="README_RU.md">Русский</a> · <a href="README_TR.md">Türkçe</a> · <a href="README_KOR.md">한국어</a> · <a href="README_UR.md">اُردو</a> · <a href="README_ES.md">Español</a></p>

<p align="center"><strong>Monogram</strong> — молниеносный, полностью нативный клиент Telegram для Android 7.0 и новее. <strong>Kotlin</strong>, <strong>Jetpack Compose</strong> и <strong>Material 3</strong> сочетают современный Android-интерфейс с <strong>нашей собственной реализацией MTProto на Rust</strong></p>

> [!IMPORTANT]
> Monogram находится в **активной разработке**. Возможности и архитектура продолжают меняться; возможны ошибки и неполная реализация отдельных функций.

Поддержать проект можно на [Boosty](https://boosty.to/monogram)

## Скриншоты

<div align="center">

| | | | |
|:---:|:---:|:---:|:---:|
| <img src="./documents/1.png" width="180" alt="Monogram 1" /> | <img src="./documents/2.png" width="180" alt="Monogram 2" /> | <img src="./documents/3.png" width="180" alt="Monogram 3" /> | <img src="./documents/4.png" width="180" alt="Monogram 4" /> |

</div>

## Особенности проекта

- **Полностью нативный** — Создан для Android на Kotlin и Jetpack Compose
- **Под ваш экран** — Интерфейс Material 3 адаптируется к телефонам, планшетам и большим экранам
- **Общение с медиа** — Фотографии, видео и анимированные стикеры в переписке
- **Молниеносный, благодаря Rust** — Собственная нативная реализация протокола Telegram MTProto 🚀
- **Без NFT и криптовалют** — Monogram не будет включать продвижение NFT, подарки и другие функции Telegram, которые мы считаем выходящими за рамки приложения для обмена сообщениями

## Сборка из исходников

### 1. Требования

- **JDK 17** и версия Android Studio, совместимая с Android Gradle Plugin проекта (см. [каталог версий](gradle/libs.versions.toml)).
- **Android SDK Platform 37**, platform tools и **Android NDK**. В CI используется **NDK r28c**. Для явного выбора NDK задайте `ANDROID_NDK_HOME`; иначе Gradle ищет его в каталоге `ndk` внутри SDK.
- **Rust 1.98 или новее**, Cargo и инструменты сборки C/C++ для вашей ОС, необходимые для нативных зависимостей и генерации UniFFI
- **Git**, перечисленные ниже целевые платформы Rust для Android и **cargo-ndk**:

```console
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
cargo install cargo-ndk --locked
```

### 2. Клонирование репозиториев

```console
git clone --recurse-submodules https://github.com/monogram-android/monogram.git
git clone --recurse-submodules https://github.com/gdlbo/telers-mtproto-impl.git
cd monogram
git submodule update --init --recursive
```

Выполните обе команды клонирования из одного родительского каталога. Текущие манифесты Cargo ожидают `../telers-mtproto-impl` рядом с каталогом Monogram; подмодуль в `vendor` не заменяет эту соседнюю копию. В CI используется такое же расположение.

### 3. Локальные настройки

Скопируйте [local.properties.example](local.properties.example) в `local.properties` и задайте `sdk.dir`, `API_ID` и `API_HASH`.

Unix-подобные системы:

```sh
cp local.properties.example local.properties
```

Windows (PowerShell):

```powershell
Copy-Item local.properties.example local.properties
```

Получите API-реквизиты на [my.telegram.org/apps](https://my.telegram.org/apps). Собрать приложение можно без них, но для входа в Telegram нужны действительные реквизиты. Не добавляйте `local.properties`, ключи подписи и реквизиты сервисных аккаунтов в систему контроля версий, логи или коммиты.

Чтобы подписывать релиз собственным ключом, также задайте:

```properties
RELEASE_STORE_FILE=keystore/release.jks
RELEASE_STORE_PASSWORD=your_store_password
RELEASE_KEY_ALIAS=your_key_alias
RELEASE_KEY_PASSWORD=your_key_password
```

Если `RELEASE_STORE_FILE` не задан, сборки release и beta по умолчанию подписываются отладочным ключом. Параметр `-Punsigned=true` отключает эту подстановку, если релизный ключ не настроен. Отладочная подпись предназначена для разработки и не заменяет ваш ключ для распространения приложения.

### 4. Push-уведомления (необязательно)

- **FCM:** Зарегистрируйте Android-приложение Firebase с идентификатором `org.monogram` (он одинаков для debug, release и beta). Поместите конфигурацию в `app/google-services.json`; Gradle подключает плагин Google Services только при наличии этого файла. Зависимости Firebase остаются в приложении и без файла конфигурации.
- Зарегистрируйте соответствующие реквизиты FCM для своего Telegram API ID на [my.telegram.org/apps](https://my.telegram.org/apps). Не добавляйте `google-services.json` и JSON-файлы `firebase-adminsdk` в систему контроля версий; никогда не включайте ключ сервисного аккаунта в APK.
- Для проверки FCM нужны сервисы Google Play; на эмуляторе используйте образ Google Play
- **UnifiedPush:** Установите и настройте совместимый дистрибьютор на устройстве. Приложение поддерживает регистрацию UnifiedPush.

### 5. Сборка и запуск

Откройте репозиторий в Android Studio, синхронизируйте Gradle и выберите конфигурацию запуска `app` либо выполните команды ниже из корня репозитория. Для `installDebug` нужно подключённое устройство или запущенный эмулятор.

Unix-подобные системы:

```sh
./gradlew :app:assembleDebug
./gradlew :app:installDebug
./gradlew :app:assembleRelease
./gradlew :app:assembleBeta
```

Windows (PowerShell):

```powershell
./gradlew.bat :app:assembleDebug
./gradlew.bat :app:installDebug
./gradlew.bat :app:assembleRelease
./gradlew.bat :app:assembleBeta
```

- `debug`: сборка для разработки
- `release`: оптимизированная сборка с R8
- `beta`: сборка на основе release с отключённым R8 для удобства отладки


### Нативная сборка

При обычной сборке Gradle собирает библиотеки Rust для `armeabi-v7a`, `arm64-v8a` и `x86_64` и генерирует Kotlin-привязки UniFFI из отладочных библиотек для хост-системы. Готовые библиотеки libvpx для видеостикеров находятся в `native/vpx/prebuilt`. Прямой запуск задач нативной сборки для Android:

```sh
./gradlew :native:mtproto:buildNativeMtproto :native:markup:buildNativeMarkup
```

В Windows замените `./gradlew` на `./gradlew.bat`. Используйте `-PskipNativeBuild=true` только для изменений в Kotlin, когда уже есть совместимые нативные библиотеки и сгенерированные привязки. Параметр пропускает и нативную компиляцию, и генерацию привязок; настройка SDK/NDK всё равно обязательна.

## Технологии

- **Языки и протокол:** Kotlin, Rust, MTProto, UniFFI
- **Интерфейс и состояние:** Jetpack Compose, Material 3, Decompose, MVIKotlin
- **Общие сервисы:** Koin, Coroutines, Flow, Room
- **Медиа и уведомления:** Media3, Coil, libvpx, tlottie, Firebase Cloud Messaging, UnifiedPush

## Структура проекта

| Путь | Назначение |
|:---|:---|
| `app` | Точка входа, внедрение зависимостей, навигация и push-уведомления |
| `core/*` | Общие модели, база данных, интерфейс, утилиты и доступ к обработке разметки |
| `feature/*` | Авторизация, список чатов, переписка, папки, профили и настройки |
| `network/bridge` | API клиента для Kotlin, преобразование моделей и обработка ошибок |
| `network/http` | Загрузка медиа по HTTP, кеш и очередь |
| `native/mtproto-rs` | Протокольный клиент на Rust и экспорты UniFFI |
| `native/markup-rs` | Обработка Markdown, подсветки синтаксиса и формул на Rust |
| `native/mtproto`, `native/markup` | Фасады Kotlin, сгенерированные привязки и нативные библиотеки |
| `native/vpx` | Готовый декодер VP9 для видеостикеров |
| `vendor` | Сторонние исходники и подмодули |

Модули feature обращаются к `network/bridge`; нативная протокольная логика находится в Rust. Модули feature не должны импортировать сгенерированные типы UniFFI или зависеть друг от друга. Правила разработки и протокольные инварианты описаны в [AGENTS.md](AGENTS.md).

## Участие в разработке

Создавайте ветку от `develop` и направляйте pull request в `develop`. Соблюдайте существующую архитектуру и стиль кода, ограничивайте изменения задачей и прикладывайте понятное описание с результатами подходящих проверок. Соблюдайте [условия использования Telegram API](https://core.telegram.org/api/terms).

Запускайте проверки, относящиеся к изменённому модулю. Примеры:

```sh
./gradlew :network:bridge:test
cargo test --manifest-path native/mtproto-rs/Cargo.toml
cargo test --manifest-path native/markup-rs/Cargo.toml
git diff --check
```

В Windows используйте `./gradlew.bat`. Запускайте только проверки, относящиеся к изменению; работу с реальным Telegram и поведение на устройстве проверяйте отдельно со своими реквизитами.

Для ошибок используйте префикс `[Bug]`, для предложений — `[Feature]` в заголовке issue. См. [трекер ошибок](https://github.com/orgs/monogram-android/projects/3/views/1) и [доску предложений](https://github.com/orgs/monogram-android/projects/5/views/1).

## Переводы

Строки интерфейса находятся в `core/ui/src/main/res/values/strings.xml` и файлах `feature/*/src/main/res/values/strings.xml`. Добавляйте или обновляйте `values-<locale>/strings.xml` в соответствующих модулях (например, `values-de/strings.xml`). Сохраняйте имена ресурсов, параметры форматирования и формы множественного числа. Отправляйте переводы через pull request. Синхронизируйте переводы README с английской версией.

## Лицензия

Monogram распространяется под [GNU General Public License v3.0](LICENSE). Для сторонних компонентов и нативных крейтов действуют лицензии, указанные в соответствующих каталогах исходников.
