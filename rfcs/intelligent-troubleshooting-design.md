# IT 智能排障系统设计文档（on MateClaw）

> ⚠️ **现行架构见 `rfcs/intelligent-troubleshooting-architecture-v4.md`。**
> 本文不作废：§5 红线论证与 §12 源码位置索引是逐条核对过 MateClaw 源码的**证据附录**，仍然有效，
> v4 继续引用。但实现与优先级以 v4 为准，不要把本文当作独立的现行概要设计。
>
> 状态：实施中 v1 · P0—P3 与 P4 未命中路只读 Agent 工程链路已落地；P4 默认关闭，真实数据验证待 T1—T3
> 作者：MateClaw Team
> 首个落地域：CSDP 工单/客服链路
> 关联 ISSUE：待创建（动工前在上游开 issue）

## 0. 一句话与阅读指南

把故障处理从「人工翻系统 + 经验判断」升级为「告警/工单驱动 · 智能路由 · 自动取证 · 人机协同诊断 · 知识闭环」。

本设计的每一个结论都对照 MateClaw `dev` 分支的 Java 源码逐条核对过（见 §12 核对证据）。核心判断只有一句：
**排障的「确定性命中路」不能表达为一条 native MateClaw Workflow，必须做成一个确定性领域模块 `vip.mate.troubleshooting`；MateClaw 的 Agent/Workflow 只承载「未命中路」。**

---

## 1. 决定性事实：为什么不能直接用 Workflow 承载命中路

MateClaw 的 Workflow 里，每一个「干活步」最终都是调 LLM agent，没有「确定性函数步」：

| 事实 | 源码位置 | 内容 |
|---|---|---|
| work-step 默认走 agent 执行器 | `workflow/runtime/SequentialStepAdapter.java` | `execute()` → `executor.run(step, context)`，executor 是 `AgentStepExecutor`；注释 "default mode for any agent-call step" |
| agent 执行器必调 LLM | `workflow/runtime/AgentStepExecutor.java` | `run()`：resolveAgentId → renderPrompt(Pebble) → `agentInvoker.invoke(agentId, prompt, conversationId)` → parseResponse |
| 其余 StepMode 都不「干活」 | `workflow/compiler/ir/StepMode.java` | sealed：Sequential / FanOut / Collect / Conditional / AwaitApproval / DispatchChannel / WriteMemory —— 除 Sequential(=agent) 外都是编排/审批/分发/写记忆 |
| AwaitApproval 不执行任何东西 | `workflow/runtime/mode/AwaitApprovalStepAdapter.java` | 插 pause 行 → `StepResult.paused()` → 通知 approverChannels → 靠 `WorkflowResumeController`+pauseToken 恢复；执行 0 个工具 |

**推论**：若把命中路做成 Workflow，每步都会塞进一次 LLM 调用，直接违反「确定性优先、命中即零 LLM」的第一原则（D1）。因此命中路必须是确定性 Java。

---

## 2. 顶层形态：确定性领域模块

排障系统 = MateClaw-server 内的一个**确定性领域模块** `vip.mate.troubleshooting`：纯 Java 引擎 + 自有持久化 + 自有状态机 + 自有 REST/webhook。后续实现只以当前 Java 领域模块及其测试为准，不维护第二套运行时。

**落点（T4，已坐实）**：MateClaw-server 是**单 Maven 模块**，所有领域都是 `vip.mate.*` 下的平级包（acp/agent/approval/audit/auth/channel/cron/dashboard/goal/hook/kbopen/llm/memory/planning/skill/tool/trigger/wiki/workflow/workspace…），每域内部同一套 `controller/service/repository/model/event` 骨架。排障域照此新增顶层包，**不新建 Maven module、不新建独立 Spring 应用**，复用同一鉴权/持久化/Flyway/启动。

```
vip.mate.troubleshooting
├── controller      REST + webhook 接入（自有幂等）
├── service         自跑编排循环（等价 Python orchestrator）
├── engine          类型化规则引擎（sealed Criterion + Java 21 pattern match）
├── statemachine    Diagnosis 聚合 + 状态推进（拒绝跳步）
├── evidence        D8 EvidenceSourceAdapter / Router / 绑定注册表
├── agent           未命中路只读 ReAct 分诊 / 证据 ToolCallback / 服务端会话
├── knowledge       SopEntry / KnowledgeCandidate 审核生命周期 + Outbox
├── card            领域 FeishuCardKind（前缀 ts.）+ handler
├── model           record 契约层 + *Entity 持久化层
├── repository      MyBatis-Plus *Mapper
└── event           领域事件
```

