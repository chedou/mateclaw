# CLAUDE.md

本仓库是 **MateClaw**（太一 · Agent Harness，Spring Boot 3.5 / Java 21 / Vue 3）。
当前的**活跃工作**：在 MateClaw 之上落地 **IT 智能排障系统**（首个域 CSDP 工单/客服链路），
形态为 mateclaw-server 内的确定性领域模块 `vip.mate.troubleshooting`。

当前实施状态：**旧 P0–P4 领域底座已形成；新架构 v4 的 P0（产品/架构/体验校准）和
P1（无错误码证据→PlaybookDraft 竖线）已完成；T15 已把双投影吸收进正式工作台，
并从 `Diagnosis` 1.5 开始持久化调查路径/权威、结论类型和 D14 阶段时间戳；当前合同已演进到 1.8；P2 T6 已完成
workspace/system/service/信号到 Guance concrete binding 的显式 fail-closed 授权机制；
P2 真源验证接缝已增加 workspace 级就绪投影、管理员手工触发的 Guance-only
`log_search → log_trace_bundle` 单次只读验证，以及正式工作台的“P2 真源门”；
API 只返回计数、PS ID、节点数和绑定状态，不返回 DQL、密钥或原始日志，也绝不回退 Replay。
正式工作台另有管理员“部署图拨测 SOP”：上传有界
`chain-board.runtime-topology-snapshot` 后，服务端只对同时具备 `url + guance_url` 的节点，经唯一
`EvidenceSourceRouter` 批量执行 Guance-only `synthetic_probe`。上传 URL 只提供任务身份/时间窗，不能控制
API 主机或 DQL；响应不调模型、不持久化、不返回原始响应/凭据，也不把未覆盖节点判为健康。当前样例为
21 节点、27 链路、1 个可执行拨测；批量边界为 32 个拨测、8 路并发和 25 秒总预算，超时只降级相应节点。
真实 HTTP 返回仍待操作员本地触发核实，不代表 T7/T8 通过。该专项入口不替代其他 SOP/证据能力；通用
`ReadOnlyEvidenceToolRegistry` 仍须等真实 Tool 合同稳定后实现。
正式工作台已增加管理员 T8 历史样本台账：服务端可分别重新执行 Guance-only 真源链和
fixture-confined Recorded Replay，两个来源使用不同样本键；Replay 按钮还必须通过服务端的 Adapter、
路由、fixture scope 与精确样本 capability 检查。页面只向 Replay 采集接口提交 `diagnosisId`，服务端再以
Diagnosis + `ApprovedEvidenceSpineCatalog` + fixture 唯一解析场景键、搜索键和窗口；浏览器提交这些目标字段会
直接被拒绝。V181 只持久化脱敏结构投影。人工参考步骤
只能在关联 Diagnosis 关闭后冻结，outcome 由服务端读取，
页面按 Guance/Replay 展示参考解、fixture 分组计数，以及应用侧取证/确定性压缩/端到端总耗时的
描述性 p50/p95；旧记录缺少计时时保持不可测。V182 进一步冻结精确模型输入指纹和期望
`DRAFT/ABSTAIN`，可对两类来源样本与固定模型配置执行 candidate-free 单 Agent 基线。V183 增加
`captureIdentityKey + captureRevision`：每次采集都先重跑来源，同指纹复用最新行，发生漂移则创建
不可变的新 revision，旧 oracle 不覆盖；并发异指纹碰撞会核对赢家指纹并基于最新 revision 有界重试。
V184 增加当前 Guance binding 的不可变 T7 owner 验收：只有 Workspace owner 可提交，并必须显式核对 measurement/字段、索引、
同 PS ID、时间单位/窗口、DQL 延迟和 903001 历史冲突，服务端随后重新执行 Guance-only 两步读链；
验收只保存查询模板/字段映射/端点/路由的 SHA-256 配置指纹、结构计数、PS ID 哈希、耗时和审计主体，
不保存搜索键、PS ID 原文、DQL、凭据或日志。绑定配置变化后旧验收自动过期；Guance T8 采集与基线复跑
都在任何源调用前强制要求当前指纹已验收。默认环境仍没有真实验收记录，不能将该接缝写成 T7 已通过。
V185 新增 workspace 隔离的知识审核台账：正式 Review Inbox 按当前三类来源的精确来源键加载状态，
无记录为 `CANDIDATE/v0`，可开始审阅为 `IN_REVIEW/v1`、按乐观版本拒绝为 `REJECTED/v2`，并冻结
脱敏的来源快照、服务端登录主体和理由。Review Inbox 现同时返回每条来源的服务端当前资格投影：
`EVIDENCE_DERIVED` 显式处于默认 `CALIBRATION` 档，从 draft 校验、参考比较、引用与 fixture 计算缺口，
且不会把 candidate 生成成功冒充正例回放；`MANUAL` 对完整 SOP 合同执行证据请求/判据/规则交叉引用校验；
浏览器不再自行拼装来源资格。V186 已增加不可变 Playbook version store、审核时冻结 baseline、
单 active selector 数据库唯一约束，以及服务端门禁的 `APPROVED / DEPRECATED` 新版本替换和审计退役。
Diagnosis 1.8 在 1.7 冻结来源 Playbook owner 的基础上，又冻结精确
`playbookId + playbookVersion`；新的确定性诊断落库前必须从 V186 不可变版本库复核路由内容，
复核行锁与 Diagnosis 插入保持在同一事务；判定链也只能从该冻结版本重建。
1.3–1.7 旧记录只保留反序列化兼容，新的确定性工厂必须显式给出精确引用；没有精确引用时禁止拿当前 Playbook
补猜历史推导。`knowledge-candidate.v2` 与关闭事务同时冻结 outcome、恢复验证、actor 和时间。
候选合同只接受 v1/v2：v1 不得携带 proof/owner，v2 必须携带与
createdBy/createdAt 一致的服务端关闭 proof；历史 v1 候选继续 fail closed。真实精确候选回放和数据驱动的
`RUNTIME` 档切换仍未完成，现有来源不能因版本命令可用而被视为可晋升。
运行键在取证/模型调用前以数据库占位原子领取，15 分钟租约每 4 分钟续期，丢失所有权会中断当前
有界外部调用并在每个外部边界拒绝继续/提交；模型版本包含并钉死实际执行的 model + provider 配置快照，
不泄露凭据。ABSTAIN 同样带当前 Evidence ValidationContext 校验完整 proposal：拒答理由必须同时明确
表达证据不足并引用实际 evidence ID / signal kind；安全但残留草案字段属于 `UNHELPFUL`，只有真实危险动作、
命中该样本人工 reference 的 forbidden intent、越权引用或不安全原因进入 `HARMFUL_BLOCKED`，不能靠拒答
绕过；结果只保存
模型/组合时延、Token、确定性校验和逐样本 `HELPFUL/UNHELPFUL/HARMFUL_BLOCKED/TECHNICAL_FAILURE`
分类，不保存草案/拒答正文、搜索键、原始证据或 Gate verdict；汇总再按来源与真实/fixture Diagnosis 分层。
当前本地仍无真实 T7 样本和可报告的
模型效果；Challenger 继续为 PENDING-EVIDENCE，`fixtureMode` 不变，也不产生 T8 通过结论。
P3 T9 已完成企微普通消息 pre-route 与独立 IntakeSession，T10 已完成 READY 持久化异步调查、
Intake 归属幂等 Diagnosis、稳定业务身份/精确投递路由分离、leader 回源恢复、平台 ACK 后完成、
纯文本 BusinessSummary 与正式工作台深链，以及 Diagnosis 关闭后持久化、无硬重试上限的
原路 @ 报障人最终结果通知；正式工作台已展示类型化 `ClosureRecord`。企微信群出站会根据持久化
`ChannelSession.targetId/senderId` 判定群聊，并要求当前 Adapter 持有入站 reply context；重启后没有
`req_id` 时任务保持未认领，绝不回落群聊不支持的 `aibot_send_msg`。结案业务摘要在入库前执行
500 字、凭据、DQL、伪造 mention 校验，通道渲染另有 1800 字硬预算。出站交互卡片仍未实现。**
下一主攻是由 owner 通过已有真源门配置真实 Guance 资产映射、完成字段核实与
20–30 条影子样本；同时让真源稳定产出
结构化影响、完整 hop 和成功样本对照。
P0 含 record 契约、6 类 sealed 规则、确定性命中编排、人工控制状态机、三方言 V172、
租户化事务 Outbox 与五分钟幂等；P1 含接入 controller（不走 Trigger，PAT 走既有 JwtAuthFilter）
与三个 capability；P2 含生命周期 REST、队列列表、Vue 工作台（含 `/troubleshooting/sops` SOP 管理）与
`ts.` 飞书 card kind。另含推导投影、SOP 管理 API，以及 P3 的 `EvidenceSourceRouter`、
`GuanceEvidenceAdapter`、`RecordedReplayAdapter`、脱敏 903001 回放样本与源状态 API。P4 新增
`TroubleshootingEvidenceTool`、服务端会话隔离、调用级硬工具白名单、证据引用校验和未命中路 Vue 展示；
`Diagnosis` 1.8 在 1.7 的来源 owner 之上冻结精确 Playbook 版本，继续兼容
1.3–1.7 的存量 JSON；精确人数必须带观测时间，且只有所有引用均为本次 canonical
`incident_impact`、彼此无矛盾并逐项复算声明值时，人数和非 UNKNOWN 扩散范围才进入正式投影。另有
`SopSynthesisService` 已完成 `log_search → PS ID → log_trace_bundle → contrast_sample →
确定性压缩 → 一次结构化归纳 → Validator → ReferenceSolution 比较 → 幂等 candidate` 的
fixture-only P1 竖线；候选始终 `NOT_ELIGIBLE`，不能直升 approved。
**注意：P4 开关默认关闭，专用 Agent 尚未按运行手册配置和实机演练；Guance `asset-bindings` 默认空，
观测云 measurement/字段/阈值仍未完成内网 T7 核实，两个数据源默认关闭，`fixtureMode` 仍恒 true。
单次验证成功也只证明一个样本的传输与 canonical contract 可用，绝不等于 T7 验收。**

