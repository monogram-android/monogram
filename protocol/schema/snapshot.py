#!/usr/bin/env python3
"""Export and verify Monogram's pinned Telegram TL schema snapshots."""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import math
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import tomllib
from typing import Any, Callable

PINNED_TELLERS_COMMIT = "31ed4e03e74188e342160951493ae75e25efccc6"
EXPORTER_PACKAGE = "tellers-tl-json"
EXPORTER_VERSION = "0.1.0"
INTERCHANGE_FORMAT_VERSION = 1
MANIFEST_FORMAT_VERSION = 1
CLOUD_LAYER = 223
SECRET_CHAT_LAYERS = (8, 17, 20, 23, 45, 46, 66, 73, 101, 143, 144, 216)
RFC3339_UTC = re.compile(
    r"^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\.[0-9]+)?Z$"
)
SCRIPT_DIR = Path(__file__).resolve().parent
REPOSITORY_ROOT = SCRIPT_DIR.parents[1]
ROOT_SUBMODULE = (REPOSITORY_ROOT / "tools" / "tellers-tl").resolve()
MANIFEST_KEYS = ("format_version", "schemas")
ENTRY_KEYS = (
    "path",
    "kind",
    "layer",
    "source_tl_path",
    "source_url",
    "source_sha256",
    "exported_json_sha256",
    "tellers_commit",
    "exporter_package",
    "exporter_version",
    "interchange_format_version",
    "export_command",
    "generated_at",
)


class SnapshotError(RuntimeError):
    """A reproducibility contract violation."""


def fail(message: str) -> None:
    raise SnapshotError(message)


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def reject_constant(value: str) -> None:
    fail(f"non-finite JSON value is forbidden: {value}")


def unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            fail(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def strict_json(data: bytes, label: str) -> dict[str, Any]:
    validate_text_bytes(data, label)
    try:
        value = json.loads(
            data.decode("utf-8"),
            object_pairs_hook=unique_object,
            parse_constant=reject_constant,
        )
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        fail(f"{label}: invalid strict UTF-8 JSON: {error}")
    if not isinstance(value, dict):
        fail(f"{label}: top-level JSON value must be an object")
    reject_non_finite(value, label)
    return value


def reject_non_finite(value: Any, label: str) -> None:
    if isinstance(value, float) and not math.isfinite(value):
        fail(f"{label}: non-finite JSON number")
    if isinstance(value, dict):
        for child in value.values():
            reject_non_finite(child, label)
    elif isinstance(value, list):
        for child in value:
            reject_non_finite(child, label)


def validate_text_bytes(data: bytes, label: str) -> None:
    if data.startswith(b"\xef\xbb\xbf"):
        fail(f"{label}: UTF-8 BOM is forbidden")
    if b"\r" in data:
        fail(f"{label}: CR bytes are forbidden; use an LF-preserving checkout")
    if not data.endswith(b"\n"):
        fail(f"{label}: exactly one terminal LF is required")
    if data.endswith(b"\n\n"):
        fail(f"{label}: more than one terminal LF")
    try:
        data.decode("utf-8")
    except UnicodeDecodeError as error:
        fail(f"{label}: not valid UTF-8: {error}")


def run(
    arguments: list[str],
    *,
    cwd: Path,
    env: dict[str, str] | None = None,
    binary: bool = False,
) -> bytes | str:
    completed = subprocess.run(
        arguments,
        cwd=cwd,
        env=env,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if completed.returncode != 0:
        stderr = completed.stderr.decode("utf-8", errors="replace").strip()
        fail(f"command failed ({completed.returncode}): {' '.join(arguments)}\n{stderr}")
    if binary:
        return completed.stdout
    try:
        return completed.stdout.decode("utf-8").strip()
    except UnicodeDecodeError as error:
        fail(f"command emitted non-UTF-8 text: {' '.join(arguments)}: {error}")


def validate_timestamp(value: str) -> str:
    if not RFC3339_UTC.fullmatch(value):
        fail("generated_at must be RFC 3339 UTC with seconds and a trailing Z")
    try:
        dt.datetime.fromisoformat(value[:-1] + "+00:00")
    except ValueError as error:
        fail(f"invalid generated_at timestamp: {error}")
    return value


def verify_tool_checkout(tool_dir: Path) -> None:
    tool_dir = tool_dir.resolve()
    if tool_dir == ROOT_SUBMODULE:
        fail("the root tools/tellers-tl checkout is read-only; use a temporary LF clone")
    if not (tool_dir / ".git").is_dir():
        fail(f"tool directory is not a standalone Git clone: {tool_dir}")
    head = run(["git", "rev-parse", "HEAD"], cwd=tool_dir)
    if head != PINNED_TELLERS_COMMIT:
        fail(f"tellers-tl HEAD is {head}, expected {PINNED_TELLERS_COMMIT}")
    status = run(
        ["git", "status", "--porcelain=v1", "--untracked-files=all"], cwd=tool_dir
    )
    if status:
        fail(f"tellers-tl checkout is not clean:\n{status}")
    autocrlf = run(["git", "config", "--get", "core.autocrlf"], cwd=tool_dir)
    if autocrlf.lower() not in ("false", "input"):
        fail(
            "temporary tellers-tl clone must set core.autocrlf=false (or input) "
            f"before checkout; observed {autocrlf or '<unset>'}"
        )
    verify_exporter_metadata(tool_dir)


def verify_exporter_metadata(tool_dir: Path) -> None:
    try:
        workspace = tomllib.loads((tool_dir / "Cargo.toml").read_text(encoding="utf-8"))
        package = tomllib.loads(
            (tool_dir / "crates" / "tl-json" / "Cargo.toml").read_text(encoding="utf-8")
        )
    except (OSError, tomllib.TOMLDecodeError) as error:
        fail(f"cannot read pinned exporter metadata: {error}")
    if workspace.get("workspace", {}).get("package", {}).get("version") != EXPORTER_VERSION:
        fail("workspace exporter version does not match the pinned version")
    package_data = package.get("package", {})
    if package_data.get("name") != EXPORTER_PACKAGE or not package_data.get("version", {}).get(
        "workspace"
    ):
        fail("pinned tellers-tl-json package metadata is unexpected")


def load_source_manifest(tool_dir: Path) -> dict[str, Any]:
    path = tool_dir / "schemas" / "manifest.json"
    manifest = strict_json(path.read_bytes(), path.as_posix())
    layers = manifest.get("layers")
    transport = manifest.get("transport")
    secret = manifest.get("secret_chat")
    if not isinstance(layers, dict) or not isinstance(transport, dict) or not isinstance(secret, dict):
        fail("pinned schemas/manifest.json has an unexpected shape")
    cloud = layers.get(str(CLOUD_LAYER))
    if not isinstance(cloud, dict) or cloud.get("layer") != CLOUD_LAYER:
        fail(f"pinned source manifest has no cloud layer {CLOUD_LAYER}")
    secret_layers = secret.get("layers")
    if not isinstance(secret_layers, dict):
        fail("pinned source manifest has no Secret Chat layer map")
    observed = tuple(sorted(int(layer) for layer in secret_layers))
    if observed != SECRET_CHAT_LAYERS:
        fail(f"Secret Chat layers are {observed}, expected {SECRET_CHAT_LAYERS}")
    if secret.get("current_layer") != SECRET_CHAT_LAYERS[-1]:
        fail("pinned source manifest current Secret Chat layer is unexpected")
    return manifest


def schema_specs(source_manifest: dict[str, Any]) -> list[dict[str, Any]]:
    layers = source_manifest["layers"]
    transport = source_manifest["transport"]
    secret = source_manifest["secret_chat"]
    specs: list[dict[str, Any]] = [
        {
            "path": f"cloud/layer-{CLOUD_LAYER}.json",
            "kind": "cloud",
            "layer": CLOUD_LAYER,
            "source_tl_path": f"schemas/layers/{CLOUD_LAYER}/api.tl",
            "source_url": layers[str(CLOUD_LAYER)]["url"],
            "source_sha256": layers[str(CLOUD_LAYER)]["sha256"],
        },
        {
            "path": "transport/mtproto.json",
            "kind": "transport",
            "layer": None,
            "source_tl_path": "schemas/upstream/mtproto.tl",
            "source_url": transport["url"],
            "source_sha256": transport["sha256"],
        },
    ]
    for layer in SECRET_CHAT_LAYERS:
        specs.append(
            {
                "path": f"secret-chat/layer-{layer}.json",
                "kind": "secret",
                "layer": layer,
                "source_tl_path": f"schemas/secret-chat/layers/{layer}.tl",
                "source_url": secret["url"],
                "source_sha256": secret["layers"][str(layer)],
            }
        )
    specs.sort(key=lambda spec: spec["path"].encode("utf-8"))
    return specs


def validate_source(tool_dir: Path, spec: dict[str, Any]) -> bytes:
    source_path = tool_dir / Path(spec["source_tl_path"])
    if not source_path.is_file():
        fail(f"missing source TL file: {spec['source_tl_path']}")
    data = source_path.read_bytes()
    validate_text_bytes(data, spec["source_tl_path"])
    actual_hash = sha256(data)
    if actual_hash != spec["source_sha256"]:
        fail(
            f"source hash mismatch for {spec['source_tl_path']}: "
            f"{actual_hash} != {spec['source_sha256']}"
        )
    if spec["layer"] is not None:
        marker = f"// LAYER {spec['layer']}\n".encode()
        if not data.startswith(marker):
            fail(f"{spec['source_tl_path']} does not declare layer {spec['layer']}")
    return data


def export_command(spec: dict[str, Any]) -> list[str]:
    return [
        "cargo",
        "run",
        "--locked",
        "--offline",
        "-p",
        EXPORTER_PACKAGE,
        "--",
        spec["source_tl_path"],
        "--source-url",
        spec["source_url"],
    ]


def export_command_text(spec: dict[str, Any]) -> str:
    return " ".join(export_command(spec))


def validate_export(data: bytes, spec: dict[str, Any]) -> dict[str, Any]:
    document = strict_json(data, spec["path"])
    if tuple(document) != ("json_schema", "schema"):
        fail(f"{spec['path']}: top-level keys/order must be json_schema, schema")
    if not isinstance(document["json_schema"], dict) or not document["json_schema"]:
        fail(f"{spec['path']}: json_schema must be a non-empty object")
    schema = document["schema"]
    if not isinstance(schema, dict):
        fail(f"{spec['path']}: schema must be an object")
    if schema.get("format_version") != INTERCHANGE_FORMAT_VERSION:
        fail(f"{spec['path']}: interchange format version must be 1")
    if schema.get("layer") != spec["layer"]:
        fail(
            f"{spec['path']}: schema layer {schema.get('layer')} != expected {spec['layer']}"
        )
    source = schema.get("source")
    expected_source = {"name": spec["source_tl_path"], "url": spec["source_url"]}
    if source != expected_source:
        fail(f"{spec['path']}: source metadata is {source!r}, expected {expected_source!r}")
    declaration_keys = (
        "constructors",
        "functions",
        "finalizations",
        "partial_applications",
    )
    declarations = 0
    for key in declaration_keys:
        values = schema.get(key)
        if not isinstance(values, list):
            fail(f"{spec['path']}: schema.{key} must be an array")
        declarations += len(values)
    if declarations == 0:
        fail(f"{spec['path']}: declaration set is empty")
    return document


def cargo_environment() -> dict[str, str]:
    environment = os.environ.copy()
    environment["CARGO_NET_OFFLINE"] = "true"
    return environment


def build_package(
    tool_dir: Path, generated_at: str
) -> tuple[dict[str, bytes], bytes, list[dict[str, Any]]]:
    source_manifest = load_source_manifest(tool_dir)
    specs = schema_specs(source_manifest)
    artifacts: dict[str, bytes] = {}
    entries: list[dict[str, Any]] = []
    environment = cargo_environment()
    for spec in specs:
        validate_source(tool_dir, spec)
        command = export_command(spec)
        data = run(command, cwd=tool_dir, env=environment, binary=True)
        assert isinstance(data, bytes)
        validate_export(data, spec)
        artifacts[spec["path"]] = data
        entries.append(
            {
                "path": spec["path"],
                "kind": spec["kind"],
                "layer": spec["layer"],
                "source_tl_path": spec["source_tl_path"],
                "source_url": spec["source_url"],
                "source_sha256": spec["source_sha256"],
                "exported_json_sha256": sha256(data),
                "tellers_commit": PINNED_TELLERS_COMMIT,
                "exporter_package": EXPORTER_PACKAGE,
                "exporter_version": EXPORTER_VERSION,
                "interchange_format_version": INTERCHANGE_FORMAT_VERSION,
                "export_command": export_command_text(spec),
                "generated_at": generated_at,
            }
        )
    manifest = {"format_version": MANIFEST_FORMAT_VERSION, "schemas": entries}
    manifest_bytes = (json.dumps(manifest, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    validate_manifest(manifest_bytes, tool_dir, artifacts=artifacts)
    return artifacts, manifest_bytes, entries


def expected_json_paths() -> set[str]:
    return {
        f"cloud/layer-{CLOUD_LAYER}.json",
        "transport/mtproto.json",
        *(f"secret-chat/layer-{layer}.json" for layer in SECRET_CHAT_LAYERS),
        "manifest.json",
    }


def validate_artifact_structure(schema_dir: Path) -> None:
    observed = {
        path.relative_to(schema_dir).as_posix()
        for path in schema_dir.rglob("*.json")
        if path.is_file()
    }
    expected = expected_json_paths()
    if observed != expected:
        fail(
            "schema JSON artifact set mismatch; "
            f"missing={sorted(expected - observed)}, unexpected={sorted(observed - expected)}"
        )


def validate_logical_identities(entries: list[dict[str, Any]]) -> None:
    identities: set[tuple[str, int | None]] = set()
    for entry in entries:
        kind = entry.get("kind")
        layer = entry.get("layer")
        if kind == "transport":
            if layer is not None:
                fail("transport manifest identity must use a null layer")
        elif kind in ("cloud", "secret"):
            if not isinstance(layer, int) or isinstance(layer, bool):
                fail(f"{kind} manifest identity must use an integer layer")
        else:
            fail(f"unknown manifest schema kind: {kind!r}")
        identity = (kind, layer)
        if identity in identities:
            fail(f"duplicate manifest logical identity: {identity!r}")
        identities.add(identity)


def validate_manifest(
    data: bytes,
    tool_dir: Path,
    *,
    artifacts: dict[str, bytes] | None = None,
) -> dict[str, Any]:
    manifest = strict_json(data, "manifest.json")
    if tuple(manifest) != MANIFEST_KEYS:
        fail("manifest.json top-level keys/order must be format_version, schemas")
    if manifest["format_version"] != MANIFEST_FORMAT_VERSION:
        fail("manifest.json format_version must be 1")
    entries = manifest["schemas"]
    if not isinstance(entries, list) or len(entries) != 14:
        fail("manifest.json must contain exactly 14 schema entries")
    paths = [entry.get("path") if isinstance(entry, dict) else None for entry in entries]
    if any(not isinstance(path, str) for path in paths):
        fail("manifest schema paths must be strings")
    if paths != sorted(paths, key=lambda value: value.encode("utf-8")):
        fail("manifest schemas must be sorted by UTF-8 ordinal logical path")
    if len(set(paths)) != len(paths):
        fail("manifest contains duplicate logical paths")
    validate_logical_identities(entries)
    source_manifest = load_source_manifest(tool_dir)
    expected_specs = {spec["path"]: spec for spec in schema_specs(source_manifest)}
    timestamps: set[str] = set()
    for entry in entries:
        if not isinstance(entry, dict) or tuple(entry) != ENTRY_KEYS:
            fail(f"manifest entry has unexpected keys/order: {entry!r}")
        path = entry["path"]
        spec = expected_specs.get(path)
        if spec is None:
            fail(f"manifest contains unexpected schema path: {path}")
        expected_fields = {
            "kind": spec["kind"],
            "layer": spec["layer"],
            "source_tl_path": spec["source_tl_path"],
            "source_url": spec["source_url"],
            "source_sha256": spec["source_sha256"],
            "tellers_commit": PINNED_TELLERS_COMMIT,
            "exporter_package": EXPORTER_PACKAGE,
            "exporter_version": EXPORTER_VERSION,
            "interchange_format_version": INTERCHANGE_FORMAT_VERSION,
            "export_command": export_command_text(spec),
        }
        for key, expected in expected_fields.items():
            if entry[key] != expected:
                fail(f"manifest {path}.{key} is {entry[key]!r}, expected {expected!r}")
        validate_source(tool_dir, spec)
        timestamp = validate_timestamp(entry["generated_at"])
        timestamps.add(timestamp)
        artifact = artifacts[path] if artifacts is not None else (SCRIPT_DIR / path).read_bytes()
        validate_export(artifact, spec)
        if sha256(artifact) != entry["exported_json_sha256"]:
            fail(f"exported JSON hash mismatch for {path}")
    if set(paths) != set(expected_specs):
        fail("manifest does not contain the exact required artifact set")
    if len(timestamps) != 1:
        fail("every manifest entry must reuse one generated_at timestamp")
    return manifest


def atomic_write(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "wb") as output:
            output.write(data)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
    finally:
        if temporary.exists():
            temporary.unlink()


def compare_packages(
    first_artifacts: dict[str, bytes],
    first_manifest: bytes,
    second_artifacts: dict[str, bytes],
    second_manifest: bytes,
    label: str,
) -> None:
    if first_artifacts.keys() != second_artifacts.keys():
        fail(f"{label}: artifact path sets differ")
    for path in first_artifacts:
        if first_artifacts[path] != second_artifacts[path]:
            fail(f"{label}: non-deterministic bytes for {path}")
    if first_manifest != second_manifest:
        fail(f"{label}: reconstructed manifest bytes differ")


def export_snapshots(tool_dir: Path, generated_at: str) -> None:
    generated_at = validate_timestamp(generated_at)
    verify_tool_checkout(tool_dir)
    unexpected = {
        path.relative_to(SCRIPT_DIR).as_posix()
        for path in SCRIPT_DIR.rglob("*.json")
        if path.is_file()
    } - expected_json_paths()
    if unexpected:
        fail(f"refusing to overwrite a directory with unexpected JSON files: {sorted(unexpected)}")
    first_artifacts, first_manifest, _ = build_package(tool_dir, generated_at)
    second_artifacts, second_manifest, _ = build_package(tool_dir, generated_at)
    compare_packages(
        first_artifacts,
        first_manifest,
        second_artifacts,
        second_manifest,
        "second export",
    )
    for path in sorted(first_artifacts, key=lambda value: value.encode("utf-8")):
        atomic_write(SCRIPT_DIR / path, first_artifacts[path])
    atomic_write(SCRIPT_DIR / "manifest.json", first_manifest)
    validate_artifact_structure(SCRIPT_DIR)
    validate_manifest(first_manifest, tool_dir)
    print(
        f"exported {len(first_artifacts)} schemas; "
        f"manifest sha256={sha256(first_manifest)}"
    )


def verify_snapshots(tool_dir: Path, reexport: bool) -> None:
    verify_tool_checkout(tool_dir)
    validate_artifact_structure(SCRIPT_DIR)
    committed_manifest_bytes = (SCRIPT_DIR / "manifest.json").read_bytes()
    manifest = validate_manifest(committed_manifest_bytes, tool_dir)
    if reexport:
        timestamp = manifest["schemas"][0]["generated_at"]
        artifacts, reconstructed_manifest, _ = build_package(tool_dir, timestamp)
        committed_artifacts = {
            entry["path"]: (SCRIPT_DIR / entry["path"]).read_bytes()
            for entry in manifest["schemas"]
        }
        compare_packages(
            committed_artifacts,
            committed_manifest_bytes,
            artifacts,
            reconstructed_manifest,
            "re-export",
        )
    print(
        f"verified {len(manifest['schemas'])} schemas"
        f"{' with byte-identical re-export' if reexport else ''}; "
        f"manifest sha256={sha256(committed_manifest_bytes)}"
    )


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__)
    commands = result.add_subparsers(dest="mode", required=True)
    export = commands.add_parser("export", help="export all pinned snapshots twice and write atomically")
    export.add_argument("--tool-dir", required=True, type=Path, help="clean standalone LF tellers-tl clone")
    export.add_argument("--generated-at", required=True, help="explicit RFC 3339 UTC provenance timestamp")
    verify = commands.add_parser("verify", help="strictly verify committed snapshots and manifest")
    verify.add_argument("--tool-dir", required=True, type=Path, help="clean standalone LF tellers-tl clone")
    verify.add_argument("--reexport", action="store_true", help="offline re-export and byte-compare all outputs")
    return result


def main() -> int:
    arguments = parser().parse_args()
    try:
        if arguments.mode == "export":
            export_snapshots(arguments.tool_dir, arguments.generated_at)
        else:
            verify_snapshots(arguments.tool_dir, arguments.reexport)
    except (OSError, SnapshotError, KeyError, TypeError, ValueError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