---

## 3. 双路脊柱

总原则（D1）：命中即确定性、零 LLM；未命中才叫 agent，且套笼子。

### 3.1 命中路（deterministic hit-path）—— 全程领域包内，零 agent

```
webhook/REST → controller → service（自跑循环）
  → engine（6 种 criterion，纯 Java pattern-matching；含 903001 依赖的 boolean_equals）
  → evidence（D8 SourceAdapter，只读取证）
  → statemachine（Diagnosis 聚合 + 状态推进）
  → repository（自有表 + 事务 Outbox，Flyway）
```

这条路**不碰 `AgentService`、不碰 Workflow 引擎、不碰 `agentInvoker`**，一次 LLM 都不调。

### 3.2 未命中路（miss-path）—— 调 MateClaw 数字员工，套笼子

交接接缝（已落地）：`vip.mate.agent.AgentService` 新增进程内安全入口
`chatWithToolAllowlist(..., Set<String> hardAllowedTools)`。它为受限图使用独立缓存键，并把调用级白名单放在
正常 Agent 绑定、system-level tools 与 MCP 自动扩展**之后**做最终交集。

领域 service 在 `completeness==SYMPTOM / 无 error_code / 无 SOP` 时，同进程调上述受限入口（**ReAct**，
探索型分诊：看一眼证据再决定下一步查什么；不用 `execute()` 的预先规划），叫起一个**专用排障数字员工**
做受控分诊。此路径由 `mateclaw.troubleshooting.agent.enabled` 显式控制，默认关闭；开关关闭或配置不合规时
在模型调用前 fail-closed 返回 409。

笼子（均有源码支撑）：

| 笼子 | 源码机制 | 落地 |
|---|---|---|
| 专用 Agent 配置闸 | `AgentEntity` + `AgentToolBinding` | 必须 workspace-local、enabled ReAct、显式绑定唯一 enabled 模型、skills/wiki 关闭、迭代有上限；直接绑定必须且只能是 `TroubleshootingEvidenceTool` |
| 调用级硬白名单 | `AgentService.chatWithToolAllowlist` + `AgentGraphBuilder` 最终交集 | 模型实际只看到 `collect_troubleshooting_evidence`；system-level/MCP 自动扩展不能放宽；provider 原生搜索开启时 fail-closed 409 |
| 隔离上下文/模型 | `HardScopedAgentPolicy` + ReAct 受限图 | 只保留 Agent identity + 本次 prompt；不读会话历史、memory/wiki/runtime/skill/goal；禁用通用 `ToolResultStorage` spill；显式绑定唯一 enabled 模型并跳过默认模型/capability routing；provider 禁用/未配置时 409，运行时失败保守弃权，均不自动替换 |
| 输入预算与脱敏 | `TroubleshootingSecretRedactor` + `TroubleshootingAgentTriageService` | Incident、canonical EvidenceResult 全字符串字段/递归 key、路由原因先脱敏与标签转义，危险 queryId 安全重映射，再按独立字符预算确定性截断；Diagnosis 只持久化脱敏后的 fallback 上下文与证据 |
| 服务端能力会话 | `TroubleshootingEvidenceSessionRegistry` | Incident 由服务端固定，工具同时校验 conversationId + workspaceId；限制取证次数；queryId 必须安全且会话内唯一；会话外、跨 workspace 或重复 ID 调用拒绝并返回 `MISSING` |
| 平台级纵深拦截 | `tool/guard`（ToolGuard，RBAC+approval） | 对 shell/file/写类工具配 **BLOCK**；绝不给排障 Agent 用 NEEDS_APPROVAL；不能替代硬白名单 |
| 输出与证据闸 | 领域 service 结构化解析与引用核验 | 仅本次工具返回且非 `MISSING` 的 queryId 可引用；失败/空结论/低置信/无有效引用强制 `LOW + abstain`，有效 fallback 最高 `MEDIUM`，回落状态机且仍需人工确认 |

对比 Python MVP：MVP 的 `_fallback` 是纯 abstain 占位（缺口 G1）；MateClaw 已具备只读数字员工的
工程真身。**能力补上，红线不松；完成代码不等于已上线，专用 Agent 配置与实机演练前保持默认关闭。**

