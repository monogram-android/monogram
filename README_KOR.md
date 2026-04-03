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

**다른 언어로 읽기:** [English](README.md), [Русский](README_RU.md), [Español](README_ES.md)

---

**MonoGram**은 빠르고 우아한 최신 비공식 안드로이드용 텔레그램 클라이언트입니다. **Jetpack Compose**와 **Material Design 3**로 제작되었으며, 공식 **TDLib**을 기반으로 네이티브하고 부드러운 사용자 경험을 제공합니다.

> [!IMPORTANT]
> MonoGram은 현재 **활발히 개발 중**입니다. 잦은 업데이트, 아키텍처 변경 및 간헐적인 버그가 발생할 수 있습니다.

[**Boosty**](https://boosty.to/monogram)에서 프로젝트를 후원해 주세요.

---

## 스크린샷

<div align="center">

| | | | |
|:---:|:---:|:---:|:---:|
| <img src="./documents/1.png" width="180" alt="Screenshot 1" /> | <img src="./documents/2.png" width="180" alt="Screenshot 2" /> | <img src="./documents/3.png" width="180" alt="Screenshot 3" /> | <img src="./documents/4.png" width="180" alt="Screenshot 4" /> |

</div>

---

## 주요 기능

- **Material Design 3** — 스마트폰, 태블릿, 폴더블 기기에서 모두 멋지게 보이는 아름답고 적응형인 UI입니다.
- **보안** — 생체 인식 잠금 및 암호화된 로컬 저장소가 내장되어 있습니다.
- **풍부한 미디어** — ExoPlayer와 Coil 3를 사용한 고성능 미디어 재생을 지원합니다.
- **빠르고 효율적임** — Kotlin Coroutines를 기반으로 성능이 최적화되었습니다.
- **클린 아키텍처** — 도메인(Domain), 데이터(Data), 프레젠테이션(Presentation) 계층으로 관심사를 명확히 분리했습니다.
- **MVI 패턴** — MVIKotlin을 사용한 예측 가능한 상태 관리를 제공합니다.
- **NFT 및 암호화폐 없음** — MonoGram은 메시징 앱의 범위를 벗어난다고 판단되는 NFT 프로모션, 선물 등 텔레그램에서 추진하는 기능을 절대 포함하지 않습니다.

---

## 시작하기

로컬 환경에서 프로젝트를 설정하려면 다음 단계를 따르세요.

### 사전 요구 사항

- **Android Studio**: Ladybug 이상 (권장).
- **JDK**: Java 17 이상.

### 1. 저장소 클론

```bash
git clone https://github.com/monogram-android/monogram.git
cd monogram
```

### 2. 텔레그램 API 키 설정

텔레그램 서버에 연결하려면 고유한 API 자격 증명이 필요합니다.

1. [my.telegram.org](https://my.telegram.org/)에 로그인합니다.
2. **API development tools**로 이동합니다.
3. 새 애플리케이션을 생성하여 `App api_id`와 `App api_hash`를 얻습니다.
4. 프로젝트 루트 디렉터리에 `local.properties` 파일을 생성합니다(없는 경우).
5. 다음 내용을 추가합니다:

```properties
API_ID=12345678
API_HASH=your_api_hash_here
```

### 3. 푸시 알림 설정

1. [Firebase Console](https://console.firebase.google.com)에 로그인합니다.
2. 새 프로젝트를 생성합니다.
3. 필요한 `applicationId`로 새 애플리케이션을 추가합니다. 다른 ID를 가진 여러 애플리케이션이 있는 경우 여러 Firebase 애플리케이션을 생성하세요. **기본적으로 디버그 빌드와 릴리스 빌드의 `applicationId`는 다릅니다.**
4. `google-services.json` 파일을 다운로드하여 **app** 모듈의 루트(`monogram/app/google-services.json`)에 복사합니다. 여러 애플리케이션을 생성한 경우 가장 최근의 구성 파일만 복사하세요.
5. **Cloud Messaging** 섹션으로 이동합니다.
6. **서비스 계정 관리(Manage service accounts)**를 클릭합니다.
7. 열린 창 상단에서 **키(Keys)** 섹션을 선택합니다.
8. **키 추가(Add key)**를 클릭하고 **JSON** 옵션을 선택합니다. 파일이 다운로드될 때까지 기다립니다.
9. App ID를 받았던 텔레그램 API 페이지로 돌아갑니다.
10. FCM 자격 증명 섹션 옆의 **Update**를 클릭합니다.
11. 열린 페이지에서 다운로드한 서비스 계정 JSON 파일을 업로드합니다.

### 4. 빌드 및 실행

1. **Android Studio**에서 프로젝트를 엽니다.
2. `TdApi.java`(TDLib 래퍼)가 올바르게 인덱싱되도록 IDE 인덱싱 제한을 늘립니다. **Android Studio** 또는 **IntelliJ IDEA**에서 **Help → Edit Custom Properties...**를 열고 아래 줄을 붙여넣은 후, 메시지가 나타나면 IDE를 다시 시작합니다:

```properties
# size in Kb
idea.max.intellisense.filesize=20480
# size in Kb
idea.max.content.load.filesize=20480
```

3. Gradle을 동기화합니다.
4. `app` 실행 구성을 선택합니다.
5. 기기를 연결하거나 에뮬레이터를 시작합니다.
6. **Run**(실행)을 클릭합니다.

---

## TDLib 빌드하기

소스에서 TDLib을 직접 빌드해야 하는 경우 먼저 필요한 종속성을 설치하세요. Debian/Ubuntu 기반 배포판의 경우 다음과 같습니다:

```bash
sudo apt-get update
sudo apt-get install build-essential git curl wget php perl gperf unzip zip default-jdk cmake
```

그런 다음 프로젝트 루트에서 빌드 스크립트를 실행합니다:

```bash
./build-tdlib.sh
```

---

## 기여하기

우리는 여러분의 기여를 환영합니다! 버그 수정, 문서 개선, 새로운 기능 제안 등 무엇이든 좋습니다.

1. **이슈 확인** — 열려 있는 이슈를 찾아보거나 새로운 이슈를 생성하여 아이디어를 논의하세요.
2. **`develop` 브랜치에서 작업** — `develop` 브랜치에서 새 브랜치를 생성하고 해당 브랜치를 기반으로 작업하세요.
3. **포크 및 브랜치** — 저장소를 포크하고 기능 브랜치를 생성하세요.
4. **코드 스타일** — 기존 Kotlin 코딩 스타일과 클린 아키텍처 가이드라인을 준수하세요.
5. **PR 제출** — 변경 사항에 대한 명확한 설명과 함께 `develop` 브랜치로 Pull Request를 여세요.

> [!IMPORTANT]
> - [텔레그램 API 서비스 약관](https://core.telegram.org/api/terms)을 준수하세요.
> - 작성한 코드가 모든 검사와 테스트를 통과하는지 확인하세요.

### 버그 신고 및 기능 제안

- **버그** — 이슈를 열고 제목에 `[Bug]` 태그를 사용하세요(예: `[Bug] 앱 시작 시 크래시 발생`). 또한 [**버그 트래커**](https://github.com/orgs/monogram-android/projects/3/views/1)에서 알려진 모든 버그를 확인할 수 있습니다.
- **기능 요청** — `[Feature]` 태그를 사용하여 이슈를 여세요(예: `[Feature] 예약 메시지 지원`). 기존 기능 요청은 [**기능 보드**](https://github.com/orgs/monogram-android/projects/5/views/1)에서 확인할 수 있습니다.

---

## 번역

MonoGram은 커뮤니티 번역을 환영합니다! 문자열 리소스 파일을 수정하여 원하는 언어 번역에 기여할 수 있습니다.

원본 문자열은 [`presentation/src/main/res/values/string.xml`](https://github.com/monogram-android/monogram/blob/develop/presentation/src/main/res/values/string.xml)에 있습니다. 새로운 언어를 추가하려면 해당하는 `values-<locale>/string.xml` 파일(예: 독일어의 경우 `values-de/string.xml`)을 만들고 해당 파일에서 문자열을 번역하세요. 번역이 포함된 PR을 열어주시면 병합하겠습니다.

---

## 기술 스택

MonoGram은 최신 안드로이드 개발 도구와 라이브러리를 활용합니다:

| 카테고리 | 라이브러리 |
|:---|:---|
| **언어** | [Kotlin](https://kotlinlang.org/) |
| **UI 툴킷** | [Jetpack Compose](https://developer.android.com/jetpack/compose) (Material 3) |
| **아키텍처** | [Decompose](https://github.com/arkivanov/Decompose) (Navigation), [MVIKotlin](https://github.com/arkivanov/MVIKotlin) |
| **의존성 주입 (DI)** | [Koin](https://insert-koin.io/) |
| **비동기** | Coroutines & Flow |
| **텔레그램 코어** | [TDLib](https://core.telegram.org/tdlib) (Telegram Database Library) |
| **이미지 로딩** | [Coil 3](https://coil-kt.github.io/coil/) |
| **미디어** | Media3 (ExoPlayer) |
| **지도** | [MapLibre](https://maplibre.org/) |
| **로컬 DB** | Room |

---

## 프로젝트 구조

이 프로젝트는 관심사 분리와 확장성을 보장하기 위해 멀티 모듈 구조를 따릅니다:

| 모듈 | 설명 |
|:---|:---|
| **:app** | 메인 안드로이드 애플리케이션 모듈입니다. |
| **:domain** | 비즈니스 로직, 유스케이스(Use cases), 리포지토리 인터페이스를 포함하는 순수 Kotlin 모듈입니다. |
| **:data** | 리포지토리, 데이터 소스, TDLib 통합의 구현체입니다. |
| **:presentation** | UI 컴포넌트, 화면(Screens), 뷰모델(MVI 스토어)입니다. |
| **:core** | 여러 모듈에서 공통으로 사용되는 유틸리티 클래스와 확장 함수(Extensions)입니다. |
| **:baselineprofile** | 앱 시작 시간 및 성능 최적화를 위한 베이스라인 프로파일(Baseline Profiles)입니다. |

---

## 라이선스

이 프로젝트는 [**GNU General Public License v3.0**](LICENSE) 라이선스에 따라 배포됩니다.
