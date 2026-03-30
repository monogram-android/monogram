# Monogram

**Читать на других языках:** [English](README.md)

![Kotlin](https://img.shields.io/badge/Kotlin-2.0+-blue.svg?logo=kotlin)
![Jetpack Compose](https://img.shields.io/badge/Jetpack_Compose-Material_3-4285F4?logo=android)
![TDLib](https://img.shields.io/badge/TDLib-1.8.62-blue)
![Status](https://img.shields.io/badge/Status-Active_Development-orange)
![Boosty](https://img.shields.io/badge/Boosty-Support_the_project-ff6f61?logo=boosty&logoColor=white)
![License](https://img.shields.io/badge/License-GPLv3-blue.svg)

**Monogram** — это современный, молниеносный и элегантный неофициальный клиент Telegram для Android. Созданный с
использованием **Jetpack Compose** и **Material Design 3**, он стремится обеспечить нативный и плавный опыт
использования, используя мощь официальной библиотеки **TDLib**.

> **Примечание:** Monogram находится в стадии **активной разработки**. Ожидайте частых обновлений, архитектурных
> изменений и возможных ошибок.

Поддержать проект на [Boosty](https://boosty.to/monogram).

---

## Ключевые особенности

* **Material Design 3**: Красивый, адаптивный интерфейс, который отлично смотрится на телефонах, планшетах и складных
  устройствах.
* **Clean Architecture**: Разделение ответственности с помощью слоев Domain, Data и Presentation.
* **Паттерн MVI**: Предсказуемое управление состоянием с использованием MVIKotlin.
* **Безопасность**: Встроенная биометрическая блокировка и зашифрованное локальное хранилище.
* **Мультимедиа**: Высокопроизводительное воспроизведение медиа с ExoPlayer и Coil 3.
* **Быстродействие**: Работает на Kotlin Coroutines и оптимизирован для производительности.

---

## Технологический стек

Monogram использует новейшие инструменты и библиотеки для разработки под Android:

| Категория                  | Библиотеки                                                                                                           |
|:---------------------------|:---------------------------------------------------------------------------------------------------------------------|
| **Язык**                   | [Kotlin](https://kotlinlang.org/)                                                                                    |
| **UI Toolkit**             | [Jetpack Compose](https://developer.android.com/jetpack/compose) (Material 3)                                        |
| **Архитектура**            | [Decompose](https://github.com/arkivanov/Decompose) (Навигация), [MVIKotlin](https://github.com/arkivanov/MVIKotlin) |
| **Внедрение зависимостей** | [Koin](https://insert-koin.io/)                                                                                      |
| **Асинхронность**          | Coroutines & Flow                                                                                                    |
| **Ядро Telegram**          | [TDLib](https://core.telegram.org/tdlib) (Telegram Database Library)                                                 |
| **Загрузка изображений**   | [Coil 3](https://coil-kt.github.io/coil/)                                                                            |
| **Медиа**                  | Media3 (ExoPlayer)                                                                                                   |
| **Карты**                  | [MapLibre](https://maplibre.org/)                                                                                    |
| **Локальная БД**           | Room                                                                                                                 |

---

## Структура проекта

Проект следует многомодульной структуре для обеспечения разделения ответственности и масштабируемости:

* **:app** - Основной модуль приложения Android.
* **:domain** - Чистый Kotlin модуль, содержащий бизнес-логику, use cases и интерфейсы репозиториев.
* **:data** - Реализация репозиториев, источников данных и интеграция с TDLib.
* **:presentation** - UI компоненты, экраны и view models (MVI Stores).
* **:core** - Общие служебные классы и расширения, используемые во всех модулях.
* **:baselineprofile** - Baseline Profiles для оптимизации запуска и производительности приложения.

---

## Начало работы

Следуйте этим шагам, чтобы настроить проект локально.

### Предварительные требования

* **Android Studio**: Ladybug или новее (рекомендуется).
* **JDK**: Java 17 или новее.

### 1. Клонирование репозитория
```bash
git clone https://github.com/monogram-android/monogram.git
cd monogram
```

### 2. Настройка API ключей Telegram

Для подключения к серверам Telegram вам понадобятся собственные учетные данные API.

1. Войдите на [my.telegram.org](https://my.telegram.org/).
2. Перейдите в **API development tools**.
3. Создайте новое приложение, чтобы получить `App api_id` и `App api_hash`.
4. Создайте файл с именем `local.properties` в корневой директории проекта (если он не существует).
5. Добавьте следующие строки:

```properties
API_ID=12345678
API_HASH=your_api_hash_here
```
### 3. Как настроить работу пушей

1. Войдите в [консоль Firebase](https://console.firebase.google.com)
2. Создайте новый проект.
3. Добавьте туда новое приложение с нужным вам applicationId (если в проекте есть несколько варинатов с разными ID, то нужно будет создать несколько приложений). **По умолчанию applicationId для debug и release сборок отличаются!**
4. Консоль создаст файл `google-services.json`, скопируйте его в корень модуля **app** (`monogram/app/google-services.json`). Если вы создали несколько приложений, то скопируйте только самый последний конфиг.
5. Перейдите в раздел "Cloud Messaging".
6. Нажмите на ссылку **Manage service accounts**.
7. В открывшемся окне выберите верхний раздел **Keys**.
8. Нажмите на кнопку **Add key**, и в диалоге выберите вариант **JSON**, дождитесь скачивания файла на компьютер.
9. Вернитесь на страницу, на которой вы получали App ID от Telegram.
10. Нажмите на кнопку Update рядом с разделом FCM credentials.
11. Загрузите JSON сервисного аккаунта в открывшейся странице.


### 4. Сборка и запуск

1. Откройте проект в **Android Studio**.
2. Увеличьте лимиты индексации IDE, чтобы `TdApi.java` (обертка над TDLib) корректно индексировался:

```properties
# custom IntelliJ IDEA properties (expand/override 'bin\idea.properties')

# size in Kb
idea.max.intellisense.filesize=20480
# size in Kb
idea.max.content.load.filesize=20480
```

3. В **Android Studio** или **IntelliJ IDEA** откройте **Help -> Edit Custom Properties...**, вставьте строки выше и при необходимости перезапустите IDE.
4. Синхронизируйте Gradle.
5. Выберите конфигурацию запуска `app`.
6. Подключите устройство или запустите эмулятор.
7. Нажмите **Run**.

---

## Building TDLib

Если вам необходимо собрать TDLib из исходного кода, сначала нужно установить необходимые зависимости в вашей системе. Для дистрибутивов на основе Debian/Ubuntu это можно сделать, выполнив команду:
```bash
sudo apt-get update
sudo apt-get install build-essential git curl wget php perl gperf unzip zip default-jdk cmake
```

После установки зависимостей вы можете запустить процесс сборки, выполнив скрипт сборки из корневой директории вашего проекта:
```bash
./build-tdlib.sh
```

---
## Участие в разработке

Мы приветствуем вклад в развитие проекта! Будь то исправление ошибок, улучшение документации или предложение новых
функций.

1. **Проверьте Issues**: Посмотрите открытые задачи или создайте новую для обсуждения ваших идей.
2. **Работайте от `develop`**: Создавайте свои ветки от `develop` и держите все изменения на базе этой ветки.
3. **Fork & Branch**: Сделайте форк репозитория и создайте ветку для вашей функции.
4. **Стиль кода**: Пожалуйста, следуйте существующему стилю кода Kotlin и принципам Clean Architecture.
5. **Отправьте PR**: Откройте Pull Request в `develop` с четким описанием ваших изменений.

**Важно**:

* Соблюдайте [Условия использования Telegram API](https://core.telegram.org/api/terms).
* Убедитесь, что ваш код проходит все проверки и тесты.

---

## Лицензия

Этот проект распространяется под лицензией GNU General Public License v3.0. Полный текст см. в `LICENSE`.
