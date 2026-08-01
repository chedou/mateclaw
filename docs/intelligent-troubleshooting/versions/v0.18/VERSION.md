# v0.18 · 部署拓扑拨测回归统一 Evidence Spine

- 发布日期：2026-07-30
- 架构版本：RFC v4.4
- 图形语义：首次把部署拓扑拨测明确画成整体智能排障内的场景与只读证据工具。

## 本版锁定的边界

- `WorkspaceTopologyAsset` 是可复用、不可变且按 Workspace 隔离的输入资产，不是第二套诊断聚合。
- `deployment_topology_probe` 是 `SCENARIO_PLAYBOOK`，在既有 Diagnosis 内触发。
- `topology_synthetic_probe` 是可插拔的语义 Tool；Guance CloudDial 是其首个来源 Adapter，后续来源不得绕开 Tool Registry、预算、脱敏与 Verify Gate。
- 安全投影保存为 `TopologyProbeEvidenceRun`，关联同一个 Diagnosis，并在排障详情中展示当前结果和历史；原始响应、DQL、凭据和未覆盖节点健康结论都不落库。
- 自动化仍永久止于只读；关闭的 Diagnosis 不接收新证据，执行期间关闭的迟到结果也不得写入。

## 主要制品

- 蓝图新增 06A “部署拓扑拨测在主架构中的位置”。
- RFC 新增 §3.4、§5.11 与 D18，并把该场景纳入 P4 首个受控场景实现。
- 架构图、流程图、泳道图同步表达“资产 → 场景 → Tool → Adapter → EvidenceBundle → Diagnosis 详情”。
- Java 实现新增 V188、Diagnosis-scoped 运行/历史 API 与安全结果持久化；正式工作台不再运行独立、无 Diagnosis 的分析结果。

## 与 v0.17 的关系

v0.17 永久保留修正前的中间态，记录“共享拓扑库和独立分析弹窗已存在、结果尚未进入 Diagnosis”的真实事实；v0.18 才是当前边界。
