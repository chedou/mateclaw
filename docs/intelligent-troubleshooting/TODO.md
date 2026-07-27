# 待办清单 —— IT 智能排障系统

> 面向接手的 AI / 工程师。**每条都写清了「为什么这么做」和「做到什么算完」**，
> 因为这个项目里大部分坑不是不会写代码，而是不知道某个看似合理的做法会破坏哪条red line。
>
> 开工前必读：`CLAUDE.md` → `HANDOFF.md`（决策 D1–D8、四条红线、矛盾分析）→
> **`meeting-change-plan.md`（会议驱动的变更方案 C1–C8，决定了下一阶段做什么）** →
> `rfcs/intelligent-troubleshooting-design.md`（架构 + 源码索引）。
>
> 当前状态：P0 内核 + P1 接入身份 + P2 交付闭环 + P3 命中路证据适配底座 + P4 未命中路只读 Agent
> 工程链路 + 推导投影 + SOP 管理 API/Vue 均已完成；P4 默认关闭、待专用 Agent 配置与实机演练。
> 定向回归与应用上下文启动测试通过。分支 `claude/session-moz2pc`。
>
> **2026-07 会议后的方向调整**：主攻方向从"接真实数据补 SOP"前移到
> **「从观测云日志自动生成 SOP」**（T11/T12），因为那才是我们相对研发团队的差异化。
> 详见 `meeting-change-plan.md`；新增战线见下面第三·五节 T11–T18。

---

## 零、动手前请先理解的四条红线

违反其中任何一条，都会毁掉这套系统存在的理由。它们不是风格偏好：

1. **命中路零 LLM。** `(system, error_code)` 命中已知故障模式时，全程走确定性 Java，
   一次模型调用都不能有。**因此接入不能走 Trigger 引擎**——它只能分发给 agent 或 workflow，
   而 workflow 的每个干活步都调 LLM。
2. **生产写工具一个都不注册。** 平台的 ToolGuard「批准」语义是**回放执行**被扣住的工具调用；
   排障要的是**永不自动执行**。语义相反，所以生产写绝不能挂进 ToolGuard/approval。
3. **人工确认只推进领域状态机，执行 0 个工具。** 批准把动作从 `PENDING` 推到
   `APPROVED_NOT_EXECUTED` 就结束了，真正的变更由有权限的人在 MateClaw 之外执行，
   回来调 `record-outcome` 登记。`Diagnosis` 构造器里 `writeExecutionEnabled=true` 直接抛异常——
   这条红线在类型系统层面不可表达，别试图绕。
4. **未命中路 Agent 锁死只读**（专用直接绑定校验 + 调用级硬工具白名单 + 服务端取证会话；
   ToolGuard BLOCK 写/shell/file 做纵深防御）。

**还有一条贯穿全项目的纪律：宁可承认做不到，也不要假装做到了。**
`fixtureMode` 恒 true、未命中路返回 409、推导 `faithful=false`、卡片未绑定身份即拒绝——
这些"示弱"的设计都是刻意的，不要为了让 demo 好看而抹掉它们。

---

## 一、主攻：接真实数据（**当前主要矛盾**）

代码闭环已通且可测，但**从未在真实数据上跑过一次**。SOP 库是 fixture、观测云未联调。
再往下堆功能都是在未验证的地基上加层。这三件事都需要**人的介入**，AI 单独做不了：

### T1 · 清理 L0 三个路由键冲突 🟡 **降级**：只阻塞错误码类 SOP
- **问题**：`101014` / `101034` / `101040` 在源表里一码对应多个业务上下文，
  与 D1「`(system,error_code)` 唯一路由」的前提冲突。
- **为什么必须先解决**：路由键冲突时 `register` 会 fail-closed 抛 409。这是**故意的**——
  让一码多义暴露出来，而不是让后写的 SOP 静默覆盖先写的。绕过它就等于把知识库的歧义
  带进了确定性判定。
- **怎么做**：需 owner 裁决拆分口径（拆成不同 service？还是加二级判别字段？），
  详见 `l0/quality_report.md`。**这是人的决策，不是技术问题。**
