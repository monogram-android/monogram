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

<p align="center"><strong>Monogram</strong>은 Android 7.0 이상을 위한 매우 빠른 완전 네이티브 Telegram 클라이언트입니다. <strong>Kotlin</strong>, <strong>Jetpack Compose</strong>, <strong>Material 3</strong>로 만든 현대적인 Android 인터페이스와 <strong>Rust로 직접 구현한 MTProto</strong>를 결합했습니다</p>

> [!IMPORTANT]
> Monogram은 **활발히 개발 중**입니다. 기능과 아키텍처가 계속 바뀌고 있으며, 버그나 미완성 기능이 있을 수 있습니다.

[Boosty](https://boosty.to/monogram)에서 프로젝트를 후원할 수 있습니다.

## 스크린샷

<div align="center">

| | | | |
|:---:|:---:|:---:|:---:|
| <img src="./documents/1.png" width="180" alt="Monogram 1" /> | <img src="./documents/2.png" width="180" alt="Monogram 2" /> | <img src="./documents/3.png" width="180" alt="Monogram 3" /> | <img src="./documents/4.png" width="180" alt="Monogram 4" /> |

</div>

## 프로젝트 특징

- **완전 네이티브** — Kotlin과 Jetpack Compose로 Android에 맞게 개발
- **화면에 맞는 디자인** — 휴대전화, 태블릿, 큰 화면에 맞춰 조정되는 Material 3 레이아웃
- **채팅 속 미디어** — 대화에서 즐기는 사진, 동영상, 움직이는 스티커
- **Rust로 구현한 놀라운 속도** — Telegram의 MTProto 프로토콜을 자체 네이티브 코드로 구현 🚀
- **NFT 및 암호화폐 제외** — Monogram에는 NFT 홍보, 선물 및 메시징 앱의 범위를 벗어난다고 판단하는 Telegram 기능을 포함하지 않습니다

## 소스에서 빌드하기

### 1. 요구 사항

- **JDK 17** 및 프로젝트의 Android Gradle Plugin과 호환되는 Android Studio 버전([버전 카탈로그](gradle/libs.versions.toml) 참고)
- **Android SDK Platform 37**, platform tools, **Android NDK**. CI는 **NDK r28c**를 사용합니다. NDK를 직접 선택하려면 `ANDROID_NDK_HOME`을 설정하세요. 설정하지 않으면 Gradle이 SDK의 `ndk` 디렉터리에서 찾습니다.
- **Rust 1.98 이상**, Cargo, 네이티브 의존성 빌드와 UniFFI 생성을 위한 호스트 시스템의 C/C++ 빌드 도구
- **Git**, 아래 Android Rust 타깃 및 **cargo-ndk**:

```console
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
cargo install cargo-ndk --locked
```

### 2. 저장소 복제

```console
git clone --recurse-submodules https://github.com/monogram-android/monogram.git
git clone --recurse-submodules https://github.com/gdlbo/telers-mtproto-impl.git
cd monogram
git submodule update --init --recursive
```

같은 상위 디렉터리에서 두 clone 명령을 실행하세요. 현재 Cargo 매니페스트는 Monogram 디렉터리 옆의 `../telers-mtproto-impl`을 요구합니다. `vendor`의 서브모듈은 이 별도 체크아웃을 대체하지 않습니다. CI도 같은 디렉터리 구성을 사용합니다.

### 3. 로컬 설정

[local.properties.example](local.properties.example)을 `local.properties`로 복사한 뒤 `sdk.dir`, `API_ID`, `API_HASH`를 설정하세요.

Unix 계열 시스템:

```sh
cp local.properties.example local.properties
```

Windows (PowerShell):

```powershell
Copy-Item local.properties.example local.properties
```

API 자격 증명은 [my.telegram.org/apps](https://my.telegram.org/apps)에서 발급받으세요. 자격 증명 없이도 빌드는 가능하지만 Telegram 로그인에는 유효한 값이 필요합니다. `local.properties`, 서명 키, 서비스 계정 자격 증명은 버전 관리 대상에서 제외하고 로그나 커밋에 포함하지 마세요.

릴리스에 자체 서명 키를 사용하려면 다음 값도 설정하세요:

```properties
RELEASE_STORE_FILE=keystore/release.jks
RELEASE_STORE_PASSWORD=your_store_password
RELEASE_KEY_ALIAS=your_key_alias
RELEASE_KEY_PASSWORD=your_key_password
```

`RELEASE_STORE_FILE`이 없으면 release와 beta 빌드는 기본적으로 디버그 키로 서명됩니다. 릴리스 키가 설정되지 않은 상태에서 `-Punsigned=true`를 사용하면 이 대체 서명을 비활성화합니다. 디버그 서명 빌드는 개발용이며 실제 배포용 서명을 대신하지 않습니다.

### 4. 푸시 알림 설정 (선택 사항)

- **FCM:** Firebase에 `org.monogram`을 사용하는 Android 앱을 등록하세요(debug, release, beta 모두 같은 애플리케이션 ID 사용). 설정 파일을 `app/google-services.json`에 넣으세요. Gradle은 이 파일이 있을 때만 Google Services 플러그인을 적용합니다. 설정 파일이 없어도 Firebase 의존성은 앱에 포함됩니다.
- [my.telegram.org/apps](https://my.telegram.org/apps)에서 Telegram API ID에 해당 FCM 자격 증명을 등록하세요. `google-services.json`과 모든 `firebase-adminsdk` JSON 파일은 버전 관리에서 제외하고, 서비스 계정 키를 APK에 절대 포함하지 마세요.
- FCM 테스트에는 Google Play 서비스가 필요합니다. 에뮬레이터에서는 Google Play 이미지를 사용하세요.
- **UnifiedPush:** 기기에 호환되는 배포자 앱을 설치하고 설정하세요. 앱은 UnifiedPush 등록을 지원합니다.

### 5. 빌드 및 실행

Android Studio에서 저장소를 열고 Gradle을 동기화한 뒤 `app` 실행 구성을 선택하거나, 저장소 루트에서 아래 명령을 실행하세요. `installDebug`에는 연결된 기기 또는 실행 중인 에뮬레이터가 필요합니다.

Unix 계열 시스템:

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

- `debug`: 개발용 빌드
- `release`: R8으로 최적화한 빌드
- `beta`: 디버깅을 쉽게 하도록 R8을 비활성화한 release 기반 빌드


### 네이티브 컴파일

일반 빌드 과정에서 Gradle은 `armeabi-v7a`, `arm64-v8a`, `x86_64`용 Rust 라이브러리를 빌드하고 호스트 디버그 라이브러리에서 UniFFI Kotlin 바인딩을 다시 생성합니다. 비디오 스티커용 사전 빌드 libvpx 라이브러리는 `native/vpx/prebuilt`에 포함되어 있습니다. Android 네이티브 컴파일 작업을 직접 실행하려면:

```sh
./gradlew :native:mtproto:buildNativeMtproto :native:markup:buildNativeMarkup
```

Windows에서는 `./gradlew` 대신 `./gradlew.bat`를 사용하세요. `-PskipNativeBuild=true`는 호환되는 네이티브 라이브러리와 생성된 바인딩이 이미 있는 상태에서 Kotlin만 변경할 때 사용하세요. 네이티브 컴파일과 바인딩 재생성을 모두 건너뛰지만 SDK/NDK 설정은 여전히 필요합니다.

## 기술 스택

- **언어 및 프로토콜:** Kotlin, Rust, MTProto, UniFFI
- **UI 및 상태:** Jetpack Compose, Material 3, Decompose, MVIKotlin
- **공통 서비스:** Koin, Coroutines, Flow, Room
- **미디어 및 푸시:** Media3, Coil, libvpx, tlottie, Firebase Cloud Messaging, UnifiedPush

## 프로젝트 구조

| 경로 | 역할 |
|:---|:---|
| `app` | 앱 진입점, 의존성 주입, 탐색 및 푸시 통합 |
| `core/*` | 공통 모델, 데이터베이스, UI, 유틸리티 및 마크업 처리 접근 |
| `feature/*` | 인증, 채팅 목록, 대화, 폴더, 프로필 및 설정 |
| `network/bridge` | Kotlin 클라이언트 API, 도메인 매핑 및 오류 처리 |
| `network/http` | HTTP 미디어 다운로드, 캐시 및 대기열 |
| `native/mtproto-rs` | Rust 프로토콜 클라이언트 및 UniFFI 내보내기 |
| `native/markup-rs` | Rust 기반 Markdown 처리, 구문 강조 및 수식 파싱 |
| `native/mtproto`, `native/markup` | Kotlin 파사드, 생성된 바인딩 및 네이티브 라이브러리 |
| `native/vpx` | 비디오 스티커용 사전 빌드 VP9 디코더 |
| `vendor` | 외부 소스 코드 및 서브모듈 |

feature 모듈은 `network/bridge`를 호출하며, 네이티브 프로토콜 로직은 Rust에 있습니다. feature 모듈은 생성된 UniFFI 타입을 직접 가져오거나 다른 feature 모듈에 의존하면 안 됩니다. 개발 규칙과 프로토콜 불변 조건은 [AGENTS.md](AGENTS.md)를 참고하세요.

## 기여하기

`develop`에서 브랜치를 만들고 `develop`을 대상으로 pull request를 제출하세요. 기존 아키텍처와 코드 스타일을 따르고, 변경 범위를 작업에 한정하며, 명확한 설명과 관련 검증 결과를 포함하세요. [Telegram API 이용 약관](https://core.telegram.org/api/terms)을 준수하세요.

변경한 모듈에 적합한 검사를 실행하세요. 예:

```sh
./gradlew :network:bridge:test
cargo test --manifest-path native/mtproto-rs/Cargo.toml
cargo test --manifest-path native/markup-rs/Cargo.toml
git diff --check
```

Windows에서는 `./gradlew.bat`를 사용하세요. 변경 사항과 관련된 검사만 실행하세요. 실제 Telegram 연동과 기기 동작은 본인의 자격 증명으로 별도 검증해야 합니다.

버그 신고는 이슈 제목에 `[Bug]`, 기능 제안은 `[Feature]`를 사용하세요. [버그 추적 보드](https://github.com/orgs/monogram-android/projects/3/views/1)와 [기능 보드](https://github.com/orgs/monogram-android/projects/5/views/1)를 참고하세요.

## 번역

UI 문자열은 `core/ui/src/main/res/values/strings.xml` 및 `feature/*/src/main/res/values/strings.xml` 파일에 있습니다. 관련 모듈마다 `values-<locale>/strings.xml`을 추가하거나 수정하세요(예: `values-de/strings.xml`). 리소스 이름, 서식 자리표시자, 복수형을 유지하세요. 번역은 pull request로 제출하고, 번역된 README는 영어 버전과 일치하도록 유지하세요.

## 라이선스

Monogram은 [GNU General Public License v3.0](LICENSE)으로 배포됩니다. 외부 구성 요소와 네이티브 크레이트에는 각 소스 디렉터리에 명시된 라이선스가 적용됩니다.
