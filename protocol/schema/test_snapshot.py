#!/usr/bin/env python3
"""Focused standard-library tests for snapshot manifest identity rules."""

import json
import unittest

import snapshot


class LogicalIdentityTests(unittest.TestCase):
    def test_allows_equal_layers_across_different_kinds(self) -> None:
        snapshot.validate_logical_identities(
            [
                {"kind": "cloud", "layer": 223},
                {"kind": "secret", "layer": 223},
                {"kind": "transport", "layer": None},
            ]
        )

    def test_rejects_duplicate_kind_and_layer(self) -> None:
        with self.assertRaisesRegex(snapshot.SnapshotError, "duplicate manifest logical identity"):
            snapshot.validate_logical_identities(
                [
                    {"kind": "secret", "layer": 216},
                    {"kind": "secret", "layer": 216},
                ]
            )

    def test_requires_null_transport_layer(self) -> None:
        with self.assertRaisesRegex(snapshot.SnapshotError, "null layer"):
            snapshot.validate_logical_identities([{"kind": "transport", "layer": 1}])

    def test_allows_repeated_declarations_across_documents(self) -> None:
        declaration = {"name": "shared", "id": 1}
        for kind in ("cloud", "secret"):
            source_path = f"schemas/{kind}.tl"
            source_url = f"https://example.invalid/{kind}.tl"
            document = {
                "json_schema": {"type": "object"},
                "schema": {
                    "format_version": 1,
                    "layer": 1,
                    "source": {"name": source_path, "url": source_url},
                    "constructors": [declaration],
                    "functions": [],
                    "finalizations": [],
                    "partial_applications": [],
                },
            }
            data = (json.dumps(document) + "\n").encode()
            snapshot.validate_export(
                data,
                {
                    "path": f"{kind}/layer-1.json",
                    "kind": kind,
                    "layer": 1,
                    "source_tl_path": source_path,
                    "source_url": source_url,
                },
            )


if __name__ == "__main__":
    unittest.main()