- **完成标准**：三个冲突码各自有明确唯一的路由键，清洗器不再报 blocker。
- **会议后的降级说明**：场景类 SOP（慢接口 / 系统挂）根本不带错误码，走 `scenario:` 路由键，
  不受这三个冲突影响。所以 T1 **不再阻塞 T11/T12 的主攻方向**，只阻塞错误码类 SOP 的批量入库。

### T2 · 内网核实观测云字段与阈值 🔴 阻塞项
- **问题**：`l0/activated/903001.md` 里的 `evidence_dql` / `anomaly_criteria` 标着 `«待核实»`。
  DQL 的数据源名、字段名、阈值都没在真实观测云上验证过。
- **为什么重要**：判据算错不会报错，只会给出**错误但看起来合理的根因**——
  比不给结论危险得多。
- **怎么做**：需内网窗口（`*.prd.sangfor.com`，DF-API-KEY 鉴权）。
- **完成标准**：903001 的每个 `EvidenceRequest` 都能在真实观测云取到数，
  字段名与 `AnomalyCriterion` 引用的一致，阈值经过真实数据验证。
- **会议后扩大范围（优先级最高的一条）**：拿到内网窗口时，**第一件事是验证 PS ID 是否全链路贯通**
  ——同一次请求跨服务的日志能否靠 PS ID 串起来。T11 整条流水线以此为前提，不通就得换方案。
  其次再验 C2 新增的 signalKind（`log_search` / `log_trace_bundle` / `interface_latency_rank` /
  `blast_radius_probe`）各自的 DQL 与字段。

### T3 · 真实 SOP 入库并放开 fixtureMode
- **前置**：T1、T2 完成。
- **入库通道已经就绪**（本轮刚做完）：
  ```
  POST /api/v1/troubleshooting/sops              注册（需 manage:troubleshooting）
  GET  /api/v1/troubleshooting/sops              浏览注册表
  GET  /api/v1/troubleshooting/sops/{sys}/{code} 读取完整 SOP
  POST /api/v1/troubleshooting/sops/{sys}/{code}/status  promote/retire
  ```
  管理员也可从 Vue 路由 `/troubleshooting/sops` 浏览、注册 candidate，并显式推进到
  `approved/deprecated`；页面无诊断执行入口。
  从 `l0/sop_kb.json`（146 码）导入时**必须以 `candidate` 注册**，逐条审核后再
  `→approved`。状态流转是单向的（`candidate→approved→deprecated`），不能回退。
- **当前版本边界（不能假装已解决）**：V172 对 `(workspace_id, route_key)` 做唯一约束，
  deprecated 记录仍占用 routeKey；因此 approve 错了可以 deprecate 留痕，但**当前还不能为同一路由
  注册替代版本**。上线前需设计“历史版本 + 唯一当前版本”的数据模型与按 sopId 查看历史的 API，
  不能靠覆盖旧行或逻辑删除抹掉审计轨迹。
- **放开 `fixtureMode`**：现在命中/未命中两路共用
  `TroubleshootingSafetyPolicy.EVIDENCE_IS_FIXTURE=true`。
  T4 工程链路已经落地，但**只有 T2 的真实字段/阈值核实与 T3 审核入库完成后才能改**；
  “API 可达”不等于“证据语义可信”。

---

## 二、后端功能梯队

### T4 · P3：D8 证据源适配器（观测云首个） 🟡 命中路底座完成，内网验证待 T2
- **为什么**：此前证据只能由调用方传入；现在调用方证据仍优先，缺失的 SOP 请求会由平台只读补齐。
- **架构已定**（RFC §6）：`EvidenceSourceAdapter` 接口 → 每平台一个实现 → `EvidenceSourceRouter`
  按 `(system, signal_kind)` 选源 → 归一到 canonical `observed` 字段。
- **关键设计（别做错）**：**一套路由能力两个调用方**——命中路由领域 service 直接 Java 调用（零 LLM），
  未命中路由 `TroubleshootingEvidenceTool` 这一层薄包装调用同一个 `EvidenceSourceRouter`，并暴露成
  唯一只读 ToolCallback 给 Agent 用。
  **不要写两套取证代码**，那会导致两条路看到不同的世界。
