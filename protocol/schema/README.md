# Telegram TL Schema Inputs

This directory is the committed reproducibility boundary for Telegram TL JSON consumed by the Kotlin generator. It contains 14 exporter-produced snapshots: cloud API layer 223, the layerless MTProto transport schema, and cumulative Secret Chat layers 8, 17, 20, 23, 45, 46, 66, 73, 101, 143, 144, and 216.

Normal Gradle compile, test, and assemble tasks read only these committed files. They do not invoke Cargo, initialize or update `tools/tellers-tl`, fetch schemas, or access the network. Once Gradle dependencies are cached, schema validation, Kotlin generation, generation verification, and MTProto compilation also run with Gradle's `--offline` flag.

## Gradle Codegen Contract

The Gradle task bridge invokes `org.monogram.tools.tl.codegen.gradle.TlCodegenTaskCli`. The canonical module tasks are:

```text
:mtproto:validateTelegramTlSchemas
:mtproto:generateTelegramTlKotlin
:mtproto:verifyTelegramTlGeneration
```

Android variant compilation depends on generation through the Android Components generated-source API. Generated output has the exact repository-relative root `mtproto/build/generated/source/tl/main/kotlin`. It is build output and is not committed. For all 14 pinned schema identities, generation emits deterministic Kotlin declarations, constructor and method-result codecs, family codecs, and one constructor registry per identity. It also emits these three deterministic JSON reports at the generated root:

```text
mtproto/build/generated/source/tl/main/kotlin/tl-declaration-manifest.json
mtproto/build/generated/source/tl/main/kotlin/tl-name-collisions.json
mtproto/build/generated/source/tl/main/kotlin/tl-codec-coverage.json
```

Builtin primitive, universal-vector, and repetition pseudo-constructors are
the accepted D-023 exception: they stay handwritten runtime capabilities and
are excluded from generated registries. Every concrete constructor and
function remains generated and covered; unapproved builtin/repetition
lookalikes fail validation.

Validation writes `mtproto/build/reports/tl/schema-validation.json`, generation writes `mtproto/build/reports/tl/generation.json`, and byte-for-byte regeneration verification writes `mtproto/build/reports/tl/generation-verification.json`. `verifyTelegramTlGeneration` fails when a generated file is missing, unexpected, or differs by bytes.

The codegen test task has deliberate hard limits: a 1 GiB maximum heap, one parallel fork, a fresh fork for each test class, fail-fast behavior, and a 45-minute task timeout. The full-schema acceptance campaign has a 35-minute deadline, each K2 compiler partition has a 3-minute cap, and compiler diagnostics are bounded. These limits must not be relaxed merely to conceal a generation or compiler regression.

## Prerequisites And LF Clone

Use a standalone temporary clone at the pinned commit. The repository submodule checkout is read-only for this workflow. On Windows, a global `core.autocrlf=true` gives the submodule working tree CRLF bytes even though its Git object bytes are LF. Do not edit or regenerate the submodule to conceal that mismatch.

From the repository root in PowerShell:

```powershell
$TellersCommit = "31ed4e03e74188e342160951493ae75e25efccc6"
$LfClone = Join-Path $env:TEMP "monogram-tellers-tl-31ed4e0"
git -c core.autocrlf=false clone --no-local --no-checkout .\tools\tellers-tl $LfClone
git -C $LfClone config core.autocrlf false
git -C $LfClone checkout --detach $TellersCommit
git -C $LfClone rev-parse HEAD
git -C $LfClone status --short
```

The printed SHA must be the full pinned commit and status must be empty. If the destination already exists, use a new empty temporary path; do not reuse an unverifiable checkout.

Cargo may access the network only to obtain locked Rust dependencies before reproducibility verification. It never fetches Telegram schemas:

```powershell
Push-Location $LfClone
$env:CARGO_NET_OFFLINE = "false"
cargo fetch --locked
$env:CARGO_NET_OFFLINE = "true"
cargo xtask schemas verify
cargo xtask generate --check
Pop-Location
```

