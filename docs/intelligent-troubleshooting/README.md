# IT 智能排障系统 · 文档入口

本系统只基于当前 Java **MateClaw** 实现，作为 `mateclaw-server` 内的领域深模块
`vip.mate.troubleshooting` 运行。后续不引入第二套排障平台、独立 Python orchestrator
或 loopback 运行时。

## 现行基线

按下面顺序阅读：

1. [录音产品基线](./recording-product-baseline.md)
   唯一产品事实来源：F1–F11、首个无错误码案例、企微入口与能力边界。
2. [现行概要设计 v4](../../rfcs/intelligent-troubleshooting-architecture-v4.md)
   一条证据脊柱、在线诊断/知识生产两个闭环、三类调查路径与实施顺序。
3. [架构师评审 v4](./architecture-review-v4.md)
   评审结论、范围收敛、测试覆盖图、失败模式和资源预算。
4. [架构蓝图 v0.18](./architecture-blueprint.html)
   面向讨论和汇报的精简可视化版本，已嵌入架构图、流程图和泳道图。
   [历史版本](./versions/index.html)按版本完整保留，不再覆盖。
5. [HANDOFF](./HANDOFF.md)
   当前实施状态、红线、真实缺口与接手指针。
6. [TODO](./TODO.md)
   P1 已完成；当前唯一优先级是 P2：对首条真实 Guance Evidence Spine 完成
   owner T7 验收，再累积 T8 影子样本。
7. [P1 主链路验证记录](./p1-verification.md)
   固定 Replay Eval、REST 实测、fail-closed 边界与未完成范围。
8. [源码核对与安全论证附录](../../rfcs/intelligent-troubleshooting-design.md)
   native Workflow、ToolGuard、身份与通道等源码证据；不作为独立现行概要设计。

设计门户：[index.html](./index.html)。

## 配套可编辑图件

- [总体架构图](./diagrams/mateclaw-troubleshooting-architecture.drawio) · [SVG 预览](./diagrams/mateclaw-troubleshooting-architecture.svg)
- [端到端流程图](./diagrams/mateclaw-troubleshooting-flow.drawio) · [SVG 预览](./diagrams/mateclaw-troubleshooting-flow.svg)
- [跨角色泳道图](./diagrams/mateclaw-troubleshooting-swimlane.drawio) · [SVG 预览](./diagrams/mateclaw-troubleshooting-swimlane.svg)
- [架构蓝图版本库](./versions/index.html) · v0.7–v0.18 完整快照

## 一句话架构

```text
企微/Web 报障 → Intake 补齐上下文 → 只读 EvidencePlan → EvidenceBundle
  ├─ 调查内循环 → 取证 / 提议 / 确定性校验 → 完成 / 弃权 / 转人工
  └─ 知识外循环 → PlaybookDraft → 结构化反证 / 回放 → 人工审核 → approved Playbook

调查模式：ERROR_CODE_PLAYBOOK | SCENARIO_PLAYBOOK | OPEN_DISCOVERY
路由权威：EXPLICIT | RULE_MATCHED | MODEL_PROPOSED

Agent 暴露面：唯一 TroubleshootingEvidenceTool
内部扩展面：ReadOnlyEvidenceToolRegistry → Tool SPI → EvidenceSourceAdapter SPI

Loop 控制面：LoopPolicy → LoopRun → LoopOutcome
反证评测面：Evidence Challenger + Safety Challenger → AdversarialEvalReport
```

## 不可突破的红线

红线的**唯一权威清单**是 [现行概要设计 v4 §9](../../rfcs/intelligent-troubleshooting-architecture-v4.md)。
本入口不复制条目，避免 README、HANDOFF、TODO 和蓝图之间出现口径分叉。

## 代码入口

- 后端：`mateclaw-server/src/main/java/vip/mate/troubleshooting/`
- 后端测试：`mateclaw-server/src/test/java/vip/mate/troubleshooting/`
- 前端：`mateclaw-ui/src/views/Troubleshooting/`
- 路由：`/troubleshooting`、`/troubleshooting/sops`
- Agent 启用与回滚：[agent-miss-path-runbook.md](./agent-miss-path-runbook.md)
- 证据适配设计：[observability-abstraction-design.md](./observability-abstraction-design.md)