- **fail-closed**：任何异常/超时 → `EvidenceResult(status=MISSING)`，不抛 500。
  上游会正确地把它当成"判据无法求值"（≠ 已排除）。
- **已完成（2026-07-27）**：`EvidenceSourceAdapter` + `EvidenceSourceRouter`；
  `GuanceEvidenceAdapter` 按官方 query-data API 发请求并归一 `series.columns/values`；
  `RecordedReplayAdapter` + 脱敏 903001 三信号样本；主备降级、模板注入防护、配置装配、入口补证与
  `GET /api/v1/troubleshooting/evidence/sources` 均有测试。默认两个数据源都关闭，源状态不暴露凭据。
- **第二调用方已完成（P4）**：`TroubleshootingEvidenceTool` 复用同一 Router，并通过服务端会话固定
  Incident/Workspace 上下文、限制每次取证数量；无活动会话的调用返回 canonical `MISSING`，不能越界取证。
- **尚未完成的验收**：没有内网窗口，故尚未证明 `GuanceEvidenceAdapter` 对 903001
  measurement/字段/阈值能取到真实数据；per-binding verification 与全局 `/readyz` 汇总也留待 T2。
  运行说明见 `evidence-adapter-runbook.md`。

### T5 · P4：未命中路 ReAct Agent（补旧缺口 G1） 🟡 工程完成，启用与实机演练待办
- **已完成（2026-07-27）**：无 error code、`SYMPTOM`、无 SOP 三种 miss 均接入
  `TroubleshootingAgentTriageService`；领域服务调用新增的
  `AgentService.chatWithToolAllowlist()`，输出经结构化解析、证据引用核验后回落领域状态机。
  `LLM_FALLBACK` 永远没有 `recommendedActions`/`pendingWrites`，低置信、空摘要/假设、缺证据、
  伪造引用、解析失败或 Agent 异常都会强制 `LOW + abstain`；可验证建议也最高校准为 `MEDIUM`。
- **为什么不能只靠 `AgentToolBinding`**：平台的普通有效绑定会为兼容性自动扩入 system-level tools，
  某些配置还会扩入 MCP 工具。因此 P4 在正常绑定展开**之后**再做调用级最终交集，且受限图使用独立缓存键；
  任何自动扩展都不能把 shell/file/写工具重新暴露给本次模型调用。
- **笼子已固化**：启动前校验 Agent 为当前 workspace 的 enabled ReAct、显式绑定唯一 enabled 模型、
  skills/wiki 关闭、迭代有上限，
  直接绑定必须且只能是 `TroubleshootingEvidenceTool`；运行时硬白名单只允许
  `collect_troubleshooting_evidence`；受限图不读会话历史/memory/wiki/runtime/skill/goal 上下文、不启用模型故障转移，
  禁用通用 `ToolResultStorage` 原始结果 spill，跳过全局默认模型与 capability primary routing；模型歧义、
  provider 禁用/未配置或原生搜索开启均 409。运行时调用失败保守弃权，不自动选择备用 provider。
  已有/新采集的 canonical EvidenceResult 全部字符串字段与递归 key 在进入模型和 Diagnosis 前统一脱敏，
  危险 queryId 安全重映射，脱敏结果仍随 Diagnosis 持久化。初始未受信上下文经脱敏、转义并按独立字符预算
  确定性截断；硬作用域清空/恢复请求级 ThinkingLevel 且受限图忽略环境覆盖；原始工具参数不进入
  event/SSE/log/audit/approval，硬作用域不允许进入 `NEEDS_APPROVAL` 流程；
  工具按 conversationId + workspaceId 校验服务端活动会话，并受取证次数上限约束；queryId 必须安全且会话内唯一，
  重复调用不能覆盖已引用证据。
- **仍未完成的上线动作**：默认 `MATECLAW_TROUBLESHOOTING_AGENT_ENABLED=false`。需要 operator 按
  `agent-miss-path-runbook.md` 创建专用 Agent、配置 ToolGuard BLOCK 纵深规则、设置 Agent ID，最后才打开开关并做
  真实 miss-path 演练。在此之前生产行为仍是 fail-closed 409，不能宣称“未命中智能分诊已上线”。
