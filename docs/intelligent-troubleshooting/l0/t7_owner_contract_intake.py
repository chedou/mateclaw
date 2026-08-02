#!/usr/bin/env python3
"""Build and validate the owner-only input packet before a T7 window.

The packet is deliberately not a runtime target catalog.  It lets an owner
select 20-28 currently eligible candidates and attach only safe references and
reviewed facts.  A successful validation means PREPARED_NOT_EXECUTABLE; Java's
server-owned catalog, runtime preflight, and owner acceptance remain the only
execution authorities.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, Mapping, Optional, Sequence

from t7_target_preparation import (
    ContractError,
    build_current_report,
    load_json_strict,
    render_json as render_preparation_json,
)


CONTRACT_VERSION = "t7-owner-contract-intake.v1"
VALIDATION_VERSION = "t7-owner-contract-validation.v1"
MIN_SELECTED = 20
MAX_SELECTED = 30
CORE_SIGNALS = {"log_search", "log_trace_bundle", "contrast_sample"}
OWNER_LEVELS = {"P0", "P1", "P2"}
SAFE_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$")
SAFE_REFERENCE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:/#-]{0,255}$")
WINDOW = re.compile(r"^-([1-9][0-9]{0,5})(s|m|h|d)$")
UTC_SECONDS = re.compile(r"^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$")
JWT = re.compile(r"eyJ[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}")
FORBIDDEN_TEXT = (
    re.compile(r"D::", re.IGNORECASE),
    re.compile(r"https?://", re.IGNORECASE),
    re.compile(r"DF-API-KEY", re.IGNORECASE),
    re.compile(r"\bBearer\s+[A-Za-z0-9]", re.IGNORECASE),
    re.compile(
        r"\b(?:api[_-]?key|password|authorization|access[_-]?token|secret)\s*[:=]",
        re.IGNORECASE,
    ),
)

ROOT_FIELDS = {
    "contractVersion",
    "authorization",
    "preparationContractVersion",
    "preparationFingerprint",
    "windowTargetRange",
    "availableOwnerCandidateCount",
    "candidateTierCounts",
    "contracts",
}
ROW_FIELDS = {
    "selectorKey",
    "preparationTier",
    "sourceHints",
    "selectedForWindow",
    "ownerContract",
}
OWNER_CONTRACT_FIELDS = {
    "ownerTeam",
    "ownerLevel",
    "ownerScenario",
    "verifiedRuntimeService",
    "candidateReference",
    "serverQueryContractReference",
    "safeSearchTerm",
    "window",
    "anomalyCriterionReference",
    "diagnosisRuleReference",
    "bindingRefs",
    "historicalOccurredAt",
    "historicalSourceReference",
}


class OwnerInputError(ValueError):
    """Owner input is stale, unsafe, incomplete, or structurally ambiguous."""


def _preparation_fingerprint(report: Mapping[str, Any]) -> str:
    return hashlib.sha256(render_preparation_json(report).encode("utf-8")).hexdigest()


def _tier(row: Mapping[str, Any]) -> str:
    has_level = bool(row.get("levels"))
    has_scenario = bool(row.get("scenarios"))
    has_log_hint = bool(row.get("hasLogSignatureHint"))
    if has_level and has_scenario and has_log_hint:
        return "A_HINTED"
    if has_level and has_scenario:
        return "B_CONTEXT_ONLY"
    return "C_SOURCE_GAPS"


def build_template(report: Mapping[str, Any]) -> Dict[str, Any]:
    if report.get("authorization", {}).get("kind") != "PREPARATION_ONLY":
        raise OwnerInputError("T7 preparation report authority is invalid")
    candidates = [
        row for row in report.get("queue", [])
        if row.get("status") == "NEEDS_OWNER_CONTRACT"
    ]
    contracts = []
    for row in candidates:
        contracts.append(
            {
                "selectorKey": row["selectorKey"],
                "preparationTier": _tier(row),
                "sourceHints": {
                    "levels": list(row["levels"]),
                    "sourceServices": list(row["sourceServices"]),
                    "modules": list(row["modules"]),
                    "scenarios": list(row["scenarios"]),
                    "signatureErrorCodes": list(row["signatureErrorCodes"]),
                },
                "selectedForWindow": False,
                "ownerContract": None,
            }
        )
    tier_counts = Counter(row["preparationTier"] for row in contracts)
    return {
        "contractVersion": CONTRACT_VERSION,
        "authorization": {
            "kind": "OWNER_INPUT_TEMPLATE_ONLY",
            "canAcceptT7": False,
            "canWriteRuntimeCatalog": False,
            "nextAuthority": (
                "server-owned recording target catalog + T7 preflight + owner acceptance"
            ),
        },
        "preparationContractVersion": report["contractVersion"],
        "preparationFingerprint": _preparation_fingerprint(report),
        "windowTargetRange": {
            "minimum": MIN_SELECTED,
            "maximum": min(MAX_SELECTED, len(contracts)),
        },
        "availableOwnerCandidateCount": len(contracts),
        "candidateTierCounts": {
            "A_HINTED": tier_counts["A_HINTED"],
            "B_CONTEXT_ONLY": tier_counts["B_CONTEXT_ONLY"],
            "C_SOURCE_GAPS": tier_counts["C_SOURCE_GAPS"],
        },
        "contracts": contracts,
    }


def build_current_template(repo: Path) -> Dict[str, Any]:
    return build_template(build_current_report(repo.resolve()))


def _forbidden(value: str) -> bool:
    return JWT.search(value) is not None or any(
        pattern.search(value) is not None for pattern in FORBIDDEN_TEXT
    )


def _string(value: Any, field: str, maximum: int) -> str:
    if not isinstance(value, str):
        raise OwnerInputError(field + " must be a string")
    normalized = value.strip()
    if (
        not normalized
        or len(normalized) > maximum
        or any(ord(character) < 32 for character in normalized)
        or _forbidden(normalized)
    ):
        raise OwnerInputError(field + " is blank, unsafe, or too long")
    return normalized


def _identifier(value: Any, field: str) -> str:
    normalized = _string(value, field, 128)
    if not SAFE_ID.fullmatch(normalized):
        raise OwnerInputError(field + " must be a safe identifier")
    return normalized


def _reference(value: Any, field: str) -> str:
    normalized = _string(value, field, 256)
    if not SAFE_REFERENCE.fullmatch(normalized):
        raise OwnerInputError(field + " must be a safe reference")
    return normalized


def _window(value: Any, field: str) -> str:
    normalized = _string(value, field, 16)
    match = WINDOW.fullmatch(normalized)
    if match is None:
        raise OwnerInputError(field + " must be a bounded relative window")
    amount = int(match.group(1))
    factor = {"s": 1, "m": 60, "h": 3600, "d": 86400}[match.group(2)]
    if amount * factor > 86400:
        raise OwnerInputError(field + " exceeds 24 hours")
    return normalized


def _occurred_at(value: Any, field: str, as_of: datetime) -> str:
    normalized = _string(value, field, 20)
    if not UTC_SECONDS.fullmatch(normalized):
        raise OwnerInputError(field + " must be UTC RFC3339 whole seconds")
    try:
        observed = datetime.strptime(normalized, "%Y-%m-%dT%H:%M:%SZ").replace(
            tzinfo=timezone.utc
        )
    except ValueError as failure:
        raise OwnerInputError(field + " is not a real timestamp") from failure
    if observed > as_of:
        raise OwnerInputError(field + " is in the future")
    return normalized


def _owner_contract(
    value: Any,
    selector: str,
    as_of: datetime,
) -> Dict[str, str]:
    if not isinstance(value, dict) or set(value) != OWNER_CONTRACT_FIELDS:
        raise OwnerInputError(selector + " ownerContract fields are invalid")
    level = _string(value["ownerLevel"], selector + ".ownerLevel", 2)
    if level not in OWNER_LEVELS:
        raise OwnerInputError(selector + ".ownerLevel must be P0, P1, or P2")
    binding_refs = value["bindingRefs"]
    if not isinstance(binding_refs, dict) or set(binding_refs) != CORE_SIGNALS:
        raise OwnerInputError(selector + ".bindingRefs must contain the three core signals")
    return {
        "ownerTeam": _string(value["ownerTeam"], selector + ".ownerTeam", 128),
        "ownerLevel": level,
        "ownerScenario": _string(
            value["ownerScenario"], selector + ".ownerScenario", 160
        ),
        "verifiedRuntimeService": _identifier(
            value["verifiedRuntimeService"], selector + ".verifiedRuntimeService"
        ),
        "candidateReference": _reference(
            value["candidateReference"], selector + ".candidateReference"
        ),
        "serverQueryContractReference": _reference(
            value["serverQueryContractReference"],
            selector + ".serverQueryContractReference",
        ),
        "safeSearchTerm": _identifier(
            value["safeSearchTerm"], selector + ".safeSearchTerm"
        ),
        "window": _window(value["window"], selector + ".window"),
        "anomalyCriterionReference": _reference(
            value["anomalyCriterionReference"],
            selector + ".anomalyCriterionReference",
        ),
        "diagnosisRuleReference": _reference(
            value["diagnosisRuleReference"], selector + ".diagnosisRuleReference"
        ),
        "log_search": _identifier(
            binding_refs["log_search"], selector + ".bindingRefs.log_search"
        ),
        "log_trace_bundle": _identifier(
            binding_refs["log_trace_bundle"],
            selector + ".bindingRefs.log_trace_bundle",
        ),
        "contrast_sample": _identifier(
            binding_refs["contrast_sample"],
            selector + ".bindingRefs.contrast_sample",
        ),
        "historicalOccurredAt": _occurred_at(
            value["historicalOccurredAt"], selector + ".historicalOccurredAt", as_of
        ),
        "historicalSourceReference": _reference(
            value["historicalSourceReference"],
            selector + ".historicalSourceReference",
        ),
    }


def validate_owner_input(
    document: Any,
    template: Mapping[str, Any],
    *,
    as_of: Optional[datetime] = None,
) -> Dict[str, Any]:
    if not isinstance(document, dict) or set(document) != ROOT_FIELDS:
        raise OwnerInputError("owner input root fields are invalid")
    for field in ROOT_FIELDS - {"contracts"}:
        if document.get(field) != template.get(field):
            raise OwnerInputError("owner input " + field + " is stale or tampered")
    raw_contracts = document.get("contracts")
    expected_contracts = template.get("contracts")
    if not isinstance(raw_contracts, list) or not isinstance(expected_contracts, list):
        raise OwnerInputError("owner input contracts must be an array")
    if len(raw_contracts) != len(expected_contracts):
        raise OwnerInputError("owner input must retain every current candidate row")

    expected_by_selector = {row["selectorKey"]: row for row in expected_contracts}
    actual_by_selector: Dict[str, Mapping[str, Any]] = {}
    for row in raw_contracts:
        if not isinstance(row, dict) or set(row) != ROW_FIELDS:
            raise OwnerInputError("owner input row fields are invalid")
        selector = row.get("selectorKey")
        if not isinstance(selector, str) or selector not in expected_by_selector:
            raise OwnerInputError("owner input contains an unknown selector")
        if selector in actual_by_selector:
            raise OwnerInputError("owner input contains a duplicate selector")
        expected = expected_by_selector[selector]
        if (
            row.get("preparationTier") != expected["preparationTier"]
            or row.get("sourceHints") != expected["sourceHints"]
        ):
            raise OwnerInputError(selector + " preparation hints are stale or tampered")
        if not isinstance(row.get("selectedForWindow"), bool):
            raise OwnerInputError(selector + " selectedForWindow must be boolean")
        actual_by_selector[selector] = row
    if set(actual_by_selector) != set(expected_by_selector):
        raise OwnerInputError("owner input candidate membership is stale")

    effective_as_of = as_of or datetime.now(timezone.utc)
    if effective_as_of.tzinfo is None:
        raise OwnerInputError("validation as_of must be timezone-aware")
    effective_as_of = effective_as_of.astimezone(timezone.utc)
    selected = []
    normalized_contracts = []
    for expected in expected_contracts:
        selector = expected["selectorKey"]
        row = actual_by_selector[selector]
        if not row["selectedForWindow"]:
            if row.get("ownerContract") is not None:
                raise OwnerInputError(selector + " unselected row must not carry ownerContract")
            continue
        normalized = _owner_contract(row.get("ownerContract"), selector, effective_as_of)
        selected.append(selector)
        normalized_contracts.append(normalized)

    maximum = min(MAX_SELECTED, len(expected_contracts))
    if not MIN_SELECTED <= len(selected) <= maximum:
        raise OwnerInputError(
            "owner input requires {0} to {1} selected contracts".format(
                MIN_SELECTED, maximum
            )
        )

    unique_fields = (
        "candidateReference",
        "serverQueryContractReference",
        "anomalyCriterionReference",
        "diagnosisRuleReference",
        "historicalSourceReference",
    )
    for field in unique_fields:
        values = [contract[field] for contract in normalized_contracts]
        if len(values) != len(set(values)):
            raise OwnerInputError(field + " must be unique across selected contracts")

    query_identities = [
        (
            contract["verifiedRuntimeService"],
            contract["safeSearchTerm"],
            contract["window"],
            contract["log_search"],
            contract["log_trace_bundle"],
            contract["contrast_sample"],
        )
        for contract in normalized_contracts
    ]
    if len(query_identities) != len(set(query_identities)):
        raise OwnerInputError(
            "query semantics must be unique across selected contracts"
        )

    selected_tiers = Counter(
        actual_by_selector[selector]["preparationTier"] for selector in selected
    )
    return {
        "contractVersion": VALIDATION_VERSION,
        "status": "PREPARED_NOT_EXECUTABLE",
        "selectedCount": len(selected),
        "selectedTierCounts": {
            "A_HINTED": selected_tiers["A_HINTED"],
            "B_CONTEXT_ONLY": selected_tiers["B_CONTEXT_ONLY"],
            "C_SOURCE_GAPS": selected_tiers["C_SOURCE_GAPS"],
        },
        "selectedSelectors": selected,
        "preparationFingerprint": template["preparationFingerprint"],
        "canAcceptT7": False,
        "canWriteRuntimeCatalog": False,
        "nextAuthority": template["authorization"]["nextAuthority"],
    }


def render_json(document: Mapping[str, Any]) -> str:
    return json.dumps(document, ensure_ascii=False, indent=2) + "\n"


def _write_atomic(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(content, encoding="utf-8")
    temporary.replace(path)


def _parse_args(argv: Optional[Sequence[str]] = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[3])
    actions = parser.add_mutually_exclusive_group()
    actions.add_argument("--write", action="store_true", help="Regenerate the owner template")
    actions.add_argument("--check", action="store_true", help="Fail when the owner template is stale")
    actions.add_argument("--validate", type=Path, help="Validate a copied and completed owner input")
    return parser.parse_args(argv)


def main(argv: Optional[Sequence[str]] = None) -> int:
    args = _parse_args(argv)
    output_path = (
        args.repo / "docs/intelligent-troubleshooting/t7-owner-contract-intake.template.json"
    )
    try:
        template = build_current_template(args.repo)
        expected = render_json(template)
        if args.write:
            _write_atomic(output_path, expected)
        elif args.check:
            if not output_path.exists() or output_path.read_text(encoding="utf-8") != expected:
                print("stale T7 owner contract intake template", file=sys.stderr)
                return 1
        elif args.validate is not None:
            result = validate_owner_input(
                load_json_strict(args.validate),
                template,
            )
            print(render_json(result), end="")
            return 0
        print(
            json.dumps(
                {
                    "availableOwnerCandidateCount": template[
                        "availableOwnerCandidateCount"
                    ],
                    "candidateTierCounts": template["candidateTierCounts"],
                    "canAcceptT7": False,
                },
                ensure_ascii=False,
                sort_keys=True,
            )
        )
        return 0
    except (ContractError, OwnerInputError) as failure:
        print("invalid T7 owner input: " + str(failure), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