---

## 4. 六层架构 → MateClaw 映射

| 层 | 职责 | 承载物 | 状态 |
|---|---|---|---|
| ① 接入 | 告警/工单/人工多入口汇聚 | 领域自有 controller（webhook+REST），自带幂等（5 分钟桶） | 自建；**不复用 `trigger`**（T1） |
| ② 路由 | `(system,error_code)` 命中判定 | 领域 service 内查（domain 表 + `SopKeyCollisionError` fail-closed） | 纯领域逻辑 |
| ③ 编排 | 命中=确定性 / 未命中=Agent | 命中→领域 service；未命中→`AgentService.chatWithToolAllowlist()` | §3 |
| ④ 工具/取证 | 只读取证、多平台 | `evidence` 包 D8 adapter；`TroubleshootingEvidenceTool` 薄包装同一 Router | §6 一份能力两调用方 |
| ⑤ 交付 | IM 卡片 + 故障上下文 Web 台 | `channel`（飞书/企微 card kind）+ 领域 Web 台 | §5 双确认 |
| ⑥ 反馈闭环 | 知识沉淀、经验固化 | 领域表（结构化权威）+ `wiki`（叙事）+ `memory/lifecycle` + `skill/lessons` | §8 |

**① 为什么不寄生 Trigger（T1，已坐实）**：`TriggerEntity.targetType` 只有 `agent`/`workflow` 两种取值（均带 LLM），且 `TriggerDispatcher` 在 v0 只接了 Workflow（第 54 行 "target_type not supported in v0; skipping fire"）。Trigger **没有「分发到确定性领域 bean」这条路**，接入走 Trigger 会被拖回「每步 LLM」的 Workflow。可当库借用 `TriggerRateLimiter`/`TriggerEventEnvelope`/`BotSelfFilter`，但不走 `TriggerEventIngestService`→dispatcher。

---

## 5. 交付与人工确认（红线核心，安全命门）

### 5.1 大前提（已坐实）

MateClaw 的 `vip.mate.approval` 域 = ToolGuard 的 **approve-then-execute** 闸门：`ApprovalService.createPending` 存 `toolCallPayload`，`ResolveOutcome` 注释明写批准后要「reach the consumed payload (tool call JSON) for **replay**」——**批准 = 回放执行被扣住的工具**。平台里不存在「批准了但不执行」这种语义。

### 5.2 四条红线

| # | 红线 | 源码依据 | 落地 |
|---|---|---|---|
| R1 | 生产写工具一个都不注册 | ToolGuard NEEDS_APPROVAL → replay → 执行 | agent 只绑只读取证工具；写执行器根本不进工具注册表 |
| R2 | 人工确认 ≠ ToolGuard 批准 | ToolGuard 批准会执行；我们要的确认只推进状态 | 确认走领域状态机 + 领域自有 Channel 卡片，回调打领域 REST，只推进状态/登记，执行 0 个工具（≡ Python MVP `/execute` 恒 409、`approve_action` 只记「系统未执行」） |
| R3 | 写操作永远外部人工 + 结果登记 | —— | 写恢复 = `human_contact` 转派给有权限的人 → 人在 MateClaw 外执行 → 回来 `record-outcome` 登记处置 + 恢复验证；平台从不连生产写执行器 |
| R4 | 未命中路 Agent 锁死只读 | 专用绑定校验 + 调用级硬白名单 + 服务端会话；ToolGuard BLOCK 纵深防御 | 见 §3 |

**枢纽洞察**：平台的 `approval` 是「先授权、后执行工具」；排障的写要的是「永不自动执行、只把人叫来登记结果」。二者语义相反，**所以生产写绝不能挂进 ToolGuard/approval**——否则「人工确认」变成「人点一下就真执行」，红线当场破。确认必须停在领域状态机层，批准只让状态 `PENDING_APPROVAL → APPROVED_NOT_EXECUTED`，永不碰执行器。

### 5.3 IM + Web 双确认（T2，已坐实）

`FeishuCardDispatcher` 是通用路由分发器：卡片按 `FeishuCardKind{name, actionPrefix}` 注册，按钮点击带 `action.value.action`，按前缀匹配选 handler（前缀冲突注册时抛错）；`FeishuCardHandler` 可插拔；`registerKinds()` 注释 "Add lines here as new card kinds land"——ToolGuard 只是众多卡片种类之一。

