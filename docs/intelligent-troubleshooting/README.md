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
4. [架构蓝图 v0.19](./architecture-blueprint.html)
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
- [架构蓝图版本库](./versions/index.html) · v0.7–v0.19 完整快照

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
- Guance Skills 安全融合：[guance-skill-integration.md](./guance-skill-integration.md)
- 取证查询目录：[evidence-query-catalog.md](./evidence-query-catalog.md)

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
- P4 默认关闭；本地 Workspace 已完成专用 Agent、唯一可用模型与唯一只读工具的
  首次受限 miss-path 演练，其他环境仍需独立配置验收，该结果不代表 Guance T7/T8。
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
- T7 批次预检不再接受操作员自填 `selector/searchTerm` 来宣称目标可执行。运行服务通过
  `GET /evidence/guance/recording-targets` 返回精确绑定 selector、candidate、evidence request 与三份
  Guance binding 的未录制目标；candidate/request 双指纹由服务端从目录内完整 `SopEntry` 与被选中的
  `EvidenceRequest` 重新计算，不接受目录作者自填哈希。本地计划只引用 `targetId` 并补历史时间；目录响应与
  计划都在字段读取前拒绝重复 JSON 键和尾随根值。当前随仓目录为 **0 个**：
  唯一已核实的 SendMsg 合同已经形成录制种子，其他错误码尚没有真实查询合同，因此现在报
  “不能约 20–30 条批次窗口”才是正确结果。
- 窗口外 owner 准备清单已由代码确定性生成：[Markdown 队列](./t7-target-contract-preparation.md) ·
  [JSON 合同](./t7-target-contract-preparation.json)。冻结 146 条 D1 中，清洗出 30 条只读候选：
  **1 条已录制、1 条被源材料质量阻断、28 条待 owner 补查询合同、0 条已冻结待运行验证**。
  源质量冲突项保持隔离并行回源，不计入也不阻塞从其余 28 条中完成建议 20 条；
  该清单明确为 `PREPARATION_ONLY`，不生成 DQL、凭据或可执行 target；CI 会同时校验错误字段、
  重复键/尾随根值和生成物漂移。
- Owner 不再靠聊天补齐 28 条合同：[填写说明](./t7-owner-contract-intake.md) 和
  [建议首批 20 条工作表](./t7-owner-contract-intake.recommended.template.json) 已生成；
  [空白 28 条模板](./t7-owner-contract-intake.template.json) 供 owner 调整选择。当前分层为
  `A_HINTED=15 / B_CONTEXT_ONLY=2 / C_SOURCE_GAPS=11`；工作表的占位符未全部替换时必须校验失败，
  全部核实后校验通过仍只是
  `PREPARED_NOT_EXECUTABLE`，不会写 `guance-recording-targets.json`、不会调 Guance，也不能代替
  T7 预检或 owner `ACCEPTED`。完成文件携带环境运维元数据，不得提交到仓库。
- 正式工作台与 owner acceptance 写接口现在共用同一份服务端目标目录门禁。目录未达到
  **20 个可执行目标**时，页面明确显示 `0 / 20` 与 T7 `BLOCKED`，隐藏验收清单；即使绕过前端直接调用
  `POST /evidence/guance/acceptance`，服务端也会在发起 Guance 读链前返回冲突。单条查询合同验证仍可在
  窗口外运行，但不再被表述为“进入 T7”或“批次就绪”。
- RFC v4.5 / D19 已关闭错误码晋升的机制缺口：安全有界的录制聚合正例按封闭判据形状生成
  排除/弃权例，不降低原晋升门；固定套件 fail-fast，坏生成种子按 selector 隔离。首个
  `CSDP / csp-rpc-msg / IM1010` 已通过真实 HTTP 登录、晋升、报障与投影链，结果为
  `LOCATED / MEDIUM / fixtureMode=true`。其余 145 条错误码仍待分批导入，T7 仍未完成。
- 本地无真实模型配置时，生成接口已实测会返回 `MODEL_REJECTED`且不产生 candidate。
- 生产写执行能力不存在；`execute` 端点继续恒拒绝。
- “从日志生成 SOP”是当前产品主线之一，但产物只可成为 candidate，不得自动晋升或改写权威 Playbook。
- 部署拓扑 `MANUAL` 候选已有服务端固定正例/健康反例/缺证据弃权回放 Gate，证明与候选和套件双
  SHA-256 绑定；通过只表示可进入人工批准，不代表 T7/T8 完成，也不会自动成为命中权威。

## 历史材料

`intelligent-troubleshooting-architecture-v2.md`、`v3.md` 与 `meeting-change-plan.md` 只用于追溯讨论，
不再决定当前产品主线。早期原型可在设计门户的“历史原型 · 归档”区域查看。

蓝图从 v0.7 起执行“只新增、不覆盖”，发布规则见 [versions/README.md](./versions/README.md)。
