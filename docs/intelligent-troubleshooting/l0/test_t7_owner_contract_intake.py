#!/usr/bin/env python3

import copy
import sys
import unittest
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from t7_owner_contract_intake import (
    OwnerInputError,
    build_current_recommended_worksheet,
    build_current_template,
    render_json,
    validate_owner_input,
)


BASE = Path(__file__).resolve().parent
REPO = BASE.parents[2]
AS_OF = datetime(2026, 8, 2, 12, 0, 0, tzinfo=timezone.utc)


class T7OwnerContractIntakeTest(unittest.TestCase):
    def _completed(self, selected: int = 20) -> dict:
        document = copy.deepcopy(build_current_template(REPO))
        for index, row in enumerate(document["contracts"]):
            if index >= selected:
                continue
            suffix = str(index + 1)
            row["selectedForWindow"] = True
            row["ownerContract"] = {
                "ownerTeam": "team-" + suffix,
                "ownerLevel": "P0",
                "ownerScenario": "owner reviewed scenario " + suffix,
                "verifiedRuntimeService": "service-" + suffix,
                "candidateReference": "candidate-ref-" + suffix,
                "serverQueryContractReference": "query-contract-" + suffix,
                "safeSearchTerm": "ERR_" + suffix,
                "window": "-15m",
                "anomalyCriterionReference": "criterion-ref-" + suffix,
                "diagnosisRuleReference": "rule-ref-" + suffix,
                "bindingRefs": {
                    "log_search": "search-binding",
                    "log_trace_bundle": "trace-binding",
                    "contrast_sample": "contrast-binding",
                },
                "historicalOccurredAt": "2026-07-01T00:00:00Z",
                "historicalSourceReference": "incident-ref-" + suffix,
            }
        return document

    def test_template_is_preparation_only_and_prioritized(self) -> None:
        document = build_current_template(REPO)
        by_selector = {
            row["selectorKey"]: row for row in document["contracts"]
        }

        self.assertEqual("t7-owner-contract-intake.v1", document["contractVersion"])
        self.assertEqual(
            {"A_HINTED": 15, "B_CONTEXT_ONLY": 2, "C_SOURCE_GAPS": 11},
            document["candidateTierCounts"],
        )
        self.assertEqual(28, document["availableOwnerCandidateCount"])
        self.assertEqual(
            {"minimum": 20, "maximum": 28},
            document["windowTargetRange"],
        )
        self.assertFalse(document["authorization"]["canAcceptT7"])
        self.assertFalse(document["authorization"]["canWriteRuntimeCatalog"])
        self.assertTrue(all(not row["selectedForWindow"] for row in document["contracts"]))
        self.assertTrue(all(row["ownerContract"] is None for row in document["contracts"]))
        self.assertTrue(
            by_selector["csdp:301002"]["sourceHints"]["hasLogSignatureHint"]
        )
        self.assertEqual(
            [],
            by_selector["csdp:301002"]["sourceHints"]["signatureErrorCodes"],
        )

    def test_unfilled_template_is_not_window_ready(self) -> None:
        with self.assertRaisesRegex(OwnerInputError, "20 to 28 selected"):
            validate_owner_input(
                build_current_template(REPO),
                build_current_template(REPO),
                as_of=AS_OF,
            )

    def test_exactly_twenty_complete_contracts_validate_without_authority(self) -> None:
        template = build_current_template(REPO)
        result = validate_owner_input(self._completed(), template, as_of=AS_OF)

        self.assertEqual("PREPARED_NOT_EXECUTABLE", result["status"])
        self.assertEqual(20, result["selectedCount"])
        self.assertEqual(20, len(result["selectedSelectors"]))
        self.assertFalse(result["canAcceptT7"])
        self.assertFalse(result["canWriteRuntimeCatalog"])

    def test_nineteen_selected_contracts_are_rejected(self) -> None:
        with self.assertRaisesRegex(OwnerInputError, "20 to 28 selected"):
            validate_owner_input(
                self._completed(19),
                build_current_template(REPO),
                as_of=AS_OF,
            )

    def test_stale_fingerprint_unknown_selector_and_tampered_hints_are_rejected(self) -> None:
        mutations = []

        stale = self._completed()
        stale["preparationFingerprint"] = "0" * 64
        mutations.append(stale)

        unknown = self._completed()
        unknown["contracts"][0]["selectorKey"] = "csdp:UNKNOWN"
        mutations.append(unknown)

        tampered = self._completed()
        tampered["contracts"][0]["sourceHints"]["sourceServices"] = ["invented"]
        mutations.append(tampered)

        for document in mutations:
            with self.subTest(document=document), self.assertRaises(OwnerInputError):
                validate_owner_input(
                    document,
                    build_current_template(REPO),
                    as_of=AS_OF,
                )

    def test_dql_url_credentials_and_extra_fields_are_rejected(self) -> None:
        mutations = []

        dql = self._completed()
        dql["contracts"][0]["ownerContract"]["safeSearchTerm"] = "D::logs"
        mutations.append(dql)

        url = self._completed()
        url["contracts"][0]["ownerContract"]["serverQueryContractReference"] = (
            "https://observability/query"
        )
        mutations.append(url)

        credential = self._completed()
        credential["contracts"][0]["ownerContract"]["bindingRefs"]["log_search"] = (
            "DF-API-KEY"
        )
        mutations.append(credential)

        extra = self._completed()
        extra["contracts"][0]["ownerContract"]["rawLog"] = "must never enter"
        mutations.append(extra)

        for document in mutations:
            with self.subTest(document=document), self.assertRaises(OwnerInputError):
                validate_owner_input(
                    document,
                    build_current_template(REPO),
                    as_of=AS_OF,
                )

    def test_future_historical_time_is_rejected(self) -> None:
        document = self._completed()
        document["contracts"][0]["ownerContract"]["historicalOccurredAt"] = (
            "2026-08-03T00:00:00Z"
        )

        with self.assertRaisesRegex(OwnerInputError, "future"):
            validate_owner_input(
                document,
                build_current_template(REPO),
                as_of=AS_OF,
            )

    def test_semantically_duplicate_queries_cannot_be_renamed_into_a_batch(self) -> None:
        document = self._completed()
        first = document["contracts"][0]["ownerContract"]
        second = document["contracts"][1]["ownerContract"]
        for field in ("verifiedRuntimeService", "safeSearchTerm", "window", "bindingRefs"):
            second[field] = copy.deepcopy(first[field])

        with self.assertRaisesRegex(OwnerInputError, "query semantics must be unique"):
            validate_owner_input(
                document,
                build_current_template(REPO),
                as_of=AS_OF,
            )

    def test_recommended_worksheet_is_exactly_twenty_but_cannot_self_validate(self) -> None:
        template = build_current_template(REPO)
        worksheet = build_current_recommended_worksheet(REPO)
        selected = [
            row for row in worksheet["contracts"] if row["selectedForWindow"]
        ]

        self.assertEqual(20, len(selected))
        self.assertEqual(
            {"A_HINTED": 15, "B_CONTEXT_ONLY": 2, "C_SOURCE_GAPS": 3},
            {
                tier: sum(row["preparationTier"] == tier for row in selected)
                for tier in ("A_HINTED", "B_CONTEXT_ONLY", "C_SOURCE_GAPS")
            },
        )
        self.assertEqual(
            {"csdp:101017", "csdp:101062", "csdp:301045"},
            {
                row["selectorKey"]
                for row in selected
                if row["preparationTier"] == "C_SOURCE_GAPS"
            },
        )
        self.assertTrue(all(row["ownerContract"] is not None for row in selected))
        self.assertTrue(
            all(
                row["ownerContract"] is None
                for row in worksheet["contracts"]
                if not row["selectedForWindow"]
            )
        )
        with self.assertRaises(OwnerInputError):
            validate_owner_input(worksheet, template, as_of=AS_OF)

    def test_recommended_worksheet_reports_every_placeholder_in_one_pass(self) -> None:
        template = build_current_template(REPO)
        worksheet = build_current_recommended_worksheet(REPO)
        selected = [
            row for row in worksheet["contracts"] if row["selectedForWindow"]
        ]

        with self.assertRaises(OwnerInputError) as raised:
            validate_owner_input(worksheet, template, as_of=AS_OF)

        self.assertEqual(300, len(raised.exception.issues))
        self.assertIn(
            selected[0]["selectorKey"]
            + ".ownerTeam contains an unresolved placeholder",
            raised.exception.issues,
        )
        self.assertIn(
            selected[-1]["selectorKey"]
            + ".historicalSourceReference contains an unresolved placeholder",
            raised.exception.issues,
        )
        self.assertTrue(
            all("<replace:" not in issue for issue in raised.exception.issues)
        )

    def test_invalid_fields_across_contracts_are_reported_together(self) -> None:
        document = self._completed()
        first_selector = document["contracts"][0]["selectorKey"]
        second_selector = document["contracts"][1]["selectorKey"]
        secret_marker = "do-not-echo-this-owner-value"
        document["contracts"][0]["ownerContract"]["ownerTeam"] = (
            "password=" + secret_marker
        )
        document["contracts"][1]["ownerContract"]["window"] = "-25h"

        with self.assertRaises(OwnerInputError) as raised:
            validate_owner_input(
                document,
                build_current_template(REPO),
                as_of=AS_OF,
            )

        self.assertEqual(2, len(raised.exception.issues))
        self.assertIn(
            first_selector + ".ownerTeam is blank, unsafe, or too long",
            raised.exception.issues,
        )
        self.assertIn(
            second_selector + ".window exceeds 24 hours",
            raised.exception.issues,
        )
        self.assertNotIn(secret_marker, str(raised.exception))

    def test_free_text_placeholders_must_be_replaced(self) -> None:
        mutations = []

        owner_team = self._completed()
        owner_team["contracts"][0]["ownerContract"]["ownerTeam"] = (
            "<replace:owner-team>"
        )
        mutations.append(owner_team)

        owner_scenario = self._completed()
        owner_scenario["contracts"][0]["ownerContract"]["ownerScenario"] = (
            "still <replace:owner-verified-scenario>"
        )
        mutations.append(owner_scenario)

        for document in mutations:
            with self.subTest(document=document), self.assertRaisesRegex(
                OwnerInputError,
                "placeholder",
            ):
                validate_owner_input(
                    document,
                    build_current_template(REPO),
                    as_of=AS_OF,
                )

    def test_committed_template_is_generated_from_current_preparation(self) -> None:
        self.assertEqual(
            render_json(build_current_template(REPO)),
            (REPO / "docs/intelligent-troubleshooting/t7-owner-contract-intake.template.json")
            .read_text(encoding="utf-8"),
        )
        self.assertEqual(
            render_json(build_current_recommended_worksheet(REPO)),
            (
                REPO
                / "docs/intelligent-troubleshooting/"
                "t7-owner-contract-intake.recommended.template.json"
            ).read_text(encoding="utf-8"),
        )


if __name__ == "__main__":
    unittest.main()