| 路径 | 承载 | 回调动作 |
|---|---|---|
| IM 确认 | 领域自有 `FeishuCardKind`（前缀 `ts.`）+ handler | 打领域 REST `/troubleshooting/.../{confirm,approve,record-outcome}`，只推进状态机 |
| Web 确认 | 领域故障工作台 + 领域 REST | 同一批领域端点 |

成本：卡片回调是逐 IM 平台机制（飞书 `FeishuCardDispatcher`，企微平行 `wecom/cards`）。建议 IM 确认先落一个平台，其余平台先只做通知、确认回落 Web 台。

---

## 6. 证据源开放适配（D8）

原则不变：SOP 存平台无关意图 `EvidenceRequest`；`EvidenceSourceRouter` 按 `(system, signal_kind)` 选适配器；每平台一个 `EvidenceSourceAdapter` 负责「造查询→执行→归一到 canonical `observed`」；anomaly_criteria 规则引擎跑在归一后字段上，跨平台零改。

**一套路由能力，两个调用方（已落地）**：

```
        EvidenceSourceAdapter (Spring @Component bean)
        guance / zabbix / prometheus / loki / recorded / fixture
         │                                    │
   命中路：领域 service 直接 Java 调用       未命中路：TroubleshootingEvidenceTool
   router.collect(request, incident)         collect_troubleshooting_evidence(...)
   经 Router，零 LLM                         → 同一 Router + 服务端活动会话
                                             → 调用级硬白名单最终交集
                                             → ToolGuard BLOCK 作纵深防御
```

实现选择：`TroubleshootingEvidenceTool` 是很薄的 `@Component` + `@Tool` 包装，只负责参数合同、活动会话校验与
调用既有 `EvidenceSourceRouter`；它没有第二套取证逻辑。V173 把它注册为内建工具，但会话外调用只会返回
canonical `MISSING`。调用级硬白名单决定模型实际可见面，工具注册表本身不是安全边界。

好处：① 零重复取证代码；② 写类方法不加 `@Tool`、天然不进工具表（呼应 R1）；③ D8「每平台各自毕业」照旧（`verification_status` 按 per-platform per-binding 记）。加 Zabbix = 加一个 bean + 一份 binding yaml，不动任何 SOP、不动 anomaly_criteria、不动这两个调用方。

---

## 7. 契约与规则引擎 → Java

MateClaw 惯例（已坐实）：持久化 = **MyBatis-Plus**（`*Entity` + `@TableId(ASSIGN_ID)` + 逻辑删除 `Integer deleted` + `*Mapper`）；**Flyway 多方言** `db/migration/{mysql,h2,kingbase}/V{n}__desc.sql`（现有 480 个）；**record + sealed interface** 是既用惯例（`StepMode`）。

| Python MVP 物件 | Java 落法 |
|---|---|
| `IncidentContext`/`SopEntry`/`Diagnosis`/`EvidenceRequest`/`EvidenceResult`/`TransferContextSnapshot` | Java `record`（不可变契约层） |
| 类型化规则引擎（`numeric_gte`/`missing_or_lte`/`ratio_of_sum_gt`/`multiple_gt`/`contains_and_in`） | `sealed interface Criterion permits …` + Java 21 pattern-matching `switch` |
| SQLite 聚合 + 事务 Outbox | MyBatis-Plus `*Entity` + `*Mapper` + outbox 表 + poller |
| 状态机（拒绝跳步） | 领域 `service`（不进 workflow 引擎，见 §1） |

**分层纪律**：契约层用 record（给规则引擎和测试，可脱库单测），持久化层用 `*Entity`，中间加映射；规则引擎只吃 record，绝不直接读 Entity。这是 Python MVP 38 个测试可测性的来源，翻 Java 要守住。

成本：① 新增表/内建工具要在 `mysql/h2/kingbase` 三个方言目录各写一份迁移；P0 领域表为
`V172__troubleshooting_domain.sql`，P4 只读工具注册为 `V173__register_troubleshooting_evidence_tool.sql`；
② `Diagnosis.contract_version` 作跨版本兼容闸门（D2），P4 从 1.3 升至 1.4，新增 `evidenceCitations`，
仍兼容读取无该字段的 1.3 历史载荷。

---

## 8. 两层知识（D1 边界 + D2 生命周期）

