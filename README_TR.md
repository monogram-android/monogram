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

<p align="center"><strong>Monogram</strong>, Android 7.0 ve üzeri için yıldırım hızında, tamamen yerel bir Telegram istemcisidir. <strong>Kotlin</strong>, <strong>Jetpack Compose</strong> ve <strong>Material 3</strong> ile geliştirilen modern Android arayüzünü <strong>Rust ile yazdığımız kendi MTProto uygulamamızla</strong> bir araya getirir</p>

> [!IMPORTANT]
> Monogram **aktif geliştirme** aşamasındadır. Özellikler ve mimari gelişmeye devam etmektedir; hatalar ve eksik işlevler olabilir.

Projeyi [Boosty](https://boosty.to/monogram) üzerinden destekleyebilirsiniz.

## Ekran görüntüleri

<div align="center">

| | | | |
|:---:|:---:|:---:|:---:|
| <img src="./documents/1.png" width="180" alt="Monogram 1" /> | <img src="./documents/2.png" width="180" alt="Monogram 2" /> | <img src="./documents/3.png" width="180" alt="Monogram 3" /> | <img src="./documents/4.png" width="180" alt="Monogram 4" /> |

</div>

## Projenin özellikleri

- **Tamamen yerel** — Kotlin ve Jetpack Compose ile Android için geliştirildi
- **Ekranınıza uygun** — Telefonlara, tabletlere ve büyük ekranlara uyarlanan Material 3 düzenleri
- **Sohbetlerinizde medya** — Konuşmalarınızda fotoğraflar, videolar ve animasyonlu çıkartmalar
- **Rust gücüyle yıldırım hızında** — Telegram'ın MTProto protokolünün bize ait yerel uygulaması 🚀
- **NFT ve kripto yok** — Monogram, NFT tanıtımlarını, hediyeleri veya bir mesajlaşma uygulamasının kapsamı dışında gördüğümüz diğer Telegram özelliklerini içermeyecektir

## Kaynaktan derleme

### 1. Gereksinimler

- **JDK 17** ve projenin Android Gradle Plugin sürümüyle uyumlu bir Android Studio sürümü ([sürüm kataloğuna](gradle/libs.versions.toml) bakın)
- **Android SDK Platform 37**, platform tools ve **Android NDK**. CI, **NDK r28c** kullanır. NDK'yı açıkça seçmek için `ANDROID_NDK_HOME` ayarlayın; aksi hâlde Gradle, SDK'nın `ndk` dizininde arar.
- **Rust 1.98 veya üzeri**, Cargo ve yerel bağımlılıklar ile UniFFI üretimi için ana sistemin C/C++ derleme araçları
- **Git**, aşağıdaki Android Rust hedefleri ve **cargo-ndk**:

```console
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
cargo install cargo-ndk --locked
```

### 2. Depoları klonlama

```console
git clone --recurse-submodules https://github.com/monogram-android/monogram.git
git clone --recurse-submodules https://github.com/gdlbo/telers-mtproto-impl.git
cd monogram
git submodule update --init --recursive
```

Her iki klonlama komutunu aynı üst dizinden çalıştırın. Mevcut Cargo bildirimleri, Monogram dizininin yanında `../telers-mtproto-impl` bulunmasını gerektirir; `vendor` altındaki alt modül bu komşu kopyanın yerini tutmaz. CI da aynı dizin düzenini kullanır.

### 3. Yerel ayarları yapılandırma

[local.properties.example](local.properties.example) dosyasını `local.properties` olarak kopyalayın ve `sdk.dir`, `API_ID`, `API_HASH` değerlerini ayarlayın.

Unix benzeri sistemler:

```sh
cp local.properties.example local.properties
```

Windows (PowerShell):

```powershell
Copy-Item local.properties.example local.properties
```

API kimlik bilgilerinizi [my.telegram.org/apps](https://my.telegram.org/apps) adresinden alın. Bunlar olmadan derleme yapılabilir ancak Telegram'a giriş için geçerli bilgiler gereklidir. `local.properties`, imzalama anahtarları ve hizmet hesabı kimlik bilgilerini sürüm kontrolüne, günlüklere veya commit'lere eklemeyin.

Dağıtım derlemelerini kendi anahtarınızla imzalamak için şunları da ayarlayın:

```properties
RELEASE_STORE_FILE=keystore/release.jks
RELEASE_STORE_PASSWORD=your_store_password
RELEASE_KEY_ALIAS=your_key_alias
RELEASE_KEY_PASSWORD=your_key_password
```

`RELEASE_STORE_FILE` yoksa release ve beta derlemeleri varsayılan olarak hata ayıklama anahtarıyla imzalanır. `-Punsigned=true`, bir dağıtım anahtarı yapılandırılmadığında bu varsayılanı devre dışı bırakır. Hata ayıklama imzası geliştirme içindir; üretim imzanızla dağıtımın yerini tutmaz.

### 4. Anlık bildirimleri yapılandırma (isteğe bağlı)

- **FCM:** Firebase'de `org.monogram` kimliğiyle bir Android uygulaması kaydedin (debug, release ve beta aynı uygulama kimliğini kullanır). Yapılandırmayı `app/google-services.json` konumuna koyun; Gradle, Google Services eklentisini yalnızca bu dosya varsa uygular. Yapılandırma dosyası olmasa da Firebase bağımlılıkları uygulamada kalır.
- İlgili FCM kimlik bilgilerini Telegram API ID'niz için [my.telegram.org/apps](https://my.telegram.org/apps) adresinde kaydedin. `google-services.json` ve tüm `firebase-adminsdk` JSON dosyalarını sürüm kontrolünün dışında tutun; hizmet hesabı anahtarını asla APK'ya eklemeyin.
- FCM testleri Google Play hizmetlerini gerektirir; emülatör testleri için Google Play imajı kullanın
- **UnifiedPush:** Cihaza uyumlu bir dağıtıcı kurup yapılandırın. Uygulama UnifiedPush kaydını destekler.

### 5. Derleme ve çalıştırma

Depoyu Android Studio'da açın, Gradle'ı senkronize edin ve `app` çalıştırma yapılandırmasını seçin veya aşağıdaki komutları depo kökünden çalıştırın. `installDebug`, bağlı bir cihaz ya da çalışan bir emülatör gerektirir.

Unix benzeri sistemler:

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

- `debug`: geliştirme derlemesi
- `release`: R8 ile optimize edilmiş derleme
- `beta`: hata ayıklamayı kolaylaştırmak için R8'in devre dışı bırakıldığı, release tabanlı derleme


### Yerel derleme

Normal derlemenin parçası olarak Gradle, Rust kitaplıklarını `armeabi-v7a`, `arm64-v8a` ve `x86_64` için derler ve ana sistemin hata ayıklama kitaplıklarından UniFFI Kotlin bağlarını yeniden üretir. Video çıkartmaları için önceden derlenmiş libvpx kitaplıkları `native/vpx/prebuilt` altındadır. Android yerel derleme görevlerini doğrudan çalıştırmak için:

```sh
./gradlew :native:mtproto:buildNativeMtproto :native:markup:buildNativeMarkup
```

Windows'ta `./gradlew` yerine `./gradlew.bat` kullanın. `-PskipNativeBuild=true` seçeneğini yalnızca uyumlu yerel kitaplıklar ve üretilmiş bağlar zaten mevcutken, sadece Kotlin değişiklikleri için kullanın. Hem yerel derlemeyi hem de bağların yeniden üretilmesini atlar; SDK/NDK yapılandırması yine gereklidir.

## Teknolojiler

- **Diller ve protokol:** Kotlin, Rust, MTProto, UniFFI
- **Arayüz ve durum:** Jetpack Compose, Material 3, Decompose, MVIKotlin
- **Ortak hizmetler:** Koin, Coroutines, Flow, Room
- **Medya ve bildirimler:** Media3, Coil, libvpx, tlottie, Firebase Cloud Messaging, UnifiedPush

## Proje yapısı

| Yol | Sorumluluk |
|:---|:---|
| `app` | Uygulama giriş noktası, bağımlılık enjeksiyonu, gezinme ve bildirim entegrasyonu |
| `core/*` | Ortak modeller, veritabanı, arayüz, yardımcı araçlar ve işaretleme işleme erişimi |
| `feature/*` | Kimlik doğrulama, sohbet listesi, konuşmalar, klasörler, profiller ve ayarlar |
| `network/bridge` | Kotlin istemci API'si, alan modeli dönüşümleri ve hata yönetimi |
| `network/http` | HTTP medya indirmeleri, önbellek ve kuyruk |
| `native/mtproto-rs` | Rust protokol istemcisi ve UniFFI dışa aktarımları |
| `native/markup-rs` | Rust ile Markdown, sözdizimi vurgulama ve matematik ayrıştırma |
| `native/mtproto`, `native/markup` | Kotlin arayüz katmanları, üretilmiş bağlar ve yerel kitaplıklar |
| `native/vpx` | Video çıkartmaları için önceden derlenmiş VP9 çözücü |
| `vendor` | Üçüncü taraf kaynak kodları ve alt modüller |

Feature modülleri `network/bridge` üzerinden çağrı yapar; yerel protokol mantığı Rust'tadır. Feature modülleri üretilmiş UniFFI türlerini içe aktarmamalı veya birbirine bağımlı olmamalıdır. Geliştirme kuralları ve protokol değişmezleri için [AGENTS.md](AGENTS.md) dosyasına bakın.

## Katkıda bulunma

Dalınızı `develop` üzerinden oluşturun ve pull request'in hedefini `develop` olarak ayarlayın. Mevcut mimariyi ve kod stilini izleyin, değişiklikleri görevle sınırlayın, açık bir açıklama ve ilgili doğrulama sonuçlarını ekleyin. [Telegram API Kullanım Koşulları'na](https://core.telegram.org/api/terms) uyun.

Değiştirilen modüle uygun kontrolleri çalıştırın. Örnekler:

```sh
./gradlew :network:bridge:test
cargo test --manifest-path native/mtproto-rs/Cargo.toml
cargo test --manifest-path native/markup-rs/Cargo.toml
git diff --check
```

Windows'ta `./gradlew.bat` kullanın. Yalnızca değişikliğinizle ilgili kontrolleri çalıştırın; gerçek Telegram ve cihaz davranışı kendi kimlik bilgilerinizle ayrıca doğrulanmalıdır.

Hata bildirimlerinin başlığında `[Bug]`, özellik önerilerinde `[Feature]` kullanın. [Hata Takipçisi'ne](https://github.com/orgs/monogram-android/projects/3/views/1) ve [Özellik Panosu'na](https://github.com/orgs/monogram-android/projects/5/views/1) bakın.

## Çeviriler

Arayüz metinleri `core/ui/src/main/res/values/strings.xml` ve `feature/*/src/main/res/values/strings.xml` dosyalarındadır. İlgili her modülde `values-<locale>/strings.xml` ekleyin veya güncelleyin (örneğin `values-de/strings.xml`). Kaynak adlarını, biçimlendirme yer tutucularını ve çoğul biçimlerini koruyun. Çevirileri pull request ile gönderin. README çevirilerini İngilizce sürümle uyumlu tutun.

## Lisans

Monogram, [GNU General Public License v3.0](LICENSE) ile lisanslanmıştır. Üçüncü taraf bileşenler ve yerel crate'ler, kendi kaynak dizinlerinde belirtilen lisansları korur.