- **完成标准（代码已满足）**：命中路零 Agent；miss-path 只有一个只读工具；有效证据引用才能形成待人工确认结论；
  各类失败均持久化或返回可解释的保守结果且不产生动作。**运行验收**仍以 runbook 的配置与实机演练为准。

### T6 · 知识候选审核工作流（D2 闭环最后一环）
- **现状**：候选在关闭时入 Outbox，**可以看但不能审**——
  `GET /api/v1/troubleshooting/sops/candidates` 已可列出（本轮刚做）。
- **未做的原因（重要）**：Outbox 那个 status 字段是**发布**语义（PENDING/PROCESSING/PUBLISHED/FAILED，
  指"有没有交给 sink"），**不是审核**语义（"专家有没有认可"）。两者长得像，
  **混用会让一次投递重试伪装成一次审核通过**。所以候选审核需要自己的状态，是个真实的设计增量。
- **要做的设计决策**：候选审核状态存哪（新列？新表？）、审核通过后如何转成 SOP 编辑
  （自动生成 SOP 草案？还是只做提示由人编辑？）、驳回是否要记原因（建议要，是知识运营的输入）。
- **红线**：候选**永不直接覆盖已审核 SOP**——一次故障不足以重写确定性路径依赖的知识。

### T7 · 出站飞书卡片推送（P2 收尾）
- **现状**：**入站点击已通**（`ts.` card kind 已注册，点击→推进状态机，未绑定 MateClaw 身份即拒绝）。
  出站没接。
- **两个障碍**：
  1. 平台的 `FeishuCardRenderer` 接口签名是 `render(ApprovalNotice)`——是 tool-guard 的形状，
     渲染不了诊断。当前注册的 renderer 是**故意会抛异常**的，防止有人误接后送出误导性卡片。
  2. ~~**"哪个群收哪个系统的故障"这个绑定尚未设计**~~ —— **会议已给答案**：
     接收方就是**报障那条会话所在的群**，闭环时**自动 @ 原报障人**。不需要 per-system 映射表，
     只需要在报障入站时记下会话来源（群 id + 报障人 externalId），闭环时原路回。
- **随之变化**：出站的第一目标从飞书改为**企业微信**（见 T15），飞书出站沿用同一投影。
- **红线**：卡片上**只放"确认"**。批准生产写和关闭归档要留在工作台——
  卡片摘要不足以支撑这两个决定。

### T8 · P5：放权阶梯（D5）
- **架构已定**（RFC §10）：per-system `delegation_stage`（S0 影子 → S1 建议 → S2 只读自动取证 →
  S3 半自动）+ `system/featureflag` 做全局 kill-switch（fail-closed，未配 flag 默认最保守档）。
- **关键**：**逐系统档位存领域表**，不要塞进 FeatureFlag 的 whitelist——
  那个键是 kbId/userId，不是"系统"，硬掰会埋概念错配。
- **毕业必须数据驱动**：S0 影子跑出的诊断入库后比对"自动 vs 人工"一致率，达标才升档。
- **底线不随阶段转移**：写操作永远人工，S3 也不例外。

---

## 三、前端待办

### T9 · Vue 工作台补齐
- **已落地**：`mateclaw-ui/src/views/Troubleshooting/{index,DerivationChain,SopManagement}.vue`
  （队列 + 判定链 + 处置弹窗 + SOP 注册表），判定链已接 `GET /diagnoses/{id}/derivation`，
  三态（成立/已排除/无法求值）与代入运算都是真实数据；SOP 管理只允许 candidate 注册，
  生命周期单向推进且受 `manage:troubleshooting` 门控。`LLM_FALLBACK` 展示独立的“只读 Agent 建议”分支，
  只高亮服务端核验过的证据引用，并且不请求只适用于确定性 SOP 的 derivation API。
- **待补**：
  - [x] SOP 管理界面（注册/浏览/promote，接 T3 那组 API）——**导入 146 码的人工入口已具备**。
  - [ ] 知识候选审核界面（依赖 T6 定下审核工作流）。
  - [ ] 证据的"重放查询"按钮（适配器已具备；仍依赖 T2 真实绑定核实与专用重查 API）。