查证：`kbopen` 是 KB 开放 API（无 candidate→approved 审核队列语义）；`wiki` 只有 `knowledgeLayer`/`metadataValidationStatus`/stale 标记，且其结构化抽取是 **LLM 管道 + 轻量必填检查**。**MateClaw 没有现成的「确定性 SOP 审核生命周期」可复用**。

| 知识层 | 内容 | 落点 | 权威性/生命周期 |
|---|---|---|---|
| 确定性层 | 路由键、anomaly_criteria、actions、owner 归属 | **领域表** | **唯一决策权威**；D2 `candidate→approved→deprecated` 领域自建 |
| 知识面层 | 方法论、恢复叙事、根因、runbook | **Wiki**（可挂结构化 metadata 供检索） | 供人 + 未命中路 agent 消费；**永不做命中路权威** |

**D2 必须领域自建的推论**：SOP 规则是命中路零-LLM 判定的权威；若审核生命周期托给 LLM 填充的 wiki，等于让 LLM 管道决定「哪条恢复规则可用」，破 D1。所以状态、审核队列、专家评审入口都必须和规则本体一起待在领域表。这套 Python MVP 已建好（`KnowledgeCandidate` + status + 事务 Outbox + 只进审核队列不覆盖 SOP），直译即可。

**与 Wiki 的接缝**：SOP 由 `candidate` 升 `approved` 时，可顺带派生一篇 Wiki 恢复叙事（`sourceEntries` 指回 SOP id）——**单向派生（领域表→Wiki），Wiki 永不回写决策权威**。

---

## 9. 身份与鉴权（复用 MateClaw，解决 G3）

源码事实：`config/JwtAuthFilter.java`（每请求 JWT，可信 principal）；`auth/pat/*`（PAT，供 webhook/程序化鉴权）；`auth/sso/*`（SSO 现成）；`workspace/core/security/Capability.java`（能力常量）；`workspace/core/security/RoleCapabilities.java`（权威 role→capability，`viewer/member/admin/owner`）。

Python MVP 的 `actor` 是请求体标签、不可信（缺口 G3，只能 loopback 兜底）；接进 MateClaw，领域 controller 坐在 `JwtAuthFilter` 后即得可信 principal + workspace + role，**G3 消失**。

新增排障域 capability（挂进 `RoleCapabilities`）：

| capability | 授予角色 | 门控 |
|---|---|---|
| `view:troubleshooting` | viewer+ | 看工作台、证据、诊断 |
| `operate:troubleshooting` | member+ | 确认诊断、结构化转派、推进状态机的「批准」（R2：只推进不执行） |
| `manage:troubleshooting` | admin+ | 编辑 SOP、审核知识候选 `candidate→approved`（D6：专家才可评审） |

接缝：① webhook 接入用 PAT 鉴权（堵伪造告警灌入）；② R2 的「批准」按 `operate:troubleshooting` 门控，SOP 审核按 `manage:troubleshooting`——把 D6「专家才可评审」落到 RBAC 而非约定。

---

## 10. 放权阶梯（D5，写永远人工）

阶梯：S0 影子（跑但不发）→ S1 建议（发只读卡片）→ S2 只读自动取证（ToolGuard ALLOW 只读）→ S3 半自动（更细 RBAC/ToolGuard）。每格逐系统毕业。

| 格 | 行为 | 承载机制（已坐实） |
|---|---|---|
| S0 影子 | 命中路照跑、诊断照生成，不发卡片/不转派 | 状态机 + 交付层开关关闭；结果入库供回归比对 |
| S1 建议 | 发只读诊断卡片给人 | 领域 `FeishuCardKind`（`ts.`）通知卡，无写按钮 |
| S2 只读自动取证 | agent 自主跑只读取证 | `AgentToolBinding` 只绑 `collect_*` + ToolGuard ALLOW 只读 |
| S3 半自动 | 更细分诊自主度，写永远人工 | capability 细分 + ToolGuard 逐工具策略 |

**门闸（已坐实）**：`system/featureflag`（`FeatureFlagService.isEnabled`，**fail-closed**：未知 flag→false；支持 whitelist + 百分比灰度）。精确分工：

- **逐系统档位**（CSDP 在 S2、系统 X 在 S0）= 领域自有 per-system 配置表（列 `delegation_stage`）——业务状态，不塞进通用 flag whitelist（其键是 kbId/userId，非「系统」）。
- **FeatureFlag 当全局总闸/kill-switch**（`ts.auto_evidence.enabled`、`ts.agent_dispatch.enabled`）：fail-closed，出事一键全域降级；灰度拨盘可先对 5% 流量放 S2。

