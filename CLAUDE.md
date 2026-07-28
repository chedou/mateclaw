# CLAUDE.md

本仓库是 **MateClaw**（太一 · Agent Harness，Spring Boot 3.5 / Java 21 / Vue 3）。
当前的**活跃工作**：在 MateClaw 之上落地 **IT 智能排障系统**（首个域 CSDP 工单/客服链路），
形态为 mateclaw-server 内的确定性领域模块 `vip.mate.troubleshooting`。

当前实施状态：**旧 P0–P4 领域底座已形成；新架构 v4 的 P0（产品/架构/体验校准）已完成，
下一步是 P1“无错误码证据→PlaybookDraft”竖线**。
P0 含 record 契约、6 类 sealed 规则、确定性命中编排、人工控制状态机、三方言 V172、
租户化事务 Outbox 与五分钟幂等；P1 含接入 controller（不走 Trigger，PAT 走既有 JwtAuthFilter）
与三个 capability；P2 含生命周期 REST、队列列表、Vue 工作台（含 `/troubleshooting/sops` SOP 管理）与
`ts.` 飞书 card kind。另含推导投影、SOP 管理 API，以及 P3 的 `EvidenceSourceRouter`、
`GuanceEvidenceAdapter`、`RecordedReplayAdapter`、脱敏 903001 回放样本与源状态 API。P4 新增
`TroubleshootingEvidenceTool`、服务端会话隔离、调用级硬工具白名单、证据引用校验、`Diagnosis` 1.4
兼容契约和未命中路 Vue 展示；相关定向回归与应用上下文启动测试通过。另有
`SopSynthesisService.preview()` 已完成 `log_search → PS ID → log_trace_bundle → 确定性压缩` 的
fixture-only 预演，尚未调用模型或创建 candidate。
**注意：P4 开关默认关闭，专用 Agent 尚未按运行手册配置和实机演练；观测云 measurement/字段/阈值仍未完成
内网 T2 核实，两个数据源默认关闭，`fixtureMode` 仍恒 true。**

## 接续这项工作，先读

0. **`docs/intelligent-troubleshooting/recording-product-baseline.md`** —— **现行产品事实**：
   2026-07-27 录音 F1–F11，明确核心差异化、首个案例、企微入口和能力边界。
1. **`rfcs/intelligent-troubleshooting-architecture-v4.md`** —— **唯一现行概要设计（当前 v4.2）**：
   证据脊柱、在线诊断/知识生产双闭环、ERROR_CODE/SCENARIO/OPEN_DISCOVERY 三种调查路径和分阶段实施。
   **§9 是红线的唯一权威清单**，其余文档只引用不复述；**§7.4 是通道复用（D17）**。
2. **`docs/intelligent-troubleshooting/architecture-review-v4.md`** —— **架构师评审**：
   Step 0、8 个架构问题、测试覆盖图、性能预算、失败模式与 P1 实施边界。
2.5 **`docs/intelligent-troubleshooting/architecture-critique-v4.md`** —— **第一性原理评价与修订决议**
   （用户已认可）：D5′ 晋升分档、D14 北极星时间戳、D15 成功样本对照、D16 PENDING-EVIDENCE 纪律。
2.6 **`docs/intelligent-troubleshooting/projection-contracts.md`** —— **已选定的两个投影合同**：
   BusinessSummary / DeveloperEvidenceView / NorthStarTimings，含服务端不变量与通道消费方式。
3. **`docs/intelligent-troubleshooting/architecture-blueprint.html`** —— **产品与架构蓝图 v0.12**：
   增加有界 Loop Engineering 与固定角色的多 Agent 结构化反证（**当前为 PENDING-EVIDENCE，不得据以新增实现**）；
   v0.10 统一修复三张图的正交走廊、箭头端点与标签间距；v0.11 / v0.12 图形不变，改动在 RFC。
   历史版本从 **`docs/intelligent-troubleshooting/versions/index.html`** 进入。
4. **`docs/intelligent-troubleshooting/TODO.md`** —— **接手第一站**：当前 P1 及 P2–P5 顺序、完成标准和测试清单。
5. **`docs/intelligent-troubleshooting/HANDOFF.md`** —— 当前真实状态与接手指针。
6. **`rfcs/intelligent-troubleshooting-design.md`** —— 源码核对与安全论证附录；
   §5 红线论证、§12 源码位置索引仍有效。