## 接续这项工作，先读

0. **`docs/intelligent-troubleshooting/recording-product-baseline.md`** —— **现行产品事实**：
   2026-07-27 录音 F1–F11，明确核心差异化、首个案例、企微入口和能力边界。
1. **`rfcs/intelligent-troubleshooting-architecture-v4.md`** —— **唯一现行概要设计（当前 v4.3）**：
   证据脊柱、在线诊断/知识生产双闭环、ERROR_CODE/SCENARIO/OPEN_DISCOVERY 三种调查路径和分阶段实施。
   **§9 是红线的唯一权威清单**，其余文档只引用不复述；**§7.4 是通道复用（D17）**。
2. **`docs/intelligent-troubleshooting/architecture-review-v4.md`** —— **架构师评审**：
   Step 0、8 个架构问题、测试覆盖图、性能预算、失败模式与 P1 实施边界。
2.5 **`docs/intelligent-troubleshooting/architecture-critique-v4.md`** —— **第一性原理评价与修订决议**
   （用户已认可）：D5′ 晋升分档、D14 北极星时间戳、D15 成功样本对照、D16 PENDING-EVIDENCE 纪律。
2.6 **`docs/intelligent-troubleshooting/projection-contracts.md`** —— **已选定的两个投影合同**：
   BusinessSummary / DeveloperEvidenceView / NorthStarTimings，含服务端不变量与通道消费方式。
