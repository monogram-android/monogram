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
    <img src="https://img.shields.io/badge/Lisans-GPLv3-blue.svg" width="120">
  </a>
  <a href="https://github.com/monogram-android/monogram/stargazers">
    <img src="https://img.shields.io/github/stars/monogram-android/monogram" width="120">
  </a>
  <img src="https://img.shields.io/badge/Kotlin-2.0+-blue.svg?logo=kotlin" width="130">
  <img src="https://img.shields.io/badge/TDLib-1.8.63-blue" width="120">
  <img src="https://img.shields.io/badge/Durum-Aktif_Geliştirme-orange" width="170">
  <a href="https://boosty.to/monogram">
    <img src="https://img.shields.io/badge/Boosty-Projeyi_Destekle-ff6f61?logo=boosty&logoColor=white" width="200">
  </a>
</h1>

**Bu dokümanı diğer dillerde okuyun:** [English](README.md), [Русский](README_RU.md), [Türkçe](README_TR.md), [한국어](README_KOR.md), [اُردو](README_UR.md), [Español](README_ES.md)

---

**MonoGram**, Android için modern, yıldırım hızında ve zarif bir resmi olmayan Telegram istemcisidir. **Jetpack Compose** ve **Material Design 3** ile sıfırdan inşa edilen uygulama, resmi **TDLib** altyapısıyla desteklenen yerleşik (native) ve akıcı bir deneyim sunar.

> [!IMPORTANT]
> MonoGram şu an **aktif geliştirme** aşamasındadır. Sık güncellemeler, mimari değişiklikler ve nadiren de olsa hatalar (bug) bekleyebilirsiniz.

