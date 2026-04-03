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

**اسے دوسری زبانوں میں پڑھیں:** [English](README.md), [Русский](README_RU.md)، [한국어](README_KOR.md),
[Español](README_ES.md)

---

**مونوگرام (MonoGram)** اینڈرائیڈ کے لیے ایک جدید، انتہائی تیز، اور شاندار غیر سرکاری ٹیلیگرام کلائنٹ ہے۔ اسے **Jetpack Compose** اور **Material Design 3** کے ساتھ بنایا گیا ہے، اور یہ سرکاری **TDLib** کی مدد سے ایک مقامی (native) اور ہموار تجربہ فراہم کرتا ہے۔

> [!IMPORTANT]
> مونوگرام فی الحال **فعال ترقی (active development)** کے مراحل میں ہے۔ متواتر اپ ڈیٹس، تعمیراتی تبدیلیوں، اور کبھی کبھار بگز کی توقع رکھیں۔

[**Boosty**](https://boosty.to/monogram) پر اس پروجیکٹ کی حمایت کریں۔

---

## اسکرین شاٹس

<div align="center">

| | | | |
|:---:|:---:|:---:|:---:|
| <img src="./documents/1.png" width="180" alt="Screenshot 1" /> | <img src="./documents/2.png" width="180" alt="Screenshot 2" /> | <img src="./documents/3.png" width="180" alt="Screenshot 3" /> | <img src="./documents/4.png" width="180" alt="Screenshot 4" /> |

</div>

---

## اہم خصوصیات

- **Material Design 3** — ایک خوبصورت، موافق (adaptive) UI جو فونز، ٹیبلیٹس، اور فولڈ ایبلز پر بہترین نظر آتا ہے۔
- **محفوظ (Secure)** — بائیو میٹرک لاکنگ اور انکرپٹڈ لوکل اسٹوریج شامل ہے۔
- **میڈیا سے بھرپور (Media Rich)** — ExoPlayer اور Coil 3 کے ساتھ اعلیٰ کارکردگی والا میڈیا پلے بیک۔
- **تیز اور موثر (Fast & Efficient)** — Kotlin Coroutines کی مدد سے چلنے والا اور کارکردگی کے لیے آپٹمائزڈ۔
- **کلین آرکیٹیکچر (Clean Architecture)** — ڈومین (Domain)، ڈیٹا (Data)، اور پریزنٹیشن (Presentation) لیئرز کے ساتھ کام کی واضح تقسیم۔
- **MVI پیٹرن** — MVIKotlin کا استعمال کرتے ہوئے قابل پیشین گوئی اسٹیٹ مینجمنٹ (state management)۔
- **کوئی NFT یا کرپٹو نہیں** — مونوگرام میں کبھی بھی NFT پروموشنز، تحائف یا ٹیلیگرام کی جانب سے پیش کیا گیا کوئی ایسا فیچر شامل نہیں ہوگا جسے ہم میسجنگ ایپ کے دائرہ کار سے باہر سمجھتے ہیں۔

---

## شروعات (Getting Started)

پروجیکٹ کو مقامی طور پر سیٹ اپ کرنے کے لیے ان اقدامات پر عمل کریں۔

### بنیادی شرائط

- **Android Studio**: لیڈی بگ (Ladybug) یا اس سے نیا (تجویز کردہ)۔
- **JDK**: جاوا 17 یا اس سے نیا۔

### 1. ریپوزٹری کلون کریں

```bash
git clone https://github.com/monogram-android/monogram.git
cd monogram
```

### 2. ٹیلیگرام API کیز کنفیگر کریں

ٹیلیگرام سرورز سے جڑنے کے لیے، آپ کو اپنی API اسناد (credentials) درکار ہوں گی۔

1. [my.telegram.org](https://my.telegram.org/) پر لاگ ان کریں۔
2. **API development tools** پر جائیں۔
3. اپنی `App api_id` اور `App api_hash` حاصل کرنے کے لیے ایک نئی ایپلیکیشن بنائیں۔
4. پروجیکٹ کی روٹ ڈائرکٹری میں `local.properties` نام کی ایک فائل بنائیں (اگر یہ پہلے سے موجود نہیں ہے)۔
5. درج ذیل لائنیں شامل کریں:

```properties
API_ID=12345678
API_HASH=your_api_hash_here
```

### 3. پش نوٹیفکیشنز کنفیگر کریں

1. [Firebase console](https://console.firebase.google.com) پر لاگ ان کریں۔
2. ایک نیا پروجیکٹ بنائیں۔
3. اپنی مطلوبہ `applicationId` کے ساتھ ایک نئی ایپلیکیشن شامل کریں۔ اگر آپ کے پاس مختلف IDs کے ساتھ متعدد ایپلیکیشنز ہیں، تو متعدد Firebase ایپلیکیشنز بنائیں۔ **بائی ڈیفالٹ، ڈیبگ اور ریلیز بلڈز کے لیے `applicationId` مختلف ہوتی ہے۔**
4. `google-services.json` فائل ڈاؤن لوڈ کریں اور اسے **app** ماڈیول کی روٹ (`monogram/app/google-services.json`) میں کاپی کریں۔ اگر آپ نے متعدد ایپلیکیشنز بنائی ہیں، تو صرف تازہ ترین کنفیگریشن کاپی کریں۔
5. **Cloud Messaging** سیکشن پر جائیں۔
6. **Manage service accounts** پر کلک کریں۔
7. کھلنے والی ونڈو کے اوپری حصے میں **Keys** سیکشن منتخب کریں۔
8. **Add key** پر کلک کریں اور **JSON** آپشن منتخب کریں۔ فائل کے ڈاؤن لوڈ ہونے کا انتظار کریں۔
9. اس ٹیلیگرام API پیج پر واپس جائیں جہاں سے آپ نے اپنی App ID حاصل کی تھی۔
10. FCM اسناد والے سیکشن کے آگے **Update** پر کلک کریں۔
11. کھلنے والے پیج پر سروس اکاؤنٹ JSON اپ لوڈ کریں۔

### 4. بلڈ اور رن

1. **Android Studio** میں پروجیکٹ کھولیں۔
2. IDE کی انڈیکسنگ کی حد میں اضافہ کریں تاکہ `TdApi.java` (TDLib ریپر) صحیح طرح انڈیکس ہو سکے۔ **Android Studio** یا **IntelliJ IDEA** میں، **Help → Edit Custom Properties...** کھولیں، نیچے دی گئی لائنز پیسٹ کریں، اور اگر کہا جائے تو IDE کو ری اسٹارٹ کریں:

```properties
# size in Kb
idea.max.intellisense.filesize=20480
# size in Kb
idea.max.content.load.filesize=20480
```

3. Gradle کو سنک کریں۔
4. `app` رن کنفیگریشن منتخب کریں۔
5. کوئی ڈیوائس کنیکٹ کریں یا ایمولیٹر اسٹارٹ کریں۔
6. **Run** پر کلک کریں۔

---

## TDLib کی تعمیر (Building TDLib)

اگر آپ کو سورس کوڈ سے TDLib بنانے کی ضرورت ہے، تو پہلے درکار انحصار (dependencies) انسٹال کریں۔ Debian/Ubuntu پر مبنی ڈسٹریبیوشنز کے لیے:

```bash
sudo apt-get update
sudo apt-get install build-essential git curl wget php perl gperf unzip zip default-jdk cmake
```

پھر اپنے پروجیکٹ کی روٹ سے بلڈ اسکرپٹ چلائیں:

```bash
./build-tdlib.sh
```

---

## تعاون (Contributing)

ہم تعاون کا خیرمقدم کرتے ہیں! چاہے وہ بگز ٹھیک کرنا ہو، دستاویزات کو بہتر بنانا ہو، یا نئے فیچرز کی تجویز دینا ہو۔

1. **مسائل (Issues) چیک کریں** — کھلے ہوئے ایشوز تلاش کریں یا اپنے آئیڈیاز پر بحث کرنے کے لیے ایک نیا ایشو بنائیں۔
2. **`develop` سے کام کریں** — اپنی برانچ `develop` سے بنائیں اور اپنا کام اسی برانچ کی بنیاد پر رکھیں۔
3. **فورک اور برانچ (Fork & Branch)** — ریپوزٹری کو فورک کریں اور ایک فیچر برانچ بنائیں۔
4. **کوڈ اسٹائل (Code Style)** — موجودہ Kotlin کوڈنگ اسٹائل اور کلین آرکیٹیکچر کی ہدایات پر عمل کریں۔
5. **PR جمع کرائیں** — اپنی تبدیلیوں کی واضح تفصیل کے ساتھ `develop` برانچ میں ایک Pull Request (PR) کھولیں۔

> [!IMPORTANT]
> - [ٹیلیگرام API کی سروس کی شرائط (Terms of Service)](https://core.telegram.org/api/terms) کا احترام کریں۔
> - یقینی بنائیں کہ آپ کا کوڈ تمام چیکس اور ٹیسٹس پاس کرتا ہے۔

### بگز رپورٹ کرنا اور فیچرز تجویز کرنا

- **بگز (Bugs)** — ایک ایشو کھولیں اور ٹائٹل میں `[Bug]` ٹیگ استعمال کریں (مثال کے طور پر `[Bug] App crashes on startup`)۔ آپ [**بگ ٹریکر (Bug Tracker)**](https://github.com/orgs/monogram-android/projects/3/views/1) پر تمام معلوم بگز بھی دیکھ سکتے ہیں۔
- **فیچر کی درخواستیں (Feature Requests)** — `[Feature]` ٹیگ کے ساتھ ایک ایشو کھولیں (مثال کے طور پر `[Feature] Support scheduled messages`)۔ موجودہ فیچر کی درخواستیں [**فیچر بورڈ (Feature Board)**](https://github.com/orgs/monogram-android/projects/5/views/1) پر مل سکتی ہیں۔

---

## تراجم (Translations)

مونوگرام کمیونٹی کے تراجم کا خیرمقدم کرتا ہے! آپ اسٹرنگز ریسورس فائل میں ترمیم کر کے اپنی زبان کا حصہ ڈال سکتے ہیں۔

سورس اسٹرنگز [`presentation/src/main/res/values/string.xml`](https://github.com/monogram-android/monogram/blob/develop/presentation/src/main/res/values/string.xml) پر واقع ہیں۔ نئی زبان شامل کرنے کے لیے، متعلقہ `values-<locale>/string.xml` فائل بنائیں (مثال کے طور پر جرمن کے لیے `values-de/string.xml`) اور وہاں اسٹرنگز کا ترجمہ کریں۔ اپنے ترجمے کے ساتھ ایک PR کھولیں اور ہم اسے مرج کر دیں گے۔

---

## ٹیکنالوجی اسٹیک (Tech Stack)

مونوگرام اینڈرائیڈ کے جدید ترین ترقیاتی ٹولز اور لائبریریوں کا استعمال کرتا ہے:

| کیٹیگری | لائبریریاں |
|:---|:---|
| **زبان** | [Kotlin](https://kotlinlang.org/) |
| **UI ٹول کٹ** | [Jetpack Compose](https://developer.android.com/jetpack/compose) (Material 3) |
| **آرکیٹیکچر** | [Decompose](https://github.com/arkivanov/Decompose) (Navigation), [MVIKotlin](https://github.com/arkivanov/MVIKotlin) |
| **ڈیپینڈینسی انجیکشن** | [Koin](https://insert-koin.io/) |
| **ایسنک (Async)** | Coroutines & Flow |
| **ٹیلیگرام کور** | [TDLib](https://core.telegram.org/tdlib) (Telegram Database Library) |
| **امیج لوڈنگ** | [Coil 3](https://coil-kt.github.io/coil/) |
| **میڈیا** | Media3 (ExoPlayer) |
| **نقشے (Maps)** | [MapLibre](https://maplibre.org/) |
| **لوکل ڈیٹا بیس** | Room |

---

## پروجیکٹ کا ڈھانچہ (Project Structure)

کام کی تقسیم اور اسکیل ایبلٹی کو یقینی بنانے کے لیے پروجیکٹ ملٹی ماڈیول ڈھانچے کی پیروی کرتا ہے:

| ماڈیول | تفصیل |
|:---|:---|
| **:app** | مرکزی اینڈرائیڈ ایپلیکیشن ماڈیول۔ |
| **:domain** | خالص Kotlin ماڈیول جس میں بزنس لاجک، یوز کیسز (use cases)، اور ریپوزٹری انٹرفیس شامل ہیں۔ |
| **:data** | ریپوزٹریز، ڈیٹا سورسز، اور TDLib انضمام کی عمل درآمد (Implementation)۔ |
| **:presentation** | UI کمپوننٹس، اسکرینز، اور ویو ماڈلز (MVI اسٹورز)۔ |
| **:core** | مشترکہ یوٹیلٹی کلاسز اور ایکسٹینشنز جو تمام ماڈیولز میں استعمال ہوتے ہیں۔ |
| **:baselineprofile** | ایپ کے اسٹارٹ اپ اور کارکردگی کو بہتر بنانے کے لیے بیس لائن پروفائلز (Baseline Profiles)۔ |

---

## لائسنس

یہ پروجیکٹ [**GNU General Public License v3.0**](LICENSE) کے تحت لائسنس یافتہ ہے۔
