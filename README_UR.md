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

<p align="center">[English](README.md) · [Русский](README_RU.md) · [Türkçe](README_TR.md) · [한국어](README_KOR.md) · [اُردو](README_UR.md) · [Español](README_ES.md)</p>

<p align="center">**Monogram**، Android 7.0 اور اس سے نئے ورژنز کے لیے بجلی کی طرح تیز، مکمل طور پر نیٹو Telegram کلائنٹ ہے۔ **Kotlin**، **Jetpack Compose** اور **Material 3** سے بنایا گیا جدید Android انٹرفیس، **Rust میں ہماری اپنی MTProto implementation** کے ساتھ کام کرتا ہے</p>

> [!IMPORTANT]
> Monogram کی **فعال ترقی جاری ہے**۔ خصوصیات اور ساخت میں تبدیلیاں ہو رہی ہیں؛ خرابیاں اور نامکمل خصوصیات موجود ہو سکتی ہیں۔

[Boosty](https://boosty.to/monogram) پر منصوبے کی مدد کریں۔

## اسکرین شاٹس

<div align="center">

| | | | |
|:---:|:---:|:---:|:---:|
| <img src="./documents/1.png" width="180" alt="Monogram 1" /> | <img src="./documents/2.png" width="180" alt="Monogram 2" /> | <img src="./documents/3.png" width="180" alt="Monogram 3" /> | <img src="./documents/4.png" width="180" alt="Monogram 4" /> |

</div>

## منصوبے کی خصوصیات

- **مکمل طور پر نیٹو** — Kotlin اور Jetpack Compose کے ساتھ Android کے لیے بنایا گیا
- **آپ کی اسکرین کے مطابق** — فون، ٹیبلیٹ اور بڑی اسکرینز کے مطابق ڈھلنے والے Material 3 لے آؤٹس
- **چیٹس میں میڈیا** — گفتگو میں تصاویر، ویڈیوز اور متحرک اسٹیکرز
- **Rust کی طاقت، بجلی جیسی رفتار** — Telegram کے MTProto پروٹوکول کا ہمارا اپنا نیٹو نفاذ 🚀
- **NFT یا کرپٹو نہیں** — Monogram میں NFT کی تشہیر، تحائف یا Telegram کی وہ دیگر خصوصیات شامل نہیں ہوں گی جنہیں ہم پیغام رسانی کی ایپ کے دائرے سے باہر سمجھتے ہیں

## سورس سے بلڈ بنانا

### 1. ضروریات

- **JDK 17** اور Android Studio کا ایسا ورژن جو منصوبے کے Android Gradle Plugin کے ساتھ مطابقت رکھتا ہو ([ورژن کی فہرست](gradle/libs.versions.toml) دیکھیں)
- **Android SDK Platform 37**، platform tools اور **Android NDK**۔ CI میں **NDK r28c** استعمال ہوتا ہے۔ کسی مخصوص NDK کے انتخاب کے لیے `ANDROID_NDK_HOME` مقرر کریں؛ بصورت دیگر Gradle، SDK کی `ndk` ڈائریکٹری میں تلاش کرتا ہے۔
- **Rust 1.98 یا جدید تر**، Cargo اور مقامی dependencies اور UniFFI کی تیاری کے لیے میزبان سسٹم کے C/C++ بلڈ ٹولز
- **Git**، درج ذیل Android Rust targets اور **cargo-ndk**:

```console
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
cargo install cargo-ndk --locked
```

### 2. ریپوزٹریز کلون کرنا

```console
git clone --recurse-submodules https://github.com/monogram-android/monogram.git
git clone --recurse-submodules https://github.com/gdlbo/telers-mtproto-impl.git
cd monogram
git submodule update --init --recursive
```

دونوں clone کمانڈز ایک ہی بنیادی ڈائریکٹری سے چلائیں۔ موجودہ Cargo manifests کو Monogram کی ڈائریکٹری کے ساتھ `../telers-mtproto-impl` درکار ہے؛ `vendor` میں موجود submodule اس الگ، ساتھ والی کاپی کا متبادل نہیں ہے۔ CI بھی یہی ترتیب استعمال کرتا ہے۔

### 3. مقامی ترتیبات

[local.properties.example](local.properties.example) کو `local.properties` کے نام سے کاپی کریں، پھر `sdk.dir`، `API_ID` اور `API_HASH` مقرر کریں۔

Unix جیسے سسٹمز:

```sh
cp local.properties.example local.properties
```

Windows (PowerShell):

```powershell
Copy-Item local.properties.example local.properties
```

اپنی API اسناد [my.telegram.org/apps](https://my.telegram.org/apps) سے حاصل کریں۔ ان کے بغیر بلڈ بن سکتا ہے، لیکن Telegram میں لاگ اِن کے لیے درست اسناد ضروری ہیں۔ `local.properties`، دستخطی کلیدیں اور سروس اکاؤنٹ کی اسناد ورژن کنٹرول سے باہر رکھیں؛ انہیں لاگز یا commits میں شامل نہ کریں۔

ریلیز کو اپنی کلید سے دستخط کرنے کے لیے یہ اقدار بھی مقرر کریں:

```properties
RELEASE_STORE_FILE=keystore/release.jks
RELEASE_STORE_PASSWORD=your_store_password
RELEASE_KEY_ALIAS=your_key_alias
RELEASE_KEY_PASSWORD=your_key_password
```

اگر `RELEASE_STORE_FILE` موجود نہ ہو تو release اور beta بلڈز میں بطورِ ڈیفالٹ debug کلید استعمال ہوتی ہے۔ ریلیز کلید مقرر نہ ہونے پر `-Punsigned=true` اس متبادل دستخط کو بند کرتا ہے۔ debug دستخط والے بلڈز ترقیاتی کام کے لیے ہیں، آپ کی اصل تقسیمی دستخطی شناخت کا متبادل نہیں۔

### 4. پش اطلاعات کی ترتیب (اختیاری)

- **FCM:** Firebase میں `org.monogram` کے لیے Android ایپ رجسٹر کریں (debug، release اور beta کا application ID ایک ہی ہے)۔ کنفیگریشن `app/google-services.json` میں رکھیں؛ Gradle صرف اس فائل کی موجودگی میں Google Services plugin لاگو کرتا ہے۔ کنفیگریشن فائل کے بغیر بھی Firebase dependencies ایپ میں شامل رہتی ہیں۔
- اپنے Telegram API ID کے لیے متعلقہ FCM اسناد [my.telegram.org/apps](https://my.telegram.org/apps) پر رجسٹر کریں۔ `google-services.json` اور تمام `firebase-adminsdk` JSON فائلیں ورژن کنٹرول سے باہر رکھیں؛ سروس اکاؤنٹ کی کلید کبھی APK میں شامل نہ کریں۔
- FCM کی جانچ کے لیے Google Play services ضروری ہیں؛ ایمولیٹر پر Google Play امیج استعمال کریں
- **UnifiedPush:** ڈیوائس پر موافق distributor انسٹال اور کنفیگر کریں۔ ایپ UnifiedPush رجسٹریشن کی سہولت رکھتی ہے۔

### 5. بلڈ اور اجرا

ریپوزٹری Android Studio میں کھولیں، Gradle sync کریں اور `app` رن کنفیگریشن منتخب کریں، یا ریپوزٹری کی بنیادی ڈائریکٹری سے درج ذیل کمانڈز چلائیں۔ `installDebug` کے لیے منسلک ڈیوائس یا چلتا ہوا ایمولیٹر ضروری ہے۔

Unix جیسے سسٹمز:

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

- `debug`: ترقیاتی بلڈ
- `release`: R8 کے ساتھ بہتر بنایا گیا بلڈ
- `beta`: release پر مبنی بلڈ جس میں آسان debugging کے لیے R8 بند ہے


### مقامی کوڈ کی کمپائلیشن

عام بلڈ کے دوران Gradle، `armeabi-v7a`، `arm64-v8a` اور `x86_64` کے لیے Rust لائبریریاں بناتا ہے اور میزبان کی debug لائبریریوں سے UniFFI Kotlin bindings دوبارہ تیار کرتا ہے۔ ویڈیو اسٹیکرز کے لیے پہلے سے کمپائل شدہ libvpx لائبریریاں `native/vpx/prebuilt` میں شامل ہیں۔ Android کے مقامی کمپائلیشن ٹاسکس براہِ راست چلانے کے لیے:

```sh
./gradlew :native:mtproto:buildNativeMtproto :native:markup:buildNativeMarkup
```

Windows پر `./gradlew` کی جگہ `./gradlew.bat` استعمال کریں۔ `-PskipNativeBuild=true` صرف Kotlin کی تبدیلیوں کے لیے استعمال کریں جب موافق مقامی لائبریریاں اور تیار شدہ bindings پہلے سے موجود ہوں۔ یہ مقامی کمپائلیشن اور bindings کی دوبارہ تیاری دونوں چھوڑ دیتا ہے؛ SDK/NDK کی ترتیب پھر بھی ضروری ہے۔

## ٹیکنالوجیز

- **زبانیں اور پروٹوکول:** Kotlin, Rust, MTProto, UniFFI
- **انٹرفیس اور اسٹیٹ:** Jetpack Compose, Material 3, Decompose, MVIKotlin
- **مشترکہ سروسز:** Koin, Coroutines, Flow, Room
- **میڈیا اور پش:** Media3, Coil, libvpx, tlottie, Firebase Cloud Messaging, UnifiedPush

## منصوبے کی ساخت

| راستہ | ذمہ داری |
|:---|:---|
| `app` | ایپ کا نقطۂ آغاز، dependency injection، نیویگیشن اور پش انضمام |
| `core/*` | مشترکہ ماڈلز، ڈیٹابیس، انٹرفیس، معاون ٹولز اور مارک اپ پروسیسنگ تک رسائی |
| `feature/*` | تصدیقِ شناخت، چیٹ فہرست، گفتگو، فولڈرز، پروفائلز اور ترتیبات |
| `network/bridge` | Kotlin کلائنٹ API، domain mapping اور خرابیوں کی ہینڈلنگ |
| `network/http` | HTTP میڈیا ڈاؤن لوڈز، کیش اور قطار |
| `native/mtproto-rs` | Rust پروٹوکول کلائنٹ اور UniFFI exports |
| `native/markup-rs` | Rust میں Markdown، syntax highlighting اور ریاضیاتی فارمولوں کی parsing |
| `native/mtproto`, `native/markup` | Kotlin facades، تیار شدہ bindings اور مقامی لائبریریاں |
| `native/vpx` | ویڈیو اسٹیکرز کے لیے پہلے سے کمپائل شدہ VP9 decoder |
| `vendor` | تیسرے فریق کا سورس اور submodules |

Feature ماڈیولز `network/bridge` کو کال کرتے ہیں؛ مقامی پروٹوکول کی منطق Rust میں ہے۔ Feature ماڈیولز کو تیار شدہ UniFFI types درآمد نہیں کرنے چاہییں اور نہ ہی دوسرے feature ماڈیولز پر منحصر ہونا چاہیے۔ ترقیاتی اصولوں اور پروٹوکول کے لازمی قواعد کے لیے [AGENTS.md](AGENTS.md) دیکھیں۔

## تعاون

اپنی برانچ `develop` سے بنائیں اور pull request کا ہدف `develop` رکھیں۔ موجودہ ساخت اور کوڈ اسٹائل اپنائیں، تبدیلیاں متعلقہ کام تک محدود رکھیں اور واضح وضاحت کے ساتھ متعلقہ جانچ کے نتائج شامل کریں۔ [Telegram API کی شرائط](https://core.telegram.org/api/terms) کی پابندی کریں۔

تبدیل کیے گئے ماڈیول سے متعلق جانچ چلائیں۔ مثالیں:

```sh
./gradlew :network:bridge:test
cargo test --manifest-path native/mtproto-rs/Cargo.toml
cargo test --manifest-path native/markup-rs/Cargo.toml
git diff --check
```

Windows پر `./gradlew.bat` استعمال کریں۔ صرف اپنی تبدیلی سے متعلق جانچ چلائیں؛ اصل Telegram اور ڈیوائس پر رویے کی تصدیق اپنی اسناد کے ساتھ الگ سے ضروری ہے۔

خرابی کی رپورٹ کے عنوان میں `[Bug]` اور نئی خصوصیت کی درخواست میں `[Feature]` استعمال کریں۔ [بگ ٹریکر](https://github.com/orgs/monogram-android/projects/3/views/1) اور [فیچر بورڈ](https://github.com/orgs/monogram-android/projects/5/views/1) دیکھیں۔

## تراجم

انٹرفیس کے متن `core/ui/src/main/res/values/strings.xml` اور `feature/*/src/main/res/values/strings.xml` فائلوں میں ہیں۔ ہر متعلقہ ماڈیول میں `values-<locale>/strings.xml` شامل یا اپ ڈیٹ کریں (مثلاً `values-de/strings.xml`)۔ وسائل کے نام، formatting placeholders اور جمع کی صورتیں برقرار رکھیں۔ تراجم pull request کے ذریعے بھیجیں۔ ترجمہ شدہ README فائلوں کو انگریزی ورژن کے مطابق رکھیں۔

## لائسنس

Monogram، [GNU General Public License v3.0](LICENSE) کے تحت دستیاب ہے۔ تیسرے فریق کے اجزا اور مقامی crates پر ان کی متعلقہ سورس ڈائریکٹریز میں درج لائسنس لاگو ہوتے ہیں۔