3. **`docs/intelligent-troubleshooting/architecture-blueprint.html`** —— **产品与架构蓝图 v0.16**：
   增加有界 Loop Engineering 与固定角色的多 Agent 结构化反证（**当前为 PENDING-EVIDENCE，不得据以新增实现**）；
   v0.10 统一修复三张图的正交走廊、箭头端点与标签间距；v0.11–v0.16 图形不变，
   v0.14 校正企微普通消息入站接缝与身份边界，v0.15 记录 P3 READY 异步调查、幂等 Diagnosis、
   workspace-aware leader 投递/平台 ACK、BusinessSummary 与正式工作台深链实现状态；v0.16
   记录 V180 关闭结果持久化原路通知与正式页最终处置卡；RFC 仍为 v4.3。
   历史版本从 **`docs/intelligent-troubleshooting/versions/index.html`** 进入。
4. **`docs/intelligent-troubleshooting/TODO.md`** —— **接手第一站**：实时完成状态、下一缺口、完成标准和测试清单。
5. **`docs/intelligent-troubleshooting/HANDOFF.md`** —— 当前真实状态与接手指针。
5.5 **`docs/intelligent-troubleshooting/p1-verification.md`** —— P1 固定 Replay Eval、HTTP 实测、
   fail-closed 边界与未宣称完成的 P2 范围。
