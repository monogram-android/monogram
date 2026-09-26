<h1 align="center">
  <a href="https://github.com/monogram-android/monogram"><img width="130" height="130" alt="Monogram" src="./documents/monogram.png" /></a>
  <br />Monogram
</h1>

<p align="center">
  <a href="./LICENSE"><img alt="GPLv3" src="https://img.shields.io/badge/License-GPLv3-blue.svg" /></a>
  <a href="https://github.com/monogram-android/monogram/stargazers"><img alt="GitHub stars" src="https://img.shields.io/github/stars/monogram-android/monogram" /></a>
  <img alt="Kotlin + Rust" src="https://img.shields.io/badge/Kotlin_+_Rust-MTProto-blue" />
  <a href="https://boosty.to/monogram"><img alt="Boosty" src="https://img.shields.io/badge/Boosty-Support-ff6f61" /></a>
</p>

<p align="center"><a href="README.md">English</a> · <a href="README_RU.md">Русский</a> · <a href="README_TR.md">Türkçe</a> · <a href="README_KOR.md">한국어</a> · <a href="README_UR.md">اُردو</a> · <a href="README_ES.md">Español</a></p>

<p align="center"><strong>Monogram</strong> es un cliente de Telegram rapidísimo y completamente nativo para Android 7.0 y versiones posteriores. Desarrollado con <strong>Kotlin</strong>, <strong>Jetpack Compose</strong> y <strong>Material 3</strong>, combina una interfaz Android moderna con <strong>nuestra propia implementación de MTProto en Rust</strong></p>

> [!IMPORTANT]
> Monogram está en **desarrollo activo**. Las funciones y la arquitectura siguen evolucionando; puede haber errores y funciones incompletas.

