# v0.21 · OPEN_DISCOVERY 确定性有界调查窄线

- 发布日期：2026-08-17
- 架构版本：RFC v4.6 实现状态校准
- 图形语义：与 v0.20 相同；本版冻结的是有界调查实现边界，不新增生产写能力。

## 本版落地

- `HypothesisGraph`：保存服务端登记的候选原因、问题、判据、状态和证据引用。
- `ReadOnlyToolRegistry`：只接受 `READ_EVIDENCE` Bean，并强制 Tool 版本、信号类型、平台白名单、
  canonical 输出和剩余 deadline。
- `BoundedInvestigationPlanner`：每轮只执行一个最高价值问题，受迭代、调用和总时长三类预算限制。
- `RootCauseFinding`：只有一个方向得到支持且其他方向全部排除时才允许 `LOCATED`；并列方向完整展示，
  全部缺证时持久化弃权与精确停止原因。
- 首个试点只开放按服务冻结的 Guance 应用 ERROR 聚合查询；Kubernetes 资产未登记时保持 `MISSING`，
  不猜 namespace、deployment 或 cluster 字段。

## 安全边界

- 有审核 Playbook 的命中路保持零 LLM，现有 hard-scoped Agent 可用时不重复执行本窄线。
- 模型和浏览器不能选择平台、DQL、端点、凭据或 Tool 实现。
- 只持久化计划指纹、预算、停止原因和安全证据引用，不保存 DQL、observed、原始日志或密钥。
- 计划指纹覆盖候选、问题、Tool 版本、目标、窗口、判据/阈值与预算。
- 不执行任何生产写；候选原因置信封顶 `MEDIUM`，弃权为 `LOW / INSUFFICIENT_EVIDENCE`。

## 尚未证明

- 尚未用一条新的非演练未知告警证明该候选能帮助开发更快定位；T7 Owner 正式录制仍为 `0 / 20`。
- Kubernetes、HCI、拨测和更多日志语义 Tool 必须有 owner 合同与真实样本后再增加。
- 多 Agent 协同与对抗仍不参与在线裁决。