## 当前真实状态

- 旧 P0–P4 领域底座已经落地；v4 P0 产品/架构/体验校准已完成并通过架构师评审。
- P1 无错误码竖线已完成：固定三次取证、成功样本对照、确定性压缩、一次结构化归纳、
  Validator、参考解法比较、幂等 candidate 与北极星时间戳。
- 正式 `/troubleshooting` 已吸收服务经理摘要 + 开发证据台双投影，原工作台保留在
  `/troubleshooting/legacy`；两者读取同一 Diagnosis，不维护第二份事实。
- P3 已完成企微普通消息 pre-route、IntakeSession 补问、READY 持久化异步调查、Intake 归属幂等
  Diagnosis、workspace-aware leader 路由恢复、平台 ACK、持久最终投递、纯文本 BusinessSummary 与正式
  工作台深链；关闭且 outcome 已登记后，V180 持久化任务会沿精确原路 @ 报障人并发送最终结果。
  对企微信群，持久化 `ChannelSession.targetId/senderId` 决定是否必须使用当前入站 reply context；服务重启
  后缺少 `req_id` 时任务留在队列等待群内新消息，绝不误回落 `aibot_send_msg`。结案摘要先经过 500 字、
  凭据/DQL/伪造 mention 校验，通道正文再受 1800 字预算和 mention 转义保护。正式工作台同步展示
  `ClosureRecord`；交互卡片仍单独暂缓。
- Loop Engineering 与多 Agent 反证已进入现行目标设计，但 P1 不实现；P2 先做固定角色影子评测，
  P4 才为 SCENARIO / OPEN_DISCOVERY 引入领域 Loop Control。
- P4 默认关闭，尚未完成专用 Agent 与唯一模型的实机演练。
- Guance Adapter 与 Recorded Replay Adapter 已接到统一 Router。CSDP SendMsg 的
  `log_search / log_trace_bundle / contrast_sample` 已在真实 Guance `csp-rpc-msg` 数据上
  首次返回 `FULL_SPINE_OBSERVED`；trace 从单条原子 JSON 日志提取白名单字段，成功样本对照使用
  独立的显式 `success` 终态 cohort、按 `@trace_id` 去重并同窗单桶聚合。早期同状态条件及仅
  `NOT failed` 的代理对照结果已废弃。当前没有 owner
  `ACCEPTED` 记录和 T8 持久化样本，
  其他 measurement/字段/阈值也未全部验证，因此 `fixtureMode` 仍应保持开启。正式工作台的开发证据台已有
  **P2 真源门**：按 workspace/system/service 显示绑定就绪状态，并允许管理员发起一次
  Guance-only `log_search → log_trace_bundle` 只读验证。该入口不返回原始日志/DQL/密钥、
  不回退 Replay、不持久化验证数据；报告只增加应用侧每步与端到端 round-trip。页面分别显示 T6 授权、
  T7 真字段验收和 T8 20–30 条历史样本门禁，单次通过不代表 T7/T8 验收完成。
- 本地无真实模型配置时，生成接口已实测会返回 `MODEL_REJECTED`且不产生 candidate。
- 生产写执行能力不存在；`execute` 端点继续恒拒绝。
- “从日志生成 SOP”是当前产品主线之一，但产物只可成为 candidate，不得自动晋升或改写权威 Playbook。
- 部署拓扑 `MANUAL` 候选已有服务端固定正例/健康反例/缺证据弃权回放 Gate，证明与候选和套件双
  SHA-256 绑定；通过只表示可进入人工批准，不代表 T7/T8 完成，也不会自动成为命中权威。

## 历史材料

`intelligent-troubleshooting-architecture-v2.md`、`v3.md` 与 `meeting-change-plan.md` 只用于追溯讨论，
不再决定当前产品主线。早期原型可在设计门户的“历史原型 · 归档”区域查看。

蓝图从 v0.7 起执行“只新增、不覆盖”，发布规则见 [versions/README.md](./versions/README.md)。
