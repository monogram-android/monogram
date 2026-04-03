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

**Si deseas leer este documento en otro lenguaje:** [Русский](README_RU.md),
[한국어](README_KOR.md), [اُردو](README_UR.md), [English](README.md)

---

**MonoGram** es un moderno, rápido y elegante cliente no oficial de Telegram
para Android. Construido con **Jetpack Compose** y **Material Design 3**,
entrega una experiencia nativa y fluida, empoderada por el proyecto oficial
**TDLib**.

> [!IMPORTANT]
> En estos momentos, MonoGram se encuentra en **desarrollo activo**. Espera
> actualizaciones frecuentes, cambios en la arquitectura, y algún que otro bug.

Ayuda al proyecto en [**Boosty**](https://boosty.to/monogram).

---

## Capturas de Pantalla

<div align="center">

|                                                                |                                                                |                                                                |                                                                |
| :------------------------------------------------------------: | :------------------------------------------------------------: | :------------------------------------------------------------: | :------------------------------------------------------------: |
| <img src="./documents/1.png" width="180" alt="Screenshot 1" /> | <img src="./documents/2.png" width="180" alt="Screenshot 2" /> | <img src="./documents/3.png" width="180" alt="Screenshot 3" /> | <img src="./documents/4.png" width="180" alt="Screenshot 4" /> |

</div>

---

## Características Clave

- **Material Design 3** — Una bonita y adaptativa UI que se ve grandiosa en
  celulares, tablets y plegables.
- **Seguro** — Almacenamiento local encriptado y bloqueo biométrico incluido.
- **Multimedia Rica** — Reproducción de multimedia de alto rendimiento con
  ExoPlayer y Coil 3.
- **Rápido y Eficiente** — Empoderado por Kotlin Coroutines y optimizado para
  ofrecer rendimiento.
- **Arquitectura Limpia** — Separación clara de propósitos con capas de Domain,
  Data y Presentation.
- **Patrón MVI** — Administración de estados predecible usando MVIKotlin.
- **Sin NFTs o Cripto** — MonoGram nunca incluirá promociones sobre NFTs,
  regalos u otras características de Telegram que consideremos fuera del ámbito
  de una aplicacion de mensajería.

---

## Comenzando

Sigue estos pasos para configurar el proyecto localmente.

### Requisitos Previos

- **Android Studio**: Ladybug o más nuevo (recomendado).
- **JDK**: Java 17 o más nuevo.

### 1. Clona el Repositorio

```bash
git clone https://github.com/monogram-android/monogram.git
cd monogram
```

### 2. Configura las API Keys de Telegram

Para conectarse a los servidores de Telegram, necesitas tus propias credenciales
API.

1. Inicia sesión en [my.telegram.org](https://my.telegram.org/).
2. Ve a **API development tools**.
3. Crea una nueva aplicación para obtener tu `App api_id` y `App api_hash`.
4. Crea un nuevo archivo llamado `local.properties` en el directorio raíz del
   proyecto. (si no existe).
5. Agrega las siguientes líneas:

```properties
API_ID=12345678
API_HASH=your_api_hash_here
```

### 3. Configurar notificaciones

1. Inicia sesión en la
   [consola de Firebase](https://console.firebase.google.com).
2. Crea un nuevo proyecto.
3. Agrega una nueva aplicación con el `applicationId` que necesitas. Si tienes
   múltiples aplicaciones con diferentes IDs, crea múltiples aplicaciones de
   Firebase. **Por defecto, el `applicationId` para compilaciones debug y
   release son diferentes.**
4. Descarga el archivo `google-services.json` y cópialo a la raíz de módulo
   **app** (`monogram/app/google-services.json`). Si creaste múltiples
   aplicaciones, solo copia la configuración más reciente.
5. Ve a la sección **Cloud Messaging**.
6. Ve a **Manage service accounts**.
7. Selecciona la sección **Keys** en el tope de la ventana que se abre.
8. Clickea en **Add key** y selecciona la opción **JSON**. Espera al archivo a
   descargarse.
9. Vuelve a la pagina de la Telegram API donde recibiste tu App ID.
10. Clickea en **Update** después de la sección FCM credentials.
11. Sube el service account JSON en la página que se abre.

### 4. Compilar y Ejecutar

1. Abre el proyecto en **Android Studio**.
2. Aumenta los límites de indexado del IDE para que `TdApi.java` (el wrapper de
   TDLib) sea indexado correctamente. En **Android Studio** o **IntelliJ IDEA**,
   abre **Help → Edit Custom Properties...**, pega las siguientes líneas, y
   reinicia el IDE si es necesario:

```properties
# size in Kb
idea.max.intellisense.filesize=20480
# size in Kb
idea.max.content.load.filesize=20480
```

3. Sincroniza Gradle.
4. Selecciona la configuración de ejecución `app`.
5. Conecta un dispositivo o inicia un emulador.
6. Clickea **Run**.

---

## Compilando TDLib

Si necesitas compilar TDLib desde el código fuente, primero, instala las
dependencias necesarias. Para distribuciones basadas en Ubuntu o Debian:

```bash
sudo apt-get update
sudo apt-get install build-essential git curl wget php perl gperf unzip zip default-jdk cmake
```

Después ejecuta el script de compilación desde la raíz de tu proyecto:

```bash
./build-tdlib.sh
```

---

## Contribuir

Damos la bienvenida a contribuciones! Dígase solución de bugs, mejorar la
documentación, o sugerir nuevas características.

1. **Chequea las Incidencias** — Busca incidencias abiertas o crea una nueva
   para discutir tus ideas.
2. **Trabaja desde `develop`** — Crea tu rama desde `develop` y mantén tu
   trabajo basado en esa rama.
3. **Forkea y crea una rama** — Forkea el repositorio y crea una rama de
   características.
4. **Estilo de Código** — Sigue el estilo existente de código en Kotlin y
   directrices de Clean Architecture.
5. **Sube un PR** — Abre un Pull Request a `develop` con una descripción clara
   de tus cambios.

> [!IMPORTANT]
>
> - Respeta los
>   [Términos de Servicio de Telegram API](https://core.telegram.org/api/terms).
> - Asegúrate de que tu código pase todas las pruebas y chequeos.

### Reportar Bugs y Sugerir Características

- **Bugs** — Abre una incidencia y usa la etiqueta `[Bug]` en el título (ej.
  `[Bug] La aplicación se crashea al iniciar`). También puedes buscar todos los
  bugs conocidos en el
  [**Bug Tracker**](https://github.com/orgs/monogram-android/projects/3/views/1).
- **Solicitud de Características** — Abre una incidencia y usa la etiqueta
  `[Feature]` (ej. `[Feature] Soporte para mensajes programados`). Las
  solicitudes de características existentes se pueden encontrar en el
  [**Feature Board**](https://github.com/orgs/monogram-android/projects/5/views/1).

---

## Traducciones

MonoGram le da la bienvenida a traducciones de la comunidad! Puedes contribuir
con tu propio lenguaje y editar el archivo strings resource.

Las source strings se pueden encontrar en
[`presentation/src/main/res/values/string.xml`](https://github.com/monogram-android/monogram/blob/develop/presentation/src/main/res/values/string.xml).

Para añadir un nuevo lenguaje, crea el
correspondiente`values-<idioma>/string.xml` file (ej. `values-de/string.xml`
para el Alemán) y traduce las strings ahí. Abre un PR con tu traducción y nos
encargaremos de mezclarla.

---

## Stack Tecnológico

MonoGram aprovecha las últimas herramientras de desarrollo y librerías de
Android:

| Categoría                     | Librerías                                                                                                             |
| :---------------------------- | :-------------------------------------------------------------------------------------------------------------------- |
| **Lenguaje**                  | [Kotlin](https://kotlinlang.org/)                                                                                     |
| **UI Toolkit**                | [Jetpack Compose](https://developer.android.com/jetpack/compose) (Material 3)                                         |
| **Arquitectura**              | [Decompose](https://github.com/arkivanov/Decompose) (Navigation), [MVIKotlin](https://github.com/arkivanov/MVIKotlin) |
| **Inyección de Dependencias** | [Koin](https://insert-koin.io/)                                                                                       |
| **Asincronía**                | Coroutines & Flow                                                                                                     |
| **Núcleo de Telegram**        | [TDLib](https://core.telegram.org/tdlib) (Telegram Database Library)                                                  |
| **Carga de imágenes**         | [Coil 3](https://coil-kt.github.io/coil/)                                                                             |
| **Multimedia**                | Media3 (ExoPlayer)                                                                                                    |
| **Mapas**                     | [MapLibre](https://maplibre.org/)                                                                                     |
| **Base de Datos local**       | Room                                                                                                                  |

---

## Estructura del Proyecto

Este proyecto sigue una estructura multi-módulo para asegurarse de separar
propósitos y escalabilidad:

| Módulo               | Descripción                                                                                        |
| :------------------- | :------------------------------------------------------------------------------------------------- |
| **:app**             | El módulo de la aplicación principal de Android.                                                   |
| **:domain**          | Módulo puro en Kotlin que contiene la lógica de trabajo, casos de uso e interfaces de repositorio. |
| **:data**            | Implementación de repositorios, fuentes de datos, e integración con TDLib.                         |
| **:presentation**    | Componentes de UI, pantallas y modelos de visión. (MVI Stores).                                    |
| **:core**            | Clases comunes de utilidades y extensiones usadas entre módulos.                                   |
| **:baselineprofile** | Perfiles Baseline para optimizar el inicio de la app y el rendimiento.                             |

---

## Licencia

Este proyecto está licenciado bajo la
[**GNU General Public License v3.0**](LICENSE).
