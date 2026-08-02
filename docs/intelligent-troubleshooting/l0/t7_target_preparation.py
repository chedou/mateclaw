#!/usr/bin/env python3
"""Build the review-only queue that precedes the T7 recording window.

This tool deliberately cannot create an executable Guance target or authorize
T7.  It turns the reviewed L0 inventory into a bounded owner work queue, then
points back to the running Java catalog and T7 preflight as the only execution
authority.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any, Dict, Iterable, List, Mapping, Optional, Sequence, Set, Tuple

from clean_sop_kb import CleanResult, clean_entries


CONTRACT_VERSION = "t7-guance-contract-preparation.v1"
INVENTORY_VERSION = "csdp-d1-error-code-selector-inventory.v1"
TARGET_CATALOG_VERSION = "t7-guance-recording-target-catalog.v1"
RECORDED_SUITE_VERSION = 2
MIN_WINDOW_TARGETS = 20
MAX_WINDOW_TARGETS = 30
SAFE_SELECTOR = re.compile(r"^csdp:[A-Za-z0-9_.-]{1,128}$")
SAFE_RECORDED_SELECTOR = re.compile(
    r"^[a-z0-9_.-]+(?::[A-Za-z0-9_.-]{1,128}){1,3}$"
)
SAFE_SIGNATURE_CODE = re.compile(r"^[A-Za-z0-9_.:-]{1,128}$")
SIGNATURE_CODE = re.compile(
    r'''["']error_code["']\s*:\s*["']([A-Za-z0-9_.:-]{1,128})["']'''
)

COMMON_OWNER_REQUIREMENTS = (
    "owner_team",
    "verified_runtime_service",
    "server_query_contract",
    "safe_search_term",
    "deterministic_anomaly_criteria",
    "deterministic_diagnosis_rule",
    "current_binding_refs",
    "historical_occurred_at",
)


class ContractError(ValueError):
    """A preparation input is ambiguous or structurally invalid."""


def _strict_object(pairs: Sequence[Tuple[str, Any]]) -> Dict[str, Any]:
    output: Dict[str, Any] = {}
    for key, value in pairs:
        if key in output:
            raise ContractError("duplicate JSON key: " + key)
        output[key] = value
    return output


def load_json_strict(path: Path) -> Any:
    try:
        return json.loads(
            path.read_text(encoding="utf-8"),
            object_pairs_hook=_strict_object,
        )
    except (OSError, UnicodeError, json.JSONDecodeError, ContractError) as failure:
        raise ContractError("invalid strict JSON at {0}: {1}".format(path, failure)) from failure


def _sha256(path: Path) -> str:
    try:
        return hashlib.sha256(path.read_bytes()).hexdigest()
    except OSError as failure:
        raise ContractError("cannot read preparation input: " + str(path)) from failure


def selector_inventory(document: Any, source_sha256: str) -> Set[str]:
    expected_keys = {"contractVersion", "source", "sourceSha256", "selectors"}
    if not isinstance(document, dict) or set(document) != expected_keys:
        raise ContractError("selector inventory fields are invalid")
    if document.get("contractVersion") != INVENTORY_VERSION:
        raise ContractError("selector inventory version is invalid")
    if document.get("source") != "docs/intelligent-troubleshooting/l0/sop_kb.json":
        raise ContractError("selector inventory source is invalid")
    if document.get("sourceSha256") != source_sha256:
        raise ContractError("selector inventory source SHA-256 is stale")
    raw = document.get("selectors")
    if not isinstance(raw, list) or len(raw) != 146:
        raise ContractError("selector inventory must contain exactly 146 members")
    if any(not isinstance(value, str) or not SAFE_SELECTOR.fullmatch(value) for value in raw):
        raise ContractError("selector inventory membership is invalid")
    unique = set(raw)
    if len(unique) != len(raw):
        raise ContractError("selector inventory contains duplicate members")
    return unique


def recorded_selectors(document: Any) -> Set[str]:
    expected_keys = {"version", "suites", "recordedEvidenceSeeds"}
    if not isinstance(document, dict) or set(document) != expected_keys:
        raise ContractError(
            "recorded suite root must contain version, suites, recordedEvidenceSeeds"
        )
    if document.get("version") != RECORDED_SUITE_VERSION:
        raise ContractError("recorded suite root version is invalid")
    if not isinstance(document.get("suites"), list):
        raise ContractError("recorded suite root suites must be an array")
    raw = document.get("recordedEvidenceSeeds")
    if not isinstance(raw, list):
        raise ContractError("recordedEvidenceSeeds must be an array")
    output: Set[str] = set()
    for index, seed in enumerate(raw):
        value = seed.get("selectorKey") if isinstance(seed, dict) else None
        if not isinstance(value, str) or not SAFE_RECORDED_SELECTOR.fullmatch(value):
            raise ContractError(
                "recordedEvidenceSeeds[{0}].selectorKey is invalid".format(index)
            )
        if value in output:
            raise ContractError("recordedEvidenceSeeds contains duplicate selectors")
        output.add(value)
    return output


def frozen_catalog_selectors(document: Any) -> Set[str]:
    if (
        not isinstance(document, dict)
        or set(document) != {"contractVersion", "targets"}
        or document.get("contractVersion") != TARGET_CATALOG_VERSION
        or not isinstance(document.get("targets"), list)
    ):
        raise ContractError("recording target catalog root is invalid")
    output: Set[str] = set()
    for index, target in enumerate(document["targets"]):
        candidate = target.get("candidate") if isinstance(target, dict) else None
        system = candidate.get("system") if isinstance(candidate, dict) else None
        error_code = candidate.get("errorCode") if isinstance(candidate, dict) else None
        if not isinstance(system, str) or not isinstance(error_code, str):
            raise ContractError(
                "recording target catalog targets[{0}].candidate identity is invalid".format(
                    index
                )
            )
        selector = system.strip().lower() + ":" + error_code.strip()
        if not SAFE_SELECTOR.fullmatch(selector):
            raise ContractError(
                "recording target catalog targets[{0}] selector is invalid".format(index)
            )
        if selector in output:
            raise ContractError("recording target catalog contains duplicate selectors")
        output.add(selector)
    return output


def _text_values(entries: Iterable[Mapping[str, Any]], field: str) -> List[str]:
    return sorted(
        {
            str(entry.get(field) or "").strip()
            for entry in entries
            if str(entry.get(field) or "").strip()
        }
    )


def _signature_codes(entries: Iterable[Mapping[str, Any]]) -> Tuple[bool, List[str]]:
    has_hint = False
    codes: Set[str] = set()
    for entry in entries:
        raw = str(entry.get("log_signature") or "").strip()
        if not raw:
            continue
        has_hint = True
        try:
            parsed = json.loads(raw, object_pairs_hook=_strict_object)
        except (json.JSONDecodeError, ContractError):
            parsed = None
        if isinstance(parsed, dict):
            value = parsed.get("error_code")
            if isinstance(value, str) and SAFE_SIGNATURE_CODE.fullmatch(value):
                codes.add(value)
        for value in SIGNATURE_CODE.findall(raw):
            if SAFE_SIGNATURE_CODE.fullmatch(value):
                codes.add(value)
    return has_hint, sorted(codes)


def _blocking_issues(result: CleanResult) -> Dict[str, List[str]]:
    output: Dict[str, List[str]] = defaultdict(list)
    for issue in result.issues:
        if not issue.blocking or not issue.error_code:
            continue
        selector = "csdp:" + issue.error_code
        if issue.code not in output[selector]:
            output[selector].append(issue.code)
    return output


def _status(
    selector: str,
    recorded: Set[str],
    frozen: Set[str],
    blockers: Sequence[str],
) -> str:
    if selector in recorded:
        return "ALREADY_RECORDED"
    if blockers:
        return "BLOCKED_SOURCE_QUALITY"
    if selector in frozen:
        return "FROZEN_AWAITING_RUNTIME_VALIDATION"
    return "NEEDS_OWNER_CONTRACT"


def _missing_requirements(
    status: str,
    levels: Sequence[str],
    scenarios: Sequence[str],
    has_log_hint: bool,
) -> List[str]:
    if status == "ALREADY_RECORDED":
        return []
    if status == "FROZEN_AWAITING_RUNTIME_VALIDATION":
        return ["runtime_preflight", "owner_acceptance"]
    output = list(COMMON_OWNER_REQUIREMENTS)
    if status == "BLOCKED_SOURCE_QUALITY":
        output.insert(0, "source_quality_resolution")
    if not levels:
        output.append("owner_level")
    if not scenarios:
        output.append("owner_scenario")
    if not has_log_hint:
        output.append("log_signature_or_query_key")
    return output


def build_preparation_report(
    result: CleanResult,
    inventory: Set[str],
    recorded: Set[str],
    frozen: Set[str],
    input_hashes: Mapping[str, str],
) -> Dict[str, Any]:
    grouped: Dict[str, List[Mapping[str, Any]]] = defaultdict(list)
    for entry in result.entries:
        completeness = entry.get("completeness")
        if not isinstance(completeness, dict) or not completeness.get(
            "automatable_candidate"
        ):
            continue
        system = str(entry.get("system") or "").strip().lower()
        error_code = str(entry.get("error_code") or "").strip()
        selector = system + ":" + error_code
        if not SAFE_SELECTOR.fullmatch(selector):
            raise ContractError("automatable candidate selector is invalid: " + selector)
        if selector not in inventory:
            raise ContractError("automatable candidate is outside frozen inventory: " + selector)
        grouped[selector].append(entry)

    blockers_by_selector = _blocking_issues(result)
    queue: List[Dict[str, Any]] = []
    for selector, entries in grouped.items():
        levels = _text_values(entries, "level")
        scenarios = _text_values(entries, "scenario")
        has_log_hint, signature_codes = _signature_codes(entries)
        quality_blockers = sorted(blockers_by_selector.get(selector, []))
        status = _status(selector, recorded, frozen, quality_blockers)
        queue.append(
            {
                "selectorKey": selector,
                "status": status,
                "levels": levels,
                "sourceServices": _text_values(entries, "service"),
                "modules": _text_values(entries, "module"),
                "scenarios": scenarios,
                "hasLogSignatureHint": has_log_hint,
                "signatureErrorCodes": signature_codes,
                "sourceQualityBlockers": quality_blockers,
                "missingRequirements": _missing_requirements(
                    status, levels, scenarios, has_log_hint
                ),
            }
        )

    status_order = {
        "BLOCKED_SOURCE_QUALITY": 0,
        "FROZEN_AWAITING_RUNTIME_VALIDATION": 1,
        "NEEDS_OWNER_CONTRACT": 2,
        "ALREADY_RECORDED": 3,
    }
    level_order = {"P0": 0, "P1": 1, "P2": 2}
    queue.sort(
        key=lambda row: (
            status_order[row["status"]],
            min((level_order.get(value, 9) for value in row["levels"]), default=9),
            not row["hasLogSignatureHint"],
            row["selectorKey"],
        )
    )
    counts_by_status = Counter(row["status"] for row in queue)
    counts = {
        "reviewedSelectorCount": len(inventory),
        "automatableCandidateCount": len(queue),
        "alreadyRecordedCount": counts_by_status["ALREADY_RECORDED"],
        "frozenAwaitingRuntimeValidationCount": counts_by_status[
            "FROZEN_AWAITING_RUNTIME_VALIDATION"
        ],
        "blockedSourceQualityCount": counts_by_status["BLOCKED_SOURCE_QUALITY"],
        "needsOwnerContractCount": counts_by_status["NEEDS_OWNER_CONTRACT"],
    }
    if sum(counts_by_status.values()) != len(queue):
        raise ContractError("preparation status accounting is incomplete")

    blockers = []
    if counts["needsOwnerContractCount"]:
        blockers.append(
            "{0} candidates still need owner-verified Guance query contracts".format(
                counts["needsOwnerContractCount"]
            )
        )
    if counts["blockedSourceQualityCount"]:
        blockers.append(
            "{0} candidates are blocked by source quality".format(
                counts["blockedSourceQualityCount"]
            )
        )
    if counts["frozenAwaitingRuntimeValidationCount"] < MIN_WINDOW_TARGETS:
        blockers.append(
            "the preparation queue contains {0} catalog-matched targets awaiting runtime validation; the running service must expose at least {1} executable targets".format(
                counts["frozenAwaitingRuntimeValidationCount"], MIN_WINDOW_TARGETS
            )
        )
    blockers.append(
        "this report never authorizes T7; run the service-backed T7 preflight"
    )

    return {
        "contractVersion": CONTRACT_VERSION,
        "authorization": {
            "kind": "PREPARATION_ONLY",
            "canAcceptT7": False,
            "runtimeAuthority": (
                "GET /api/v1/troubleshooting/evidence/guance/recording-targets "
                "+ scripts/troubleshooting-t7-preflight.sh"
            ),
        },
        "windowTargetRange": {"minimum": MIN_WINDOW_TARGETS, "maximum": MAX_WINDOW_TARGETS},
        "inputs": dict(input_hashes),
        "counts": counts,
        "blockers": blockers,
        "queue": queue,
    }


def build_current_report(repo: Path) -> Dict[str, Any]:
    repo = repo.resolve()
    sop_path = repo / "docs/intelligent-troubleshooting/l0/sop_kb.json"
    inventory_path = (
        repo
        / "mateclaw-server/src/main/resources/troubleshooting/knowledge/"
        "csdp-d1-error-code-selectors.json"
    )
    recorded_path = (
        repo
        / "mateclaw-server/src/main/resources/troubleshooting/replay/"
        "manual-playbook-replay-suites.json"
    )
    target_path = (
        repo
        / "mateclaw-server/src/main/resources/troubleshooting/evidence/"
        "guance-recording-targets.json"
    )
    source = load_json_strict(sop_path)
    if not isinstance(source, list):
        raise ContractError("SOP KB root must be an array")
    source_sha256 = _sha256(sop_path)
    inventory = selector_inventory(load_json_strict(inventory_path), source_sha256)
    recorded = recorded_selectors(load_json_strict(recorded_path))
    frozen = frozen_catalog_selectors(load_json_strict(target_path))
    return build_preparation_report(
        clean_entries(source),
        inventory,
        recorded,
        frozen,
        {
            "sopKbSha256": source_sha256,
            "selectorInventorySha256": _sha256(inventory_path),
            "recordedSuitesSha256": _sha256(recorded_path),
            "recordingTargetCatalogSha256": _sha256(target_path),
        },
    )


def render_json(report: Mapping[str, Any]) -> str:
    return json.dumps(report, ensure_ascii=False, indent=2) + "\n"


def _cell(values: Sequence[str]) -> str:
    if not values:
        return "—"
    return " / ".join(value.replace("|", "\\|").replace("\n", " ") for value in values)


def render_markdown(report: Mapping[str, Any]) -> str:
    counts = report["counts"]
    lines = [
        "# T7 Guance 目标合同准备队列",
        "",
        "> 本文件由 `l0/t7_target_preparation.py` 从当前 L0、冻结 D1 清单、录制套件和目标目录确定性生成。",
        "> 它只用于窗口外分工，**不能授权 T7、不能替代运行服务目录或预检**。",
        "",
        "## 当前结论",
        "",
        "- 冻结 D1 分母：**{0}**。".format(counts["reviewedSelectorCount"]),
        "- 清洗后的只读候选：**{0}**；其中已录制 **{1}**、源码质量阻断 **{2}**、待 owner 补合同 **{3}**。".format(
            counts["automatableCandidateCount"],
            counts["alreadyRecordedCount"],
            counts["blockedSourceQualityCount"],
            counts["needsOwnerContractCount"],
        ),
        "- 准备队列中已写入目标目录、无源质量阻断且仍待运行时验证：**{0}**；T7 窗口要求运行服务投影至少 **20** 个可执行目标。".format(
            counts["frozenAwaitingRuntimeValidationCount"]
        ),
        "- 当前结论始终是 `PREPARATION_ONLY`；只有 `recording-targets` 运行时接口 + T7 预检可以发布可执行结论。",
        "",
        "## 缺失字段说明",
        "",
        "- `owner_team / owner_level / owner_scenario`：由业务 owner 核对责任团队、等级和故障场景。",
        "- `verified_runtime_service / safe_search_term / log_signature_or_query_key`：核对真实运行服务和安全检索键；不得在本队列填写 DQL 或原始日志。",
        "- `server_query_contract / current_binding_refs`：由服务端配置维护查询模板及当前 `log_search / log_trace_bundle / contrast_sample` 三份 bindingRef。",
        "- `deterministic_anomaly_criteria / deterministic_diagnosis_rule`：给出可复算异常判据与诊断规则，不能使用模型自报置信度替代。",
        "- `historical_occurred_at`：仍在保留期内的精确历史故障时间；批次模式不得回落当前时间。",
        "- `source_quality_resolution`：先解决源材料冲突；`runtime_preflight / owner_acceptance` 只能在完整合同冻结后执行。",
        "",
        "## Owner 补齐队列",
        "",
        "| Selector | 等级 | 来源服务 | 日志签名提示 | 状态 | 仍缺 |",
        "|---|---|---|---|---|---|",
    ]
    for row in report["queue"]:
        lines.append(
            "| `{selector}` | {levels} | {services} | {hint} | `{status}` | {missing} |".format(
                selector=row["selectorKey"],
                levels=_cell(row["levels"]),
                services=_cell(row["sourceServices"]),
                hint=(
                    "有" + (" · " + _cell(row["signatureErrorCodes"]) if row["signatureErrorCodes"] else "")
                    if row["hasLogSignatureHint"]
                    else "无"
                ),
                status=row["status"],
                missing=_cell(row["missingRequirements"]),
            )
        )
    lines.extend(
        [
            "",
            "## 使用顺序",
            "",
            "1. 将 `BLOCKED_SOURCE_QUALITY` 保持隔离并行回源，不能在冲突路由键上猜一个上下文；它不计入首批，也不阻塞从其余 28 条中完成建议 20 条。",
            "2. Owner 优先复制 `t7-owner-contract-intake.recommended.template.json`：它已按审核过的 15 A + 2 B + 3 C 选好 20 条并展开全部字段；如需调整批次再使用空白模板。",
            "3. 逐项替换所有 `<replace:...>` 占位符，核对真实运行 service、服务端查询合同、安全 search term、判据/规则、bindingRef 和历史故障时间。",
            "4. 执行 `t7_owner_contract_intake.py --validate <受控文件>`；通过仍只表示 `PREPARED_NOT_EXECUTABLE`，不是 T7 授权。",
            "5. 开发者根据已核实引用编写完整、安全、未验证的 candidate，与当前三份 bindingRef 冻结进 `guance-recording-targets.json`；本报告和 owner 输入工具都不生成该文件。",
            "6. 重启运行服务并执行 `T7_SEED_PLAN_FILE=<受控计划> ./scripts/troubleshooting-t7-preflight.sh`。只有运行时返回 20–30 个可执行目标才可约窗口。",
            "7. 窗口里由 owner 完成清单并提交 `ACCEPTED`，随后一次灌入 20–30 份 D19 聚合正例。",
            "",
            "本队列不含原始日志、DQL、凭据或 API Key；日志签名只投影是否存在及安全错误标识符。",
            "",
        ]
    )
    return "\n".join(lines)


def _write_atomic(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(content, encoding="utf-8")
    temporary.replace(path)


def _parse_args(argv: Optional[Sequence[str]] = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[3])
    parser.add_argument("--write", action="store_true", help="Regenerate committed JSON and Markdown")
    parser.add_argument("--check", action="store_true", help="Fail when committed outputs are stale")
    return parser.parse_args(argv)


def main(argv: Optional[Sequence[str]] = None) -> int:
    args = _parse_args(argv)
    if args.write and args.check:
        raise SystemExit("choose either --write or --check")
    report = build_current_report(args.repo)
    json_output = args.repo / "docs/intelligent-troubleshooting/t7-target-contract-preparation.json"
    markdown_output = args.repo / "docs/intelligent-troubleshooting/t7-target-contract-preparation.md"
    expected_json = render_json(report)
    expected_markdown = render_markdown(report)
    if args.write:
        _write_atomic(json_output, expected_json)
        _write_atomic(markdown_output, expected_markdown)
    if args.check:
        if not json_output.exists() or json_output.read_text(encoding="utf-8") != expected_json:
            print("stale T7 preparation JSON")
            return 1
        if not markdown_output.exists() or markdown_output.read_text(encoding="utf-8") != expected_markdown:
            print("stale T7 preparation Markdown")
            return 1
    print(json.dumps(report["counts"], ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