- **视觉基线**（照此，别另起炉灶）：冷调中性 + 单一信号蓝 `#2f5cf5`；
  语义色只在有意义处；**机器吐出的数据用等宽字体，人读叙述用无衬线**；
  统一描边 SVG 图标（不用 emoji）；支持浅/深双主题。
- **页面红线**：无"执行"按钮；批准按钮文案必须写明"推进状态，系统不执行"；
  弃权时要解释为什么没有恢复动作，不能留空白。

### T10 · 与一线核对信息架构
- 判定链的阶段划分（现象/范围定位/取证/判定/根因）需要和真实排障心智核对，可能微调。
- 原型里的"影响面/活体状态/在场签收"等维度是否全进 MVP，按放权阶段裁剪。
- **注意**：`IncidentContext.impact` 目前只是**一个字符串**，原型里那些
  "148 工单/12 大客户/扩散中"的结构化影响面**并不存在**，要做得先扩契约。
  → 会议已把这件事变成硬需求（业务视图要"功能影响 + 影响人数"），见 **T13**。

---

## 三·五、会议新增战线（T11–T18）

来源：`meeting-change-plan.md`（会议纪要 → 变更方案 C1–C8）。**动手前先读那份文档的 §二**——
它写清了为什么友商的"执行 SOP 自愈"我们不做。

### T11 · 日志 → SOP 自动生成流水线（**C3；本轮最高优先**）
- **为什么最优先**：这是我们相对研发团队的**唯一差异化**（我们有观测云全量日志）。
  只做错误码匹配，会议原话是"傻瓜似的、算人工的"，拿不出去。
- **做什么**：`SopSynthesisService`，五步——
  `log_search` 取样 → 抽 PS ID → `log_trace_bundle` 拉全链路 →
  **确定性压缩成调用链骨架** → 模型归纳出 `SopDraft` → **强制以 `candidate` 入库**。
- **四条不可协商**：① 压缩必须在模型之前（否则 token 爆炸且模型会去干检索）；
  ② 只能落 `candidate`，D2 审核流程不允许被自动生成绕过；
  ③ 入模型前整包过 `TroubleshootingSecretRedactor`；
  ④ 生成的 `RecommendedAction` 不得带执行语义。
- **完成标准（会议指定，别换案例）**：**「会话消息发送失败」（无错误码）**跑通，
  生成的排查步骤与人工当时的解法一致；再补一条工程门槛——影子回放对历史 incident
  能复现人工结论，不一致的样本逐条解释，**不允许调 prompt 调到看起来对为止**。

### T12 · 工具层扩 signalKind（**C2**，T11 的前置）
- **落点**：全部在既有 `EvidenceSourceAdapter` / `EvidenceSourceRouter` 后面。
- **新增**：`log_search`、`log_trace_bundle`、`interface_latency_rank`、
  `blast_radius_probe`（以上 `GuanceEvidenceAdapter`）、`k8s_workload_health`（新 `K8sEvidenceAdapter`）、
  `code_lookup`（新 `CodeSearchAdapter`）。
- **红线**：**绝不给未命中路 Agent 挂第二个工具**。命中路直调 router、未命中路经
  `TroubleshootingEvidenceTool` 调同一个 router，新能力两边自动同时生效，R4 不被稀释。
- **静默失败陷阱**：`CanonicalEvidenceSchema` 目前只声明了 `log_count`/`metric`/`trace`。
  新 signalKind **必须同步声明字段与类型**，否则返回值被判 `MISSING` → 判据 `UNEVALUATED`，
  表现为"什么都查不出来"却不报任何错。
- **`code_lookup` 边界**：只读、仓库白名单、返回片段过 `TroubleshootingSecretRedactor`。

### T13 · 爆炸半径成为一等公民 + 兜底路由（**C4**，契约 v1.4）
- `IncidentContext.impact: String` → `IncidentImpact(functionScope, affectedCustomers,
  affectedUsers, BlastRadius, note)`；`BlastRadius { SINGLE_CUSTOMER, MULTI_CUSTOMER, SYSTEM_WIDE, UNKNOWN }`。
- **路由顺序照会议原话：先批量、后单客户。** 先跑 `blast_radius_probe`；
  批量 → 进 `scenario:system_down`；单客户且他人正常 → 输出**排除法**结论。