**数据驱动毕业**：S0 影子跑出的诊断入库即历史回归集数据源，比对「系统若自动处置 vs 人工实际处置」一致率，达标才把 `delegation_stage` 往上推一格。

---

## 11. 决策与待坐实点核对结论

**D1–D8 均已锁定并完成源码可行性核对，但不是全部实现**：D1 命中路零 LLM 领域引擎已落（§1/§3）·
D2 领域自建审核生命周期已落（§8）· D3 Web 台与生命周期 REST 已落，IM 出站仍待产品路由（§5）·
D4 命中路领域 service 与未命中路只读 ToolCallback 均已落，运行启用待验收（§2/§6）·
D5 FeatureFlag×档位 + 影子回归待 P5（§10）· D6 capability 门控设计已映射（§9）·
D7 同 JAR 内领域包、逻辑不寄生 Workflow 已落（§2）· D8 Router、归一及命中/未命中两个调用方已落，
真实数据验证待 T2（§6）。

**T1–T4 全部坐实**：T1 Trigger 无确定性分发路→接入自建 · T2 `FeishuCardDispatcher` 可插拔→注册 `ts.` card kind · T3 Wiki 结构化是 LLM 管道→只做知识面 · T4 单模块内新增 `vip.mate.troubleshooting` 兄弟包。

---

## 12. 核对证据（源码位置索引）

| 结论 | 源码位置 |
|---|---|
| Workflow 每步调 LLM | `workflow/runtime/{SequentialStepAdapter,AgentStepExecutor}.java`、`workflow/compiler/ir/StepMode.java`、`workflow/runtime/mode/AwaitApprovalStepAdapter.java` |
| Agent 可编程入口与调用级硬白名单 | `agent/AgentService.java`（`chatWithToolAllowlist`）、`agent/AgentGraphBuilder.java` |
| agent 工具白名单 | `agent/binding/model/AgentToolBinding.java` |
| ToolGuard 批准即执行 | `approval/ApprovalService.java`、`approval/ResolveOutcome.java`、`approval/model/ToolApprovalEntity.java` |
| Trigger 只分发 workflow/agent | `trigger/model/TriggerEntity.java`、`trigger/dispatch/TriggerDispatcher.java` |
| 卡片可插拔分发 | `channel/feishu/cards/{FeishuCardDispatcher,FeishuCardHandler,FeishuCardKind}.java` |
| Wiki 结构化是 LLM 管道 | `wiki/model/{WikiPageEntity,WikiPageTypeProfileEntity}.java`、`wiki/model/WikiTransformationEntity.java` |
| 内建工具 = @Component+@Tool | `tool/builtin/BrowserUseTool.java` |
| 未命中只读取证与输出闸 | `troubleshooting/agent/{TroubleshootingAgentTriageService,TroubleshootingEvidenceTool,TroubleshootingEvidenceSessionRegistry}.java` |
| 身份/RBAC | `config/JwtAuthFilter.java`、`auth/{pat,sso}/*`、`workspace/core/security/{Capability,RoleCapabilities}.java` |
| 放权门闸 | `system/featureflag/{FeatureFlagService,FeatureFlagEntity,FlagContext}.java` |
| 持久化惯例 | `pom.xml`（mybatis-plus/flyway）、`db/migration/{mysql,h2,kingbase}/` |

---

## 13. 整体实施清单

> 阶段划分遵循「先夯确定性命中路与安全边界，再接未命中路与放权」。每项标注依赖的决策/红线。

### P0 · 领域骨架与契约（无外部依赖，可先跑通端到端合同）

- [x] 新增 `vip.mate.troubleshooting` 包骨架（controller/service/engine/statemachine/evidence/knowledge/card/model/repository/event）。
- [x] 契约层 record：`IncidentContext`/`SopEntry`/`Diagnosis`/`EvidenceRequest`/`EvidenceResult`/`TransferContextSnapshot`（含 `contract_version`）。
- [x] 规则引擎：`sealed interface Criterion` + 6 实现 + Java 21 pattern-match 求值；含 Python MVP 已验证的
  `boolean_equals`，当前 P0/Persistence 合同共 33 个 Java 定向测试（其余 API/竖切合同随 P1/P2 补齐）。
- [x] 确定性命中编排：`IncidentContext + SopEntry + EvidenceResult` 依次经过 engine→state machine
  初始化→repository，生产服务中零 LLM。
