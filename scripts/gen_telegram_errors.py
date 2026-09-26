#!/usr/bin/env python3
"""Generate TelegramErrorCatalog.kt from official errors.json."""

from __future__ import annotations

import json
import pathlib
import sys


def kstr(s: str) -> str:
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$") + '"'


def main() -> int:
    src = pathlib.Path(sys.argv[1])
    dest = pathlib.Path(sys.argv[2])
    data = json.loads(src.read_text(encoding="utf-8"))
    errors = data["errors"]
    descs = data.get("descriptions", {})
    layer = int(data.get("layer") or 0)

    rows: list[tuple[int, str, str]] = []
    seen: set[str] = set()
    for code, mapping in sorted(errors.items(), key=lambda kv: int(kv[0])):
        for pattern in sorted(mapping.keys()):
            rows.append((int(code), pattern, descs.get(pattern, "")))
            seen.add(pattern)
    for pattern, desc in sorted(descs.items()):
        if pattern not in seen:
            rows.append((400, pattern, desc))
            seen.add(pattern)

    lines = [
        "package org.monogram.core.common.telegram",
        "",
        "/**",
        f" * Compact ingest of https://core.telegram.org/api/errors.json (layer {layer}).",
        " * Method lists are omitted; codes, patterns, and official descriptions are kept.",
        " * Regenerated from the official JSON; do not hand-edit rows.",
        " */",
        "internal object TelegramErrorCatalog {",
        f"    const val LAYER: Int = {layer}",
        f"    const val SIZE: Int = {len(rows)}",
        "    val rows: Array<Row> = arrayOf(",
    ]
    for code, pattern, desc in rows:
        lines.append(f"        Row({code}, {kstr(pattern)}, {kstr(desc)}),")
    lines.extend(
        [
            "    )",
            "",
            "    data class Row(val httpCode: Int, val pattern: String, val description: String)",
            "}",
            "",
        ]
    )
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_text("\n".join(lines), encoding="utf-8")
    print(f"wrote {dest} rows={len(rows)} layer={layer} bytes={dest.stat().st_size}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