- **诚实约束**：排除法结论 confidence **不得高于 MEDIUM**，且必须标明是"排除"不是"定位"；
  对应判据是 `CriterionOutcome.EXCLUDED`，**绝不能**和 `UNEVALUATED` 混显。
- **明确不做**：浏览器兼容性定位（会议自己承认分析不出来），只做到"系统侧无异常"。

### T14 · 故障分类标签，按类兑现不同承诺（**C5**，契约 v1.4，与 T13 合并一次迁移 V174）
- `FaultClass { CODE_BUG, DATA_FIX, BUSINESS_OPERATION, EXTERNAL_CLIENT, INFRASTRUCTURE }`，
  加到 `Diagnosis`，并**改变输出形态而不只是加个标签**：
  `CODE_BUG` **只给定位**（类/方法 + 代码片段 + 证据链），不给解决方案、不给恢复动作；
  `DATA_FIX`/`BUSINESS_OPERATION` 才给建议动作（仍是建议，执行在 MateClaw 之外）。
- 自动生成的 candidate 必须带 `faultClass`，审核时人要核这一栏。

### T15 · 企微接入 + 服务经理闭环（**C6**）
- 群机器人「数字化服务平台智能小助手」，@ 触发 → **领域 webhook（PAT 鉴权）**。
  **不能走 Trigger 引擎**：`targetType` 只有 agent|workflow，而 workflow 每步调 LLM。
- 新增状态 `AWAITING_INPUT`：信息不足（缺客户 ID / 截图 / 视频）→ 回帖索要 → 补齐后继续；
  复用已有的 `IncidentCompleteness`。附件只存引用与元数据，**视频不做内容解析**。
- 闭环复用 `close` + `record-outcome`；出站原路回群并 @ 原报障人。
- **身份 fail-closed**：企微 userid → `ExternalIdentityEntity{provider="wecom"}`，
  未绑定即拒绝，与飞书 `CardOperatorResolver` 同一规则。

### T16 · 三类 SOP + 路由键泛化（**C1**，含 D1′ 修订）
- `SopKind { ERROR_CODE, SCENARIO, GENERIC }`；sealed `RouteKey`：
  `code:{system}:{errorCode}` / `scenario:{system}:{scenarioKey}` / `generic:{system}`。
  `SopEntry.errorCode` 从必填改为"ERROR_CODE 类必填"。V174 加 `kind` / `scenario_key` 两列并回填。
- **分诊两段式**：先确定性场景匹配器（症状关键词 + service + 指标特征），
  匹配不到再用受限分诊模型，**输出被强制约束在 `scenarioKey` 枚举内**，选不中就落未命中路。
- **D1′（必须同步写进 `HANDOFF.md`）**：零 LLM 的边界从"整条命中路径"收敛到"**判定链**"。
  分诊可以用模型，但模型**只能选路、不能下结论**；命中之后取证→判据→规则→结论仍全程零 LLM。
  这是会议带来的**唯一一次安全边界让步**，不要顺手扩大解释。
- 先交付两个 playbook：`slow_interface`、`system_down`。

### T17 · 双受众渲染 + 展示层冻结（**C7**，小改）
- 业务视图（默认）：问题描述 / 影响面（功能影响 + 影响人数）/ 解决方案或定位结论。
  开发视图：判定链**默认折叠**、一键展开。
- 改动量小：`DiagnosisDerivation` 与 `DerivationChain.vue` 已经把难的部分做完了，
  只需加 `BusinessSummary` 投影 + 前端折叠。
- **同时执行会议的减法**：会议原话"我感觉现在做这个东西太花哨了"。
  `console-*.html` 原型**不再加新版本**，只留作汇报。"再做一版页面"在本轮排期外。

### T18 · 错误码注册表只读查询 + 缺码提示（**C8 的域内部分**）
- 只做两件事：① 错误码注册表只读查询（分配时去重）；
  ② 从诊断闭环反推 `missing_error_code_hint`，作为知识候选产出，由人带去代码仓改。
- **明确不做：不在本仓自动改代码、不自动提 PR。** 代码变更是最强的生产变更，
  同 R1/R3 的精神。

