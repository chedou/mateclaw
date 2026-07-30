# v0.17 · 部署拓扑拨测专项入口中间态

- 发布日期：2026-07-30
- 架构版本：RFC v4.3；本版冻结修正前的完整制品。
- 图形语义：与 v0.16 一致，尚未显式画出部署拓扑拨测场景。
- 触发原因：实现已增加 Workspace 共享拓扑库、Guance CloudDial 只读拨测和独立分析弹窗，
  但运行结果尚未关联 Diagnosis，也未进入 EvidenceBundle 与排障详情页。

## 本版冻结的事实

- 拓扑资产可安全导入、workspace 隔离、幂等复用，原始主机、DQL、凭据和未知字段不入库。
- 服务端通过既有 `EvidenceSourceRouter` 执行 Guance-only `synthetic_probe`，最多 32 个拨测、
  8 路并发、25 秒总预算。
- 结果只存在于前端弹窗内存；不接收 `diagnosisId`，不持久化，不参与 Diagnosis Engine。
- 用户已明确否定“独立专项链路”定位：部署拓扑拨测应当同时是整体排障中的
  `SCENARIO_PLAYBOOK` 和内部只读证据 Tool，其结果必须回到同一 Diagnosis。

## 后续修正

v0.18 将修订 RFC 与三张图，明确“拓扑资产 → 场景 Playbook → 语义 Tool → 来源 Adapter
→ canonical EvidenceBundle → Diagnosis 详情”的唯一主链，不再扩展独立弹窗结果。