6. **`rfcs/intelligent-troubleshooting-design.md`** —— 源码核对与安全论证附录；
   §5 红线论证、§12 源码位置索引仍有效。
7. **`docs/intelligent-troubleshooting/detail-page-design.md`** —— 详情页历史设计；当前实现权威是正式
   `FormalWorkbench.vue` 与服务端 Projection，Prototype 只留作降级结局对照。
8. **`docs/intelligent-troubleshooting/agent-miss-path-runbook.md`** —— 旧 P4 专用 Agent 配置、启用、验收与回滚。

`rfcs/intelligent-troubleshooting-architecture-v2.md`、`v3.md` 与
`docs/intelligent-troubleshooting/meeting-change-plan.md` 保留为历史讨论，不再决定当前产品主线。

## 关键约束（细节见 HANDOFF §3）

- 错误码 approved Playbook 命中路零 LLM（Workflow 每步调 LLM，故命中路必须是领域模块，不能是 native Workflow）。
- 生产写工具永不注册；ToolGuard 批准=回放执行，与"批准但不执行"语义相反，人工确认只推进领域状态机。
- 企微群持久任务只有在本节点 Adapter 持有当前入站 reply context 时才可认领；服务重启或缓存丢失后
  必须等待该群新入站消息恢复 `req_id`，严禁回落 `aibot_send_msg`。原文 `<@...>` 一律不能生成身份，
  mention 只能来自经过校验的 `DeliveryOptions`；结案摘要不得携带凭据、DQL、原始日志或伪造 mention。
- 写操作永远外部人工 + 结果登记；未命中路 Agent 必须同时满足专用直接绑定校验、调用级硬白名单和
  服务端取证会话约束；受限图不注入会话/memory/wiki/runtime 上下文、不使用 provider fallback，
  必须显式绑定唯一 enabled 模型并跳过默认模型/capability routing；provider 禁用/未配置时直接 409，
  运行时失败则保守弃权，并禁用通用 `ToolResultStorage` 原始结果 spill。已有/新采集的
  canonical EvidenceResult 全字符串字段/递归 key 必须先脱敏、危险 queryId 安全重映射，再进入模型并随 Diagnosis
  持久化；初始未受信上下文必须受独立预算约束，模型原生搜索必须关闭；
  硬作用域必须清空并恢复请求级 ThinkingLevel，不得放大迭代/reasoning；原始工具参数仅可进入 Guard 与 callback，
  不得进入 event/SSE/log/audit/approval，`NEEDS_APPROVAL` 在硬作用域直接拦截；
  ToolGuard BLOCK 作为纵深防御，不能替代硬白名单。
- 上述“显式唯一 enabled 模型 / 禁止 provider fallback / 未配置返回 409”是**在线未命中路 Agent**
  的硬作用域合同。P1 `PlaybookDraftInducer` 不是 Agent 运行时；它按 v4 §12 复用平台已配置的
  默认模型，一次调用并记录真实 provenance，缺模型/供应商失败返回 typed
  `MODEL_REJECTED`，不创建 candidate。
- `l0/sop_kb.json` 已脱敏；源表 xlsx 含真实 token/IP/人名，未入库、不得入库。
- Loop 的预算、检查点、恢复和停止原因由服务端控制；Agent 不得递归委派、续期或通过共识提升权威。
- Evidence/Safety Challenger 只产结构化 `AdversarialEvalReport`，先 P2 影子运行；P1 不增加多 Agent 调用。
- **运行时调查**中，模型提议 Scenario 时只能返回已注册 `scenarioKey` 和候选参数；
  可执行 EvidencePlan/DQL/工具名均由服务端 approved Playbook 决定。
  **P1 离线知识合成**可让模型在 `PlaybookDraft` 中提议只读 `evidencePlan`，但它不可路由/执行，
  必须通过确定性 Validator 并且只能保存为 `CANDIDATE / NOT_ELIGIBLE`。
  OPEN_DISCOVERY 使用独立 DiscoveryPolicy，不能伪装成 approved Playbook。
- **通道一律复用平台现有 `ChannelAdapter`；普通消息走 `ChannelMessageRouter`
  pre-route，模板卡片事件才走 `CardKind`，不新建入站**（D17）。企微已有
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