---

## 四、已知的诚实缺口（不是 bug，是有意为之）

接手时别把这些当成待修复的缺陷去"优化"掉：

| 现象 | 为什么是对的 |
|---|---|
| `fixtureMode` 恒 `true` | P3 工程链路已在，但 903001 绑定与阈值未完成 T2 内网核实，仍无权声称证据可信 |
| 未注册错误码 / 无 error_code / SYMPTOM 默认 → 409 | P4 开关默认关闭或专用 Agent 配置不合规时 fail-closed；只有通过全部安全校验并显式启用后才进入只读 fallback |
| fallback 无可验证引用时弃权 | 模型文本不是证据；只有本次服务端取证会话实际返回且非 `MISSING` 的 queryId 才能支撑结论 |
| fallback 置信度最高 `MEDIUM` | `READY_FOR_HUMAN` 仍是待人工确认建议，不允许模型自行声称 `HIGH` |
| 弃权时 `recommendedActions` 恒空 | 契约保证：不确定就不给恢复建议 |
| `/execute` 恒 409 | 让"平台不执行生产写"在 HTTP 边界可见可测 |
| 推导可能 `faithful=false` | SOP 会演进；宁可承认还原不了，也不给看似合理的假推导 |
| 卡片未绑定身份即拒绝 | 飞书 open_id 不可追责，用它记审批等于废掉审批 |
| `Vertical903001Test` 用内存 mapper | 它证明领域组合自洽；SQL 由迁移测试和持久化单测覆盖，分工明确 |

---

## 五、工程约定

- **测试风格**：JUnit 5 + Mockito + AssertJ，纯单测为主。领域测试在
  `mateclaw-server/src/test/java/vip/mate/troubleshooting/`。
- **跑测试**：
  ```bash
  mvn -pl mateclaw-server -am -Dtest='vip.mate.troubleshooting.**.*Test' \
      -Dsurefire.failIfNoSpecifiedTests=false test
  ```
  （`-am` 必须带，否则 plugin-api 依赖解析失败；`-Dsurefire.failIfNoSpecifiedTests=false`
  也必须带，否则兄弟模块没有匹配测试会报错。）
- **前端类型检查**：`cd mateclaw-ui && npx vue-tsc --noEmit`。
  注意 `npm ci` 会失败（锁文件是 pnpm 的），用 `npm install`。
  2026-07-27 基线：`npm test` 共 13 个测试文件、110 项测试全绿。
- **迁移**：新增表要在 `db/migration/{mysql,h2,kingbase}/` **三个方言目录各写一份**。
- **两个 MyBatis-Plus 坑**（踩过）：
  - `getParamNameValuePairs()` 惰性填充，**先调 `getSqlSegment()`** 才有值；
  - 诊断聚合更新走 **wrapper-only（entity 传 null）**，写 fake 时要照此并保留版本闸门；
    SOP 生命周期更新当前是独立的 entity patch，不要把两种 mapper 合同混为一谈。
- **纪律**：不擅自开 PR；改 RFC 保持 § 编号连续；
  源表 xlsx 含真实 token/IP/人名，**未入库、不得入库**。

---

## 六、建议的接手顺序

**会议后的顺序（覆盖旧顺序）：**

1. **拿到内网窗口时，第一件事是 T2 里那条新增的**：验证 **PS ID 是否全链路贯通**。
   T11 整条流水线以此为前提，不通就得先换方案，别先写代码。
2. **主攻**：T12（工具层扩 signalKind）→ T11（日志→SOP 自动生成）→
   跑通「会话消息发送失败」这一个案例。**这是本轮唯一要证明的事。**
3. **接着**：T16（三类 SOP + D1′）→ T13 + T14（契约 v1.4，一次迁移）→ T15 + T17。
4. **只做错误码类 SOP 批量入库时才需要**：T1 → T3。
5. **不建议现在做**：T8 放权阶梯（等真实数据）、T18（价值密度低）、
   任何新的展示层原型（会议明确叫停）。

（旧顺序里"T1→T2→T3 是主要矛盾"的判断已被会议修正：T1 只阻塞错误码类，
主要矛盾转移到"能不能从日志自动生成 SOP"。）