Apoya el proyecto en [Boosty](https://boosty.to/monogram)

## Capturas de pantalla

<div align="center">

| | | | |
|:---:|:---:|:---:|:---:|
| <img src="./documents/1.png" width="180" alt="Monogram 1" /> | <img src="./documents/2.png" width="180" alt="Monogram 2" /> | <img src="./documents/3.png" width="180" alt="Monogram 3" /> | <img src="./documents/4.png" width="180" alt="Monogram 4" /> |

</div>

## Características del proyecto

- **Completamente nativo** — Creado para Android con Kotlin y Jetpack Compose
- **A tu medida** — Diseños Material 3 que se adaptan a teléfonos, tabletas y pantallas grandes
- **Multimedia en tus chats** — Fotos, vídeos y stickers animados en tus conversaciones
- **Rapidísimo gracias a Rust** — Nuestra propia implementación nativa del protocolo MTProto de Telegram 🚀
- **Sin NFT ni criptomonedas** — Monogram no incluirá promociones de NFT, regalos ni otras funciones de Telegram que consideremos ajenas a una aplicación de mensajería

## Compilar desde el código fuente

### 1. Requisitos

- **JDK 17** y una versión de Android Studio compatible con el Android Gradle Plugin del proyecto (consulta el [catálogo de versiones](gradle/libs.versions.toml))
- **Android SDK Platform 37**, platform tools y un **Android NDK**. CI utiliza **NDK r28c**. Define `ANDROID_NDK_HOME` para seleccionar un NDK explícitamente; de lo contrario, Gradle lo busca en el directorio `ndk` del SDK.
- **Rust 1.98 o posterior**, Cargo y las herramientas de compilación C/C++ del sistema anfitrión para las dependencias nativas y la generación de UniFFI
- **Git**, los siguientes destinos Rust para Android y **cargo-ndk**:

```console
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
cargo install cargo-ndk --locked
```

### 2. Clonar los repositorios

```console
git clone --recurse-submodules https://github.com/monogram-android/monogram.git
git clone --recurse-submodules https://github.com/gdlbo/telers-mtproto-impl.git
cd monogram
git submodule update --init --recursive
```

Ejecuta ambos comandos de clonación desde el mismo directorio padre. Los manifiestos actuales de Cargo requieren `../telers-mtproto-impl` junto al directorio de Monogram; el submódulo de `vendor` no sustituye esta copia adyacente. CI utiliza la misma estructura.

### 3. Configurar los ajustes locales

Copia [local.properties.example](local.properties.example) a `local.properties` y define `sdk.dir`, `API_ID` y `API_HASH`.

Sistemas tipo Unix:

```sh
cp local.properties.example local.properties
```

Windows (PowerShell):

```powershell
Copy-Item local.properties.example local.properties
```

Obtén tus credenciales API en [my.telegram.org/apps](https://my.telegram.org/apps). Puedes compilar sin ellas, pero necesitas credenciales válidas para iniciar sesión en Telegram. No añadas `local.properties`, claves de firma ni credenciales de cuentas de servicio al control de versiones, a los registros ni a los commits.

Para firmar las versiones de distribución con tu propia clave, define también:

```properties
RELEASE_STORE_FILE=keystore/release.jks
RELEASE_STORE_PASSWORD=your_store_password
RELEASE_KEY_ALIAS=your_key_alias
RELEASE_KEY_PASSWORD=your_key_password
```

Sin `RELEASE_STORE_FILE`, las compilaciones release y beta utilizan la clave de depuración por defecto. `-Punsigned=true` desactiva esta alternativa cuando no hay una clave de distribución configurada. Las compilaciones con firma de depuración son para desarrollo y no sustituyen tu identidad de firma de producción.

### 4. Configurar notificaciones push (opcional)

- **FCM:** Registra una aplicación Android en Firebase con `org.monogram` (el mismo identificador para debug, release y beta). Coloca la configuración en `app/google-services.json`; Gradle aplica el plugin Google Services solo si existe ese archivo. Las dependencias de Firebase siguen incluidas incluso sin él.
- Registra las credenciales FCM correspondientes para tu Telegram API ID en [my.telegram.org/apps](https://my.telegram.org/apps). Mantén `google-services.json` y cualquier JSON de `firebase-adminsdk` fuera del control de versiones; nunca incluyas una clave de cuenta de servicio en el APK.
- Las pruebas FCM requieren Google Play services; utiliza una imagen de emulador con Google Play
- **UnifiedPush:** Instala y configura un distribuidor compatible en el dispositivo. La aplicación admite el registro de UnifiedPush.

### 5. Compilar y ejecutar

Abre el repositorio en Android Studio, sincroniza Gradle y selecciona la configuración de ejecución `app`, o ejecuta los siguientes comandos desde la raíz del repositorio. `installDebug` requiere un dispositivo conectado o un emulador en ejecución.

Sistemas tipo Unix:

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

- `debug`: compilación de desarrollo
- `release`: compilación optimizada con R8
- `beta`: compilación basada en release con R8 desactivado para facilitar la depuración


### Compilación nativa

Durante la compilación normal, Gradle compila las bibliotecas Rust para `armeabi-v7a`, `arm64-v8a` y `x86_64` y regenera los enlaces Kotlin de UniFFI desde bibliotecas de depuración del sistema anfitrión. Las bibliotecas libvpx precompiladas para stickers de vídeo están en `native/vpx/prebuilt`. Para ejecutar directamente las tareas de compilación nativa de Android:

```sh
./gradlew :native:mtproto:buildNativeMtproto :native:markup:buildNativeMarkup
```

En Windows, sustituye `./gradlew` por `./gradlew.bat`. Usa `-PskipNativeBuild=true` solo para cambios exclusivos de Kotlin cuando ya existan bibliotecas nativas y enlaces generados compatibles. Omite tanto la compilación nativa como la regeneración de enlaces; la configuración SDK/NDK sigue siendo necesaria.

## Tecnologías

- **Lenguajes y protocolo:** Kotlin, Rust, MTProto, UniFFI
- **Interfaz y estado:** Jetpack Compose, Material 3, Decompose, MVIKotlin
- **Servicios compartidos:** Koin, Coroutines, Flow, Room
- **Multimedia y notificaciones:** Media3, Coil, libvpx, tlottie, Firebase Cloud Messaging, UnifiedPush

## Estructura del proyecto

| Ruta | Responsabilidad |
|:---|:---|
| `app` | Punto de entrada, inyección de dependencias, navegación e integración push |
| `core/*` | Modelos compartidos, base de datos, interfaz, utilidades y acceso al procesamiento de marcado |
| `feature/*` | Autenticación, lista de chats, conversaciones, carpetas, perfiles y ajustes |
| `network/bridge` | API del cliente Kotlin, conversión de modelos de dominio y gestión de errores |
| `network/http` | Descargas multimedia HTTP, caché y cola |
| `native/mtproto-rs` | Cliente del protocolo en Rust y exportaciones UniFFI |
| `native/markup-rs` | Procesamiento de Markdown, resaltado de sintaxis y fórmulas en Rust |
| `native/mtproto`, `native/markup` | Fachadas Kotlin, enlaces generados y bibliotecas nativas |
| `native/vpx` | Decodificador VP9 precompilado para stickers de vídeo |
| `vendor` | Código de terceros y submódulos |

Los módulos feature llaman a `network/bridge`; la lógica nativa del protocolo reside en Rust. No deben importar tipos UniFFI generados ni depender de otros módulos feature. Consulta [AGENTS.md](AGENTS.md) para las convenciones de desarrollo y las invariantes del protocolo.

## Contribuir

Crea tu rama desde `develop` y dirige el pull request a `develop`. Respeta la arquitectura y el estilo existentes, limita los cambios al objetivo e incluye una descripción clara y las comprobaciones pertinentes. Respeta los [términos de uso de la API de Telegram](https://core.telegram.org/api/terms).

Ejecuta las comprobaciones apropiadas para el módulo modificado. Ejemplos:

```sh
./gradlew :network:bridge:test
cargo test --manifest-path native/mtproto-rs/Cargo.toml
cargo test --manifest-path native/markup-rs/Cargo.toml
git diff --check
```

En Windows, usa `./gradlew.bat`. Ejecuta solo las comprobaciones pertinentes; el comportamiento con Telegram real y en dispositivos requiere validación independiente con tus propias credenciales.

Utiliza `[Bug]` en el título de los informes de errores y `[Feature]` para propuestas. Consulta el [seguimiento de errores](https://github.com/orgs/monogram-android/projects/3/views/1) y el [tablero de funciones](https://github.com/orgs/monogram-android/projects/5/views/1).

## Traducciones

Los textos de la interfaz están en `core/ui/src/main/res/values/strings.xml` y en los archivos `feature/*/src/main/res/values/strings.xml`. Añade o actualiza `values-<locale>/strings.xml` en cada módulo pertinente (por ejemplo, `values-de/strings.xml`). Conserva los nombres de recursos, los marcadores de formato y las formas plurales. Envía las traducciones mediante un pull request. Mantén los README traducidos alineados con la versión inglesa.

## Licencia

Monogram se distribuye bajo la [GNU General Public License v3.0](LICENSE). Los componentes de terceros y los crates nativos conservan las licencias declaradas en sus respectivos directorios de código fuente.