7. **`docs/intelligent-troubleshooting/detail-page-design.md`** —— 正式详情页历史设计；新信息结构先看 Vue Prototype。
8. **`docs/intelligent-troubleshooting/agent-miss-path-runbook.md`** —— 旧 P4 专用 Agent 配置、启用、验收与回滚。

`rfcs/intelligent-troubleshooting-architecture-v2.md`、`v3.md` 与
`docs/intelligent-troubleshooting/meeting-change-plan.md` 保留为历史讨论，不再决定当前产品主线。

## 关键约束（细节见 HANDOFF §3）

- 错误码 approved Playbook 命中路零 LLM（Workflow 每步调 LLM，故命中路必须是领域模块，不能是 native Workflow）。
- 生产写工具永不注册；ToolGuard 批准=回放执行，与"批准但不执行"语义相反，人工确认只推进领域状态机。
- 写操作永远外部人工 + 结果登记；未命中路 Agent 必须同时满足专用直接绑定校验、调用级硬白名单和
  服务端取证会话约束；受限图不注入会话/memory/wiki/runtime 上下文、不使用 provider fallback，
  必须显式绑定唯一 enabled 模型并跳过默认模型/capability routing；provider 禁用/未配置时直接 409，
  运行时失败则保守弃权，并禁用通用 `ToolResultStorage` 原始结果 spill。已有/新采集的
  canonical EvidenceResult 全字符串字段/递归 key 必须先脱敏、危险 queryId 安全重映射，再进入模型并随 Diagnosis
  持久化；初始未受信上下文必须受独立预算约束，模型原生搜索必须关闭；
  硬作用域必须清空并恢复请求级 ThinkingLevel，不得放大迭代/reasoning；原始工具参数仅可进入 Guard 与 callback，
  不得进入 event/SSE/log/audit/approval，`NEEDS_APPROVAL` 在硬作用域直接拦截；
  ToolGuard BLOCK 作为纵深防御，不能替代硬白名单。
- `l0/sop_kb.json` 已脱敏；源表 xlsx 含真实 token/IP/人名，未入库、不得入库。
- Loop 的预算、检查点、恢复和停止原因由服务端控制；Agent 不得递归委派、续期或通过共识提升权威。
- Evidence/Safety Challenger 只产结构化 `AdversarialEvalReport`，先 P2 影子运行；P1 不增加多 Agent 调用。
- 模型提议 Scenario 时只能返回已注册 `scenarioKey` 和候选参数；EvidencePlan/DQL/工具名均由服务端 approved
  Playbook 决定。OPEN_DISCOVERY 使用独立 DiscoveryPolicy，不能伪装成 approved Playbook。
- **通道一律复用平台现有 `ChannelAdapter` / `CardKind`，不新建入站**（D17）。企微已有
  `vip.mate.channel.wecom`；诊断卡片**不得**复用 tool-guard 的 `ApprovalNotice` 形状——
  "批准=回放执行"与排障"确认=只推进状态"语义相反。出站交互卡片需先泛化平台 renderer 接缝。

## 方法论 skills

`.claude/skills/` 装有 qiushi-skill（矛盾分析/集中兵力/持久战/群众路线/批评与自我批评等），`/<name>` 调用。

## 纪律

- 排障工作当前本地分支 `claude/intelligent-troubleshooting-design`；
  以用户当前明确选择的分支为准。
- 不擅自开 PR；改 RFC 保持 § 编号连续。
- 一切新设计与实现只基于当前 Java MateClaw 仓库；不得再引入第二套排障平台、独立 Python
  orchestrator 或 loopback 运行时。
- **架构版本只新增、不覆盖**：修改现行蓝图、RFC 或三张图之前，先把当前完整制品复制到
  `docs/intelligent-troubleshooting/versions/vX.Y/`；版本目录一旦发布不得覆盖、删除或原地修订，
  后续修正必须发布新版本并更新 `versions/index.html`。
- 每个版本至少保留 `architecture-blueprint.html`、三张 `.drawio`、三张 `.svg` 和三份 YAML 源；
  该版修改过 RFC/评审时一并快照。现行文件只是 latest 别名，不是历史存档。
- Draw.io 离线 SVG 导出会把复杂 waypoint 简化为中心直线。流程图发布前必须用浏览器直接渲染 SVG
  检查箭头端点、回路走廊和节点穿越；未经视觉复核不得覆盖已经人工正交路由的 SVG。
