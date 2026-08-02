#!/usr/bin/env python3

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from t7_target_preparation import (
    ContractError,
    _status,
    build_current_report,
    load_json_strict,
    recorded_selectors,
    render_json,
    render_markdown,
)


BASE = Path(__file__).resolve().parent
REPO = BASE.parents[2]


class T7TargetPreparationTest(unittest.TestCase):
    def test_current_repository_report_is_preparation_only(self) -> None:
        report = build_current_report(REPO)

        self.assertEqual("t7-guance-contract-preparation.v1", report["contractVersion"])
        self.assertEqual(
            {
                "reviewedSelectorCount": 146,
                "automatableCandidateCount": 30,
                "alreadyRecordedCount": 1,
                "frozenAwaitingRuntimeValidationCount": 0,
                "blockedSourceQualityCount": 1,
                "needsOwnerContractCount": 28,
            },
            report["counts"],
        )
        self.assertFalse(report["authorization"]["canAcceptT7"])
        self.assertEqual("PREPARATION_ONLY", report["authorization"]["kind"])

        by_selector = {row["selectorKey"]: row for row in report["queue"]}
        self.assertEqual("ALREADY_RECORDED", by_selector["csdp:IM1010"]["status"])
        self.assertEqual(
            "BLOCKED_SOURCE_QUALITY", by_selector["csdp:101014"]["status"]
        )
        self.assertEqual(
            "NEEDS_OWNER_CONTRACT", by_selector["csdp:901002"]["status"]
        )
        self.assertIn(
            "server_query_contract",
            by_selector["csdp:901002"]["missingRequirements"],
        )
        self.assertIn(
            "current_binding_refs",
            by_selector["csdp:901002"]["missingRequirements"],
        )

    def test_report_does_not_expose_raw_logs_or_dql(self) -> None:
        payload = render_json(build_current_report(REPO))

        self.assertNotIn('"logSignature":', payload)
        self.assertNotIn('"evidenceDql":', payload)
        self.assertNotIn("D::", payload)
        self.assertNotIn("DF-API-KEY", payload)

    def test_wrong_recorded_seed_field_is_rejected_instead_of_counting_zero(self) -> None:
        with self.assertRaisesRegex(ContractError, "recordedEvidenceSeeds"):
            recorded_selectors({"recordedEvidenceSeed": []})

    def test_recorded_suite_root_version_is_fail_closed(self) -> None:
        with self.assertRaisesRegex(ContractError, "recorded suite root"):
            recorded_selectors(
                {"version": 999, "suites": [], "recordedEvidenceSeeds": []}
            )

    def test_source_quality_blocker_cannot_be_hidden_by_frozen_catalog(self) -> None:
        self.assertEqual(
            "BLOCKED_SOURCE_QUALITY",
            _status(
                "csdp:101014",
                recorded=set(),
                frozen={"csdp:101014"},
                blockers=["KEY_COLLISION"],
            ),
        )

    def test_strict_json_rejects_duplicate_keys_and_trailing_roots(self) -> None:
        for raw in (
            '{"contractVersion":"evil","contractVersion":"good"}',
            '{"contractVersion":"good"}{"ignored":true}',
        ):
            with self.subTest(raw=raw), tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / "bad.json"
                path.write_text(raw, encoding="utf-8")
                with self.assertRaises(ContractError):
                    load_json_strict(path)

    def test_committed_owner_queue_is_generated_from_current_sources(self) -> None:
        report = build_current_report(REPO)

        self.assertEqual(
            render_json(report),
            (REPO / "docs/intelligent-troubleshooting/t7-target-contract-preparation.json")
            .read_text(encoding="utf-8"),
        )
        self.assertEqual(
            render_markdown(report),
            (REPO / "docs/intelligent-troubleshooting/t7-target-contract-preparation.md")
            .read_text(encoding="utf-8"),
        )


if __name__ == "__main__":
    unittest.main()