After `cargo fetch --locked`, keep Cargo offline. The snapshot tool also sets `CARGO_NET_OFFLINE=true` and invokes every export with both `--locked` and `--offline`. It never runs `schemas sync-current`, writes into the clone, or mutates the root submodule.

## Export And Verify

Supply one explicit RFC 3339 UTC timestamp. It is provenance only and is reused for all entries and both deterministic export passes:

```powershell
$GeneratedAt = "2026-01-01T00:00:00Z" # replace with the one review timestamp
python protocol/schema/snapshot.py export --tool-dir $LfClone --generated-at $GeneratedAt
python protocol/schema/snapshot.py verify --tool-dir $LfClone --reexport
python protocol/schema/test_snapshot.py
```

`export` validates the clean pinned clone and source manifest, captures exporter stdout directly as binary bytes, exports every schema twice, byte-compares both packages, and atomically writes each snapshot followed by `manifest.json`. It does not use `--output`, shell redirection, `--compact`, or a JSON formatter. `verify --reexport` reuses the committed timestamp, reconstructs the manifest, and byte-compares every committed artifact and manifest byte.

The exporter command recorded per entry is exact and uses a relative TL path so `schema.source.name` is machine-independent:

```text
cargo run --locked --offline -p tellers-tl-json -- <source_tl_path> --source-url <source_url>
```

All source, snapshot, and manifest bytes must be UTF-8 without BOM, contain no CR bytes, and end with exactly one LF. Strict JSON parsing rejects duplicate object keys and non-finite values. Generated snapshot object and declaration order are the pinned exporter's order and must never be hand-edited.

## Manifest Contract

`manifest.json` is pretty JSON with exactly these top-level fields in this order:

```json
{
  "format_version": 1,
  "schemas": [
    {
      "path": "cloud/layer-223.json",
      "kind": "cloud",
      "layer": 223,
      "source_tl_path": "schemas/layers/223/api.tl",
      "source_url": "https://core.telegram.org/schema?raw=1",
      "source_sha256": "<sha256 of canonical LF source bytes>",
      "exported_json_sha256": "<sha256 of final exporter stdout bytes>",
      "tellers_commit": "31ed4e03e74188e342160951493ae75e25efccc6",
      "exporter_package": "tellers-tl-json",
      "exporter_version": "0.1.0",
      "interchange_format_version": 1,
      "export_command": "cargo run --locked --offline -p tellers-tl-json -- schemas/layers/223/api.tl --source-url https://core.telegram.org/schema?raw=1",
      "generated_at": "2026-01-01T00:00:00Z"
    }
  ]
}
```

The `schemas` array is sorted by the UTF-8 ordinal bytes of `path`, not by numeric layer. Logical identity is the unique pair `(kind, layer)`, where kind is `cloud`, `transport`, or `secret`. Transport has a JSON `null` layer. Secret Chat artifact paths remain below `secret-chat/`, but their manifest kind is `secret`. Equal numeric layers are valid across different kinds, and constructor IDs or names may repeat across different schema documents; declarations are scoped to their artifact.

Every snapshot has exactly the top-level keys `json_schema`, then `schema`. The schema has interchange `format_version` 1, its expected layer (`null` for transport), the relative source TL path and canonical URL, and a non-empty declaration set. Source URLs and canonical LF source hashes come from the pinned `tools/tellers-tl/schemas/manifest.json`.

A schema refresh is explicit and separate from ordinary builds. The canonical root task is `refreshTelegramTlSchemas`; the optional singular `refreshTelegramTlSchema` task is only an alias. Both require the caller to provide the pinned LF-preserving checkout and one review timestamp:

```powershell
.\gradlew.bat refreshTelegramTlSchemas `
  -PtellersTlDir=$LfClone `
  -PtelegramTlGeneratedAt=$GeneratedAt
```

After the explicit refresh, run the existing `verify --reexport` and snapshot-test commands above, then review schema, declaration, codec, registry, and provenance changes. A future refresh must deliberately update the tool pin or selected inputs, regenerate all affected snapshots and manifest together, and review declaration and provenance changes. Neither refresh task is a dependency of ordinary validation, generation, verification, compile, test, or assemble tasks.
