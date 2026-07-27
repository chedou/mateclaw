# CLAUDE.md

本仓库是 **MateClaw**（太一 · Agent Harness，Spring Boot 3.5 / Java 21 / Vue 3）。
当前的**活跃工作**：在 MateClaw 之上落地 **IT 智能排障系统**（首个域 CSDP 工单/客服链路），
形态为 mateclaw-server 内的确定性领域模块 `vip.mate.troubleshooting`。

当前实施状态：**P0 内核 + P1 接入与身份 + P2 交付闭环 + P3 命中路证据适配底座 +
P4 未命中路只读 Agent 工程链路已完成**。
P0 含 record 契约、6 类 sealed 规则、确定性命中编排、人工控制状态机、三方言 V172、
租户化事务 Outbox 与五分钟幂等；P1 含接入 controller（不走 Trigger，PAT 走既有 JwtAuthFilter）
与三个 capability；P2 含生命周期 REST、队列列表、Vue 工作台（含 `/troubleshooting/sops` SOP 管理）与
`ts.` 飞书 card kind。另含推导投影、SOP 管理 API，以及 P3 的 `EvidenceSourceRouter`、
`GuanceEvidenceAdapter`、`RecordedReplayAdapter`、脱敏 903001 回放样本与源状态 API。P4 新增
`TroubleshootingEvidenceTool`、服务端会话隔离、调用级硬工具白名单、证据引用校验、`Diagnosis` 1.4
兼容契约和未命中路 Vue 展示；相关定向回归与应用上下文启动测试通过。
**注意：P4 开关默认关闭，专用 Agent 尚未按运行手册配置和实机演练；观测云 measurement/字段/阈值仍未完成
内网 T2 核实，两个数据源默认关闭，`fixtureMode` 仍恒 true。**

## 接续这项工作，先读

0. **`docs/intelligent-troubleshooting/meeting-change-plan.md`** —— **2026-07 会议驱动的变更方案
   （C1–C8）**：方向已调整为「**从观测云日志自动生成 SOP**」，并写明了哪条会议诉求撞红线、不采纳。
   下一阶段做什么以它为准。
1. **`docs/intelligent-troubleshooting/TODO.md`** —— **接手第一站**：每条待办都写了
   「为什么这么做 / 做到什么算完」，含四条红线、诚实缺口清单、工程约定与建议接手顺序
   （会议新增战线 T11–T18 见第三·五节）。
2. **`docs/intelligent-troubleshooting/HANDOFF.md`** —— 会话记忆：8 个已锁定决策（D1–D8）、
   四条红线、当前阶段矛盾分析、指针与安全口径。
3. **`rfcs/intelligent-troubleshooting-architecture-v2.md`** —— **现行架构**（第一性原理重做：
   公理 A1–A6 → 定律 L1–L6 → 执行/学习/信任三环 → SOP 四维分解 → 分层与不变量）。
   `rfcs/intelligent-troubleshooting-design.md` 降为 v1 证据附录（红线论证 §5、源码索引 §12 仍有效）。
4. **`docs/intelligent-troubleshooting/detail-page-design.md`** —— 详情页唯一现行设计（三层深度）；
   原型 `console-detail.html`，其余 `console-*.html` 均为历史存档。
5. **`docs/intelligent-troubleshooting/agent-miss-path-runbook.md`** —— P4 专用 Agent 配置、启用、验收与回滚。

## 关键约束（细节见 HANDOFF §3）

- 命中路零 LLM（Workflow 每步调 LLM，故命中路必须是领域模块，不能是 native Workflow）。
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

## 方法论 skills

`.claude/skills/` 装有 qiushi-skill（矛盾分析/集中兵力/持久战/群众路线/批评与自我批评等），`/<name>` 调用。

## 纪律

- 排障工作当前本地分支 `claude/intelligent-troubleshooting-design`；
  以用户当前明确选择的分支为准。
- 不擅自开 PR；改 RFC 保持 § 编号连续。
- 旧仓库 **webonne/MetaClaw** 已归档为只读参考（Python MVP 参考实现在其 `zhinengpaizhang-dev` 分支），
  一切新工作只在本仓库进行。