Projeyi [**Boosty**](https://boosty.to/monogram) üzerinden destekleyebilirsiniz.

---

## Ekran Görüntüleri

<div align="center">

| | | | |
|:---:|:---:|:---:|:---:|
| <img src="./documents/1.png" width="180" alt="Ekran Görüntüsü 1" /> | <img src="./documents/2.png" width="180" alt="Ekran Görüntüsü 2" /> | <img src="./documents/3.png" width="180" alt="Ekran Görüntüsü 3" /> | <img src="./documents/4.png" width="180" alt="Ekran Görüntüsü 4" /> |

</div>

---

## Öne Çıkan Özellikler

- **Bağımsız İstemci** — Android için Telegram'ın bir çatalı (fork) değildir. MonoGram, tamamen sıfırdan bağımsız bir proje olarak inşa edilmiştir.
- **Material Design 3** — Telefonlar, tabletler ve katlanabilir cihazlarda harika görünen, estetik ve uyarlanabilir kullanıcı arayüzü.
- **Güvenli** — Yerleşik biyometrik kilitleme ve şifrelenmiş yerel depolama.
- **Zengin Medya Deneyimi** — ExoPlayer ve Coil 3 ile yüksek performanslı medya oynatma.
- **Hızlı ve Verimli** — Kotlin Coroutines ile desteklenen, performans için optimize edilmiş yapı.
- **Temiz Mimari (Clean Architecture)** — Domain, Data ve Presentation katmanları ile sorumlulukların net bir şekilde ayrılması.
- **MVI Deseni** — MVIKotlin kullanılarak sağlanan öngörülebilir durum yönetimi.
- **NFT veya Kripto Yok** — MonoGram; Telegram tarafından dayatılan ve bir mesajlaşma uygulamasının kapsamı dışında gördüğümüz NFT tanıtımları, hediyeleri veya benzeri özellikleri asla içermeyecektir.

---

## Başlangıç

Projeyi yerel ortamınızda kurmak için bu adımları izleyin.

### Ön Koşullar

- **Android Studio**: Ladybug veya daha yeni bir sürüm (önerilir).
- **JDK**: Java 17 veya daha yeni bir sürüm.

### 1. Depoyu Klonlayın

```bash
git clone --recurse-submodules https://github.com/monogram-android/monogram.git
cd monogram
```
### 2. Telegram API Anahtarlarını Yapılandırın

Telegram sunucularına bağlanmak için kendi API kimlik bilgilerinize ihtiyacınız vardır.

1. [my.telegram.org](https://my.telegram.org/) adresinde oturum açın.
2. **API development tools (API geliştirme araçları)** bölümüne gidin.
3. `App api_id` ve `App api_hash` değerlerinizi almak için yeni bir uygulama oluşturun.
4. Projenin kök dizininde (eğer yoksa) `local.properties` adlı bir dosya oluşturun.
5. Aşağıdaki satırları dosyaya ekleyin:

```properties
API_ID=12345678
API_HASH=your_api_hash_here
```

Gradle üzerinden imzalı release derlemeleri için şu değerleri de ekleyin:

```properties
RELEASE_STORE_FILE=keystore/release.jks
RELEASE_STORE_PASSWORD=your_store_password
RELEASE_KEY_ALIAS=your_key_alias
RELEASE_KEY_PASSWORD=your_key_password
```
### 3. Anlık Bildirimleri (Push Notifications) Yapılandırın

1. [Firebase konsolunda](https://console.firebase.google.com) oturum açın.
2. Yeni bir proje oluşturun.
3. İki Firebase Android uygulaması ekleyin:

    - `org.monogram` release derlemeleri için
    - `org.monogram.debug` debug derlemeleri için

4. `google-services.json` dosyasını indirin ve **app** modülünün kök dizinine kopyalayın (
   `monogram/app/google-services.json`). Dosyanın yukarıdaki iki paket için de istemci içerdiğinden
   emin olun.
5. **Cloud Messaging** bölümüne gidin.
6. **Manage service accounts** (Hizmet hesaplarını yönet) seçeneğine tıklayın.
7. Açılan pencerenin üst kısmındaki **Keys** (Anahtarlar) sekmesini seçin.
8. **Add key** (Anahtar ekle) seçeneğine tıklayın ve **JSON** opsiyonunu seçin. Dosyanın indirilmesini bekleyin.
9. Uygulama ID'nizi aldığınız Telegram API sayfasına geri dönün.
10. FCM kimlik bilgileri (FCM credentials) bölümünün yanındaki **Update** (Güncelle) butonuna tıklayın.
11. Açılan sayfada hizmet hesabı (service account) JSON dosyasını yükleyin.

### 4. İlk Kurulum: libvpx Derlemesi

Animasyonların çalışması için libvpx'in derlenmiş olması gerekir. Bu işlem, Gradle derlemesini başlatmadan önce yapılmalıdır; aksi takdirde derleme hatalarıyla karşılaşırsınız.

1. Çalışma dizininizi `presentation/src/main/cpp` olarak değiştirin.
2. `build.sh` dosyası içerisine kendi `ANDROID_NDK_HOME` yolunuzu ekleyin.
3. `build.sh` dosyasını çalıştırın ve işlemin tamamlanmasını bekleyin.

### 5. Derleyin ve Çalıştırın

1. Projeyi **Android Studio** ile açın.
2. `TdApi.java` (TDLib sarmalayıcısı) dosyasının doğru şekilde indekslenebilmesi için IDE indeksleme limitlerini artırın. **Android Studio** veya **IntelliJ IDEA** içerisinde, **Help (Yardım) → Edit Custom Properties...** (Özel Özellikleri Düzenle) yolunu izleyin, aşağıdaki satırları yapıştırın ve istenirse IDE'yi yeniden başlatın:

```properties
# Kb cinsinden boyut
idea.max.intellisense.filesize=20480
# Kb cinsinden boyut
idea.max.content.load.filesize=20480
```

3. Gradle senkronizasyonunu (Sync) yapın.
4. `app` çalıştırma yapılandırmasını (run configuration) seçin.
5. Bir cihaz bağlayın veya bir emülatör başlatın.
6. **Run** (Çalıştır) butonuna tıklayın.

---

## TDLib Derlemesi

Eğer TDLib'i kaynaktan derlemeniz gerekirse, öncelikle gerekli bağımlılıkları kurun. Debian/Ubuntu tabanlı dağıtımlar için:

```bash
sudo apt-get update
sudo apt-get install build-essential git curl wget php perl gperf unzip zip default-jdk cmake
```

Ardından projenin kök dizininden derleme betiğini (script) çalıştırın:

```bash
./build-tdlib.sh
```

Script şu modları destekler:

- `./build-tdlib.sh official`
- `./build-tdlib.sh telemt`
- `./build-tdlib.sh both`

Argümansız çalıştırırsanız, script size seçim sorar.

### Build Variantları ve Gradle Görevleri

Android Studio'da şu variantları kullanın:

- `officialDebug`
- `officialRelease`
- `telemtDebug`
- `telemtRelease`

Kullanışlı Gradle görevleri:

```bash
./gradlew assembleOfficialReleaseTdlibApks
./gradlew assembleTelemtReleaseTdlibApks
./gradlew assembleAllReleaseTdlibApks
./gradlew assembleOfficialDebugTdlibApks
./gradlew assembleTelemtDebugTdlibApks
./gradlew assembleAllDebugTdlibApks
```

APK adları:

- normal TDLib: `monogram-arm64-v8a-<version>-release.apk`
- Telemt TDLib: `monogram-telemt-arm64-v8a-<version>-release.apk`

---

## Katkıda Bulunma

Katkılarınızı memnuniyetle karşılıyoruz! İster hataları gidermek, ister dokümantasyonu iyileştirmek veya yeni özellikler önermek olsun, her türlü katkıya açığız.

1. **Sorunları (Issues) Kontrol Edin** — Açık sorunlara göz atın veya fikirlerinizi tartışmak için yeni bir sorun kaydı oluşturun.
2. **`develop` Dalında Çalışın** — Kendi dalınızı (branch) `develop` üzerinden oluşturun ve çalışmalarınızı bu dalı temel alarak sürdürün.
3. **Fork & Branch** — Depoyu (repo) çatallayın (fork) ve bir özellik dalı (feature branch) oluşturun.
4. **Kod Stili** — Mevcut Kotlin kod yazım stiline ve Temiz Mimari (Clean Architecture) yönergelerine uyun.
5. **PR Gönderin** — Değişikliklerinizin net bir açıklamasını içeren bir Çekme İsteğini (Pull Request) `develop` dalına açın.

> [!IMPORTANT]
> - [Telegram API Hizmet Şartlarına](https://core.telegram.org/api/terms) uyun.
> - Kodunuzun tüm kontrollerden ve testlerden geçtiğinden emin olun.

### Hata Bildirme ve Özellik Önerileri

- **Hatalar (Bugs)** — Bir sorun kaydı (issue) açın ve başlıkta `[Bug]` etiketini kullanın (Örn: `[Bug] Uygulama başlangıçta çöküyor`). Ayrıca, bilinen tüm hatalara [**Hata Takipçisi**](https://github.com/orgs/monogram-android/projects/3/views/1) üzerinden göz atabilirsiniz.
- **Özellik İstekleri** — `[Feature]` etiketini içeren bir sorun kaydı açın (Örn: `[Feature] Planlanmış mesaj desteği`). Mevcut özellik isteklerini [**Özellik Panosu**](https://github.com/orgs/monogram-android/projects/5/views/1) üzerinden inceleyebilirsiniz.

---

## Çeviriler

MonoGram topluluk tarafından yapılan çevirileri memnuniyetle karşılar! Kendi dilinizle katkıda bulunmak için metin kaynak (strings resource) dosyasını düzenleyebilirsiniz.

Kaynak metinler [`presentation/src/main/res/values/string.xml`](https://github.com/monogram-android/monogram/blob/develop/presentation/src/main/res/values/string.xml) adresinde yer almaktadır. Yeni bir dil eklemek için, ilgili dile ait bir `values-<yerel-kod>/string.xml` dosyası oluşturun (örneğin Almanca için `values-de/string.xml`) ve metinleri orada çevirin. Çevirinizi içeren bir Çekme İsteği (PR) açın, biz de onu projeye dahil edelim.

---

## Teknoloji Yığını

MonoGram, en güncel Android geliştirme araçlarından ve kütüphanelerinden yararlanır:

| Kategori | Kütüphaneler |
|:---|:---|
| **Dil** | [Kotlin](https://kotlinlang.org/) |
| **Kullanıcı Arayüzü (UI)** | [Jetpack Compose](https://developer.android.com/jetpack/compose) (Material 3) |
| **Mimari** | [Decompose](https://github.com/arkivanov/Decompose) (Navigasyon), [MVIKotlin](https://github.com/arkivanov/MVIKotlin) |
| **Bağımlılık Enjeksiyonu (DI)** | [Koin](https://insert-koin.io/) |
| **Asenkron İşlemler** | Coroutines & Flow |
| **Telegram Çekirdeği** | [TDLib](https://core.telegram.org/tdlib) (Telegram Database Library) |
| **Görsel Yükleme** | [Coil 3](https://coil-kt.github.io/coil/) |
| **Medya** | Media3 (ExoPlayer) |
| **Haritalar** | [MapLibre](https://maplibre.org/) |
| **Yerel Veritabanı** | Room |

---

## Proje Yapısı

Proje, sorumlulukların ayrılmasını ve ölçeklenebilirliği sağlamak amacıyla çok modüllü (multi-module) bir yapı izlemektedir:

| Modül | Açıklama |
|:---|:---|
| **:app** | Ana Android uygulama modülü. |
| **:domain** | İş mantığı (business logic), kullanım durumları (use cases) ve depo (repository) arayüzlerini içeren saf Kotlin modülü. |
| **:data** | Depo (repository) uygulamaları, veri kaynakları ve TDLib entegrasyonu. |
| **:presentation** | Kullanıcı arayüzü (UI) bileşenleri, ekranlar ve görünüm modelleri (MVI Store'ları). |
| **:core** | Modüller genelinde kullanılan ortak yardımcı sınıflar ve uzantılar (extensions). |
| **:baselineprofile** | Uygulama başlangıcı ve performans optimizasyonu için Baseline Profilleri. |

---

## Lisans

Bu proje [**GNU General Public License v3.0**](LICENSE) (GNU Genel Kamu Lisansı v3.0) kapsamında lisanslanmıştır.
