# monogram-markup

Monogram's text parsing library. It uses Tree-sitter to parse Markdown and
small custom lexical scanners to highlight code, exposing results through
UniFFI. The Android app uses it through `core/markup` and `native/markup`.

## API

| Function | Returns |
| --- | --- |
| `parse_telegram_markdown` | Plain text and Telegram message entities with UTF-16 offsets |
| `render_blocks` | Paragraphs, headings, quotes, code blocks, tables, and rules |
| `highlight_code` | Ordered, non-overlapping syntax spans with UTF-16 offsets |
| `extract_math` | Inline `$...$` and block `$$...$$` math spans |
| `supported_highlight_languages` | Available language identifiers |
| `library_version` | The crate version |

Code highlighting supports Kotlin, C/C++/C#, Python, JavaScript/TypeScript,
Go, Rust, Swift, SQL, JSON, XML/HTML, Java, Bash, Dockerfile, YAML, Markdown,
INI, TOML, properties, PHP, Ruby, Lua, Haskell, R, MATLAB, assembly (`asm`),
Protobuf (`proto`), GraphQL, CSS, SCSS/Sass, regex, nginx, and Caddy. Common
short aliases are accepted case-insensitively. Blank and unknown language names
use Kotlin. JSX/TSX use the JavaScript/TypeScript scanner.

Highlighting is intentionally lexical: comments, strings, numbers, keywords,
identifiers, and punctuation, without full syntax validation, semantic type
resolution, or parsing embedded languages and string interpolation. Some advanced
literal forms use approximate coloring. There are no code-language grammar
dependencies or runtime grammar downloads. Markdown structure parsing and math
extraction are separate and remain available; Kotlin handles math layout.

`render_blocks` interprets Markdown only when requested and when the text has no
server entities. Text already formatted by Telegram keeps its original meaning.
Headings, tables, and details are local display elements, not Telegram message
entities. Inputs larger than 64 KiB or containing more than 4096 entities fall
back to a plain paragraph without losing the original text. Unsupported HTML
and details content also remain as paragraph text.

## Build and test

Run host tests from the repository root:

```console
cargo test --manifest-path native/markup-rs/Cargo.toml
```

Android builds require Rust, `cargo-ndk`, and an Android NDK. The Gradle task
builds `monogram_markup` for `armeabi-v7a`, `arm64-v8a`, and `x86_64`, placing
the libraries in `native/markup/src/main/jniLibs`. On Windows:

```console
./gradlew.bat :native:markup:buildNativeMarkup
```

Use `./gradlew` on Linux or macOS. The app build runs this task automatically;
`-PskipNativeBuild=true` reuses the existing libraries for Kotlin-only changes.
Only Tree-sitter's Markdown grammar compiles its C sources during the Rust build.

To build manually, run this from `native/markup-rs`:

```console
cargo ndk -t armeabi-v7a -t arm64-v8a -t x86_64 -o ../markup/src/main/jniLibs build --release
```

## Kotlin bindings

`:native:markup:buildNativeMarkup` regenerates UniFFI Kotlin into
`native/markup/src/main/java` from the built `libmonogram_markup.so`.
`-PskipNativeBuild=true` skips both the native compile and that generate step.

To regenerate by hand from `native/markup-rs`:

```console
cargo run --features bindgen-cli --bin uniffi-bindgen -- generate --library ../markup/src/main/jniLibs/arm64-v8a/libmonogram_markup.so --language kotlin --out-dir ../markup/src/main/java --no-format
```
