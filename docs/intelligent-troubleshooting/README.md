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
4. [架构蓝图 v0.10](./architecture-blueprint.html)
   面向讨论和汇报的精简可视化版本，已嵌入架构图、流程图和泳道图。
   [历史版本](./versions/index.html)按版本完整保留，不再覆盖。
5. [HANDOFF](./HANDOFF.md)
   当前实施状态、红线、真实缺口与接手指针。
6. [TODO](./TODO.md)
   当前优先级：无错误码证据→PlaybookDraft→参考解法比较→candidate。
7. [源码核对与安全论证附录](../../rfcs/intelligent-troubleshooting-design.md)
   native Workflow、ToolGuard、身份与通道等源码证据；不作为独立现行概要设计。

设计门户：[index.html](./index.html)。

## 配套可编辑图件

- [总体架构图](./diagrams/mateclaw-troubleshooting-architecture.drawio) · [SVG 预览](./diagrams/mateclaw-troubleshooting-architecture.svg)
- [端到端流程图](./diagrams/mateclaw-troubleshooting-flow.drawio) · [SVG 预览](./diagrams/mateclaw-troubleshooting-flow.svg)
- [跨角色泳道图](./diagrams/mateclaw-troubleshooting-swimlane.drawio) · [SVG 预览](./diagrams/mateclaw-troubleshooting-swimlane.svg)
- [架构蓝图版本库](./versions/index.html) · v0.7–v0.10 完整快照

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

## 七条不可突破的红线

1. 错误码 approved Playbook 命中路零 LLM。
2. 生产写工具不注册。
3. 人工批准只推进领域状态，不执行工具；写操作始终在系统外由人完成。
4. 未命中路 Agent 只有一个只读证据工具，且必须经过服务端会话、脱敏预算和引用校验。
5. AI 生成的 PlaybookDraft 永远先进入 candidate；模型不能批准知识或生成生产写。
6. Loop 预算、检查点和停止原因由服务端控制；Agent 不能自行续期或递归创建 Agent。
7. 多 Agent 只做结构化反证；共识、票数或 Judge 文本不能成为诊断/知识权威。

## 代码入口

- 后端：`mateclaw-server/src/main/java/vip/mate/troubleshooting/`
- 后端测试：`mateclaw-server/src/test/java/vip/mate/troubleshooting/`
- 前端：`mateclaw-ui/src/views/Troubleshooting/`
- 路由：`/troubleshooting`、`/troubleshooting/sops`
- Agent 启用与回滚：[agent-miss-path-runbook.md](./agent-miss-path-runbook.md)
- 证据适配设计：[observability-abstraction-design.md](./observability-abstraction-design.md)

## 当前真实状态

- 旧 P0–P4 领域底座已经落地；v4 P0 产品/架构/体验校准已完成并通过架构师评审。
- 当前 P1 复用已有 `SopSynthesisService.preview()`：日志取样、PS ID、全链路与确定性压缩已完成；
  结构化 PlaybookDraft、校验、参考解法比较和 candidate 幂等仍待实现。
- Loop Engineering 与多 Agent 反证已进入现行目标设计，但 P1 不实现；P2 先做固定角色影子评测，
  P4 才为 SCENARIO / OPEN_DISCOVERY 引入领域 Loop Control。
- P4 默认关闭，尚未完成专用 Agent 与唯一模型的实机演练。
- Guance Adapter 与 Recorded Replay Adapter 已接到统一 Router，但真实 measurement、字段与阈值
  尚未在内网验证，`fixtureMode` 仍应保持开启。
- 生产写执行能力不存在；`execute` 端点继续恒拒绝。
- “从日志生成 SOP”是当前产品主线之一，但产物只可成为 candidate，不得自动晋升或改写权威 Playbook。

## 历史材料

`intelligent-troubleshooting-architecture-v2.md`、`v3.md` 与 `meeting-change-plan.md` 只用于追溯讨论，
不再决定当前产品主线。早期原型可在设计门户的“历史原型 · 归档”区域查看。

蓝图从 v0.7 起执行“只新增、不覆盖”，发布规则见 [versions/README.md](./versions/README.md)。