- [x] 状态机领域 service：拒绝跳步（诊断确认→审批但系统未执行→外部结果→恢复验证）；
  领域内 `executeAction` 兼容缝恒 409，真实 `/execute` REST 路由留待 P2。
- [x] 持久化：`*Entity` + `*Mapper` + `V172__troubleshooting_domain.sql`（mysql/h2/kingbase 三份）；
  知识发布 outbox 表 + lease/retry poller，全程携带 `workspace_id`。
- [x] 幂等：`(system, error_code, service, 5 分钟桶)`，rehearsal 排除，无 error_code 不去重。

**P0 验证（2026-07-25）**：`mvn -pl mateclaw-server -am -Dtest='vip.mate.troubleshooting.**.*Test'
-Dsurefire.failIfNoSpecifiedTests=false test`，33 tests passed；H2 真实执行 V172 并校验 3 张表及唯一索引。

### P1 · 接入与身份（打通安全入口）

- [x] 接入 controller：REST 报障入口 + 自有幂等；**不走 Trigger**。
- [x] 告警源复用 `auth/pat` 的受限 PAT 鉴权，不新增旁路身份。
- [x] 新增 3 个 capability（`view/operate/manage:troubleshooting`）挂进 `RoleCapabilities`；领域端点逐个门控。

### P2 · 交付与人工确认（红线落地）

- [x] 领域故障工作台（Web）+ 领域 REST（`confirm`/`transfer`/`approve`/`record-outcome`/`close`，
  以及始终返回 409 的 `/execute`）。
- [x] SOP 管理台 `/troubleshooting/sops`：只接受 candidate 注册、按需加载完整合同，生命周期仅允许
  `candidate→approved→deprecated`，由 `manage:troubleshooting` 门控且不提供执行入口。
- [x] 注册领域 `FeishuCardKind`（前缀 `ts.`）+ `FeishuCardHandler`，按钮回调只打领域端点、推进状态机（**验证：执行 0 个工具**）；出站渲染仍待产品路由决策。
- [x] R1/R2/R3 回归测试：写动作恒 `PENDING→APPROVED_NOT_EXECUTED`、`record-outcome` 登记外部处置、生产写执行器不在工具表。

### P3 · 证据源开放适配（D8）

- [x] `EvidenceSourceAdapter` 接口 + `EvidenceSourceRouter`（按 system+signal 选源，fail-closed 降级）。
- [x] 归一词汇表（先只定 903001 用到的 `log_count`/`metric`/`trace`）+ 应用配置绑定注册表。
- [x] `GuanceEvidenceAdapter` 首实现（官方 query-data API 形状 + canonical 归一）；内网字段/阈值待 T2。
- [x] `TroubleshootingEvidenceTool` 作为薄 ToolCallback 包装复用同一个 Router，不另写取证代码。
- [x] `RecordedReplayAdapter` + 脱敏 903001 三信号样本 + 合同回归测试。
- [x] `GET /api/v1/troubleshooting/evidence/sources` 汇总源级 `health`。
- [ ] 真实联调后补 per-binding `verification_status`，并按需汇入全局 `/readyz`。

### P4 · 未命中路数字员工（补 G1）

- [x] 代码校验专用 Agent 必须 workspace-local ReAct、显式绑定唯一 enabled 模型、skills/wiki 关闭、迭代有上限，
  直接绑定且只能绑定 `TroubleshootingEvidenceTool`；V173 注册该只读工具。
- [x] `AgentService.chatWithToolAllowlist()` + `AgentGraphBuilder` 最终交集锁死实际工具面，并隔离受限图缓存。
- [x] `HardScopedAgentPolicy` 隔离 ambient context、禁用 provider fallback；受限调用跳过默认模型与 capability
  routing，模型歧义、provider 禁用/未配置或原生搜索开启均在模型请求前 409；运行时失败保守弃权。
- [x] 硬作用域在构图/执行前清空请求级 `ThinkingLevelHolder`、结束后恢复，受限图/推理节点同时忽略环境覆盖；
  工具原始参数仅供 Guard 和 callback 使用，不复制到 event/SSE/log/audit/approval，`NEEDS_APPROVAL` 直接拦截。
- [x] 初始未受信上下文经脱敏、转义并受独立字符预算约束；禁用受限图的通用 `ToolResultStorage`
  原始结果 spill；已有/新采集的 canonical EvidenceResult 全字符串字段/递归 key 脱敏且危险 queryId
  安全重映射后，再进入模型并随 Diagnosis 持久化。
- [x] 三类 route miss 接入 Agent；产出回落状态机 + 强制证据引用 + 低置信/异常 abstain；
  fallback 置信度最高 `MEDIUM`、动作恒空。
- [x] 服务端活动会话固定 Incident 上下文、校验 workspaceId、限制取证次数；会话外/跨 workspace 工具调用 fail-closed。
- [ ] operator 创建并绑定专用 Agent，配置 ToolGuard 对 shell/file/写类 **BLOCK**，设置 Agent ID。
- [ ] 保持默认开关关闭直到真实 miss-path 演练通过；验收和回滚见 `docs/intelligent-troubleshooting/agent-miss-path-runbook.md`。

### P5 · 放权阶梯与知识运营（D5/D6）

- [ ] per-system 配置表（`delegation_stage`）+ FeatureFlag 全局闸（`ts.auto_evidence.enabled`/`ts.agent_dispatch.enabled`，fail-closed）。
- [ ] S0 影子：跑但不发，结果入库 → 历史回归集。
- [ ] 回归比对「自动 vs 人工」一致率，达标才升档。
- [ ] 知识候选审核队列 + `manage:troubleshooting` 门控；`approved` 时单向派生 Wiki 叙事。

### 阻塞项（数据侧，与代码并行）

- [ ] 清 L0 数据 blocker：3 个路由键一码多义冲突（101014/101034/101040）+ 103 处疑似字符丢失（回源表恢复）。
- [ ] 内网核实 903001 的 `evidence_dql`/`anomaly_criteria`（观测云 `*.prd.sangfor.com` 需内网联调）。

### 全程红线（每个 PR 自检）

1. 生产写工具永不注册；未命中路 agent 只绑只读。
2. 人工确认只推进状态机、执行 0 个工具（≠ ToolGuard 批准）。
3. 写操作永远外部人工 + 结果登记；平台不连生产写执行器。
4. 命中路零 LLM；规则引擎只吃 record、可脱库单测。

---

## 14. 实施战略（第一性原理 × 矛盾分析 · 2026-07 刷新）

### 14.1 当前阶段主要矛盾

- **⭐ 主要矛盾**：[P0—P4 工程链路已通且可测] vs [尚未在真实数据上跑过一次]。规则、状态机、
  工作台、证据适配与只读 Agent 笼子均已落 Java；当前系统性质仍由「知识与数据尚未经过实践检验」规定。
- **性质**：非对抗性，但已从工程演进转为实践检验；主战场是 T1 路由歧义裁决、T2 内网观测云核实、
  T3 审核入库，不再靠继续堆功能解决。
- **⚠️ 矛盾转化监控**：T1—T3 完成后，[知识质量 vs 自动化范围] 上升为主要矛盾——它是全过程的根本
  天花板（只读可自动化 30/146≈21%），因此本设计从不承诺「上线即全自动」。

### 14.2 兵力部署

| 方向 | 内容 | 原则 |
|---|---|---|
| **主攻** | T1 清路由歧义 → T2 核实 903001 真实证据 → T3 审核入库；P3 代码只作为承载底座 | 以真实数据验收，不用 fixture 冒充完成 |
| **钳制/并行** | SOP 管理与 P4 代码已完成；维护运行手册、脱敏回归资产，准备专用 Agent 配置 | 不越过真实验证主线 |
| **后续梯队** | P4 运行配置/实机演练 → P5 放权阶梯 | 梯次投入，前一梯队验收后进场 |
| **底线（贯穿）** | §13 四条红线每 PR 自检 | 不随阶段转移 |

### 14.3 长期战线映射（持久战，与 §10 阶梯咬合）

战略防御 = S0 影子（纪律为王、积累回归数据）→ 战略相持 = S1–S2（建议卡 + 只读自动取证，知识候选闭环
造血，146 码逐批毕业）→ 战略反攻 = S3（半自动 + 多系统 + 平台化）。**写操作永远人工，是不随阶段转移的底线。**
知识运营走群众路线：候选从一线关闭沉淀中来（§8 Outbox），专家审核毕业后回到一线工作台/卡片中去（§9 门控）；
影子回归一致率充当客观的批评者，毕业由数据驱动而非拍脑袋（§10）。
