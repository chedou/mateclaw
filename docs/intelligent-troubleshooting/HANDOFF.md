# HANDOFF —— IT 智能排障系统 on MateClaw（会话记忆）

> 供后续 AI / 工程师直接接续。**本文件 + `rfcs/intelligent-troubleshooting-design.md` 两份读完即可上手。**
> 状态：架构已逐条源码核对通过并落 RFC；**P0 内核 + P1 接入身份 + P2 交付闭环 +
> P3 命中路证据适配底座 + P4 未命中路只读 Agent 工程链路已完成**（2026-07-25—27）。
> P4 默认关闭、待专用 Agent 配置和实机演练；903001 观测云字段/阈值仍待内网 T2 核实，出站卡片推送未接。
> 工作仓库：**webonne/mateclaw**（旧仓库 webonne/MetaClaw 已归档为只读参考，见 §6 指针；
> 本文件的 MetaClaw 时期原版保留在本仓库 git 历史与 webonne/MetaClaw 远端）。

---

## 1. 一句话

把故障处理从「人工翻系统 + 经验判断」升级为「告警/工单驱动 · 智能路由 · 自动取证 · 人机协同诊断 · 知识闭环」。
首个域：CSDP 工单/客服链路。落法：**MateClaw-server 内的确定性领域模块 `vip.mate.troubleshooting`**。

## 2. 八个已锁定决策（D1–D8，已完成源码可行性核对；不等于全部实现）

| # | 决策 | mateclaw 兑现（详见 RFC 对应节） |
|---|---|---|
| D1 | 确定性/AI 边界 =「(system,error_code) 命中？」 | 命中路=领域引擎零 LLM；Workflow 每步调 LLM 故不可承载（RFC §1/§3） |
| D2 | 知识库可演进（candidate→approved→deprecated） | 领域表自建审核生命周期；Wiki 是 LLM 管道、永不做权威（RFC §8） |
| D3 | API-first 故障上下文 Web 台 | Web 台与生命周期 REST 已落；`ts.` 卡片处理器已注册，IM 出站路由待 T7（RFC §5） |
| D4 | 自建 orchestrator + 工具走 MCP/ToolCallback | 命中路领域 service 与未命中路只读 ToolCallback 均已落；P4 运行启用待验收（RFC §3/§6） |
| D5 | 影子 + 回归集 + 放权阶梯（写永不自动） | 复用 FeatureFlag 的设计已锁定；per-system 档位与影子毕业待 P5（RFC §10） |
| D6 | 知识运营（沉淀嵌入流程、专家才评审） | `manage:troubleshooting` capability 门控审核（RFC §9） |
| D7 | 与平台产品一体、运行时隔离 | 单 JAR 内兄弟包、逻辑不寄生 Workflow（RFC §2） |
| D8 | 证据源开放适配（OAL，观测云首适配器） | Router、归一及命中/未命中两个调用方均已落；真实源验证仍待 T2（RFC §6） |

**信任工程五约束**（沿用）：①确定性优先（LLM 不生成恢复动作）②强制引用证据 ③结构化输出+校验闸门
④置信度校准+abstain ⑤上下文预算。

## 3. 四条红线（每个 PR 自检，源码依据见 RFC §5/§12）

1. **生产写工具一个都不注册**（ToolGuard 批准=回放执行，语义与我们相反）。
2. **人工确认只推进领域状态机、执行 0 个工具**（≠ ToolGuard 批准）。
3. **写操作永远外部人工 + `record-outcome` 登记**；平台不连生产写执行器。
4. **未命中路 Agent 锁死只读**（专用直接绑定校验 + 调用级硬白名单 + 服务端取证会话；
   ToolGuard BLOCK 作纵深防御）＋命中路零 LLM。

## 4. 当前阶段矛盾分析（毛选方法论 · 2026-07 刷新）

**矛盾清单**（P2 完成后已再次转化，2026-07-26 三次刷新）：
- [闭环代码已通且可测] vs [尚未在真实数据上跑过一次]（排障域定向回归全绿，但 SOP 库是 fixture、
  观测云未联调、无人真正用工作台处置过一次真故障）
- [知识质量天花板]（只读可自动化 30/146≈21%、3 路由键冲突、103 处字符丢失）vs [自动化雄心]
- [Java 重实现工作量] vs [Python MVP 已验证资产]（38 测试 + 7 subtests，可同构直译）
- [单点竖切验证]（903001 需内网联调）vs [面上铺开]（146 码、多系统）
- [信任建立]（影子期慢积累）vs [见效压力]

**⭐ 主要矛盾**：[代码闭环已通] vs [从未在真实数据上验证]。报障→诊断→确认→转派→批准→登记→
关闭→知识候选这条链已全部落地且有测试，工程能力不再是瓶颈；当前系统性质由「**它还没被现实检验过**」
规定——SOP 库仍是 fixture、`fixtureMode` 恒 true、观测云 DQL 未内网核实、L0 三个路由键冲突未裁决。
再往下堆功能（出站卡片、启用 Agent 兜底、放权阶梯）都是在未验证的地基上加层。
**性质**：非对抗性，但已由「工程矛盾」转为「实践检验矛盾」——**主战场从写代码转到接真实数据**。
**矛盾的主要方面**：在「知识与数据未就位」一侧（这正是前几轮预判会上位的天花板，现在如期上位）。
**应对**：主攻转向 ①903001 fixture 端到端联跑（先证明链路自洽）→ ②清 L0 三个路由键冲突 →
③争取内网窗口核实观测云字段阈值 → ④真实 SOP 入库、放开 `fixtureMode`。
出站卡片推送等功能项降为次要，不与实践检验争主力。
**⚠️ 需监控（矛盾转化）**：P0–P2 跑通后，[知识质量 vs 自动化范围] 将上升为主要矛盾——它是全过程的
根本天花板（前一阶段已确立：**系统天花板 = 知识质量，不是技术**，故不承诺"上线即全自动"）；若内网
联调窗口先到，临时优先核实 903001 真实取证。

**持久战三阶段映射**（底线不随阶段转移：写操作永远人工）：
- 战略防御 = S0 影子：纪律为王、不承诺自动化、积累回归数据；
- 战略相持 = S1–S2：建议卡 + 只读自动取证，知识候选闭环开始造血（146 码逐批审核毕业）；
- 战略反攻 = S3：半自动 + 多系统接入 + 平台化复用。

**群众路线**：知识候选从一线关闭沉淀中来，审核毕业后回到一线工作台/卡片中去（D6 贡献者收益、覆盖率进 KPI）。
**批评与自我批评**：每 PR 四条红线自检；影子回归一致率是客观批评者。
**实事求是**：一切结论逐源码核对（已做，RFC §12）；内网核实 903001 DQL 前不宣称"取证已验证"。
**星火燎原**：903001 竖切 = 根据地；先 1 个码全闭环 → 18 个 P0/P1 → 146。

## 5. 刷新后的代办（集中兵力重排；细目见 RFC §13）

**已完成（2026-07-25）**：
- **P0** 领域骨架 + record 契约 + 6 类 sealed 规则引擎 + `DeterministicDiagnosisService`
  命中路端到端编排 + 人工控制状态机 + MyBatis-Plus/Flyway `V172` 三方言 +
  携带 `workspace_id` 的事务 Outbox/poller + 五分钟幂等；`vip.mate.troubleshooting.**.*Test` 共 33 项通过。
- **P1 接入与身份**：`TroubleshootingIntakeService`（路由→确定性诊断→持久化）+
  `TroubleshootingController`（`POST /api/v1/troubleshooting/incidents` 接入、
  `GET /diagnoses/{id}` 读取、`POST .../actions/{id}/execute` 恒 409）+
  三个 capability 挂进 `RoleCapabilities`（viewer→view / member→operate / admin→manage）。
  排障域测试增至 **40 项**，连同 workspace 域回归共 153 项通过。
  P1 当时的三条诚实性约束已固化为测试：**未注册 SOP → 409 知识缺口**（不编造诊断）、
  **无 error_code / SYMPTOM → 409**（P1 当时未命中路未接线；P4 已改为“显式启用且安全配置通过才进入只读 Agent，
  否则仍 409”）、
  **`fixtureMode` 恒 true**（P1 当时 P3 尚未到位；现在虽有 P3 工程链路，但 T2 未核实，仍不得声称证据可信）。
  webhook 鉴权**不需要新过滤器**：`JwtAuthFilter` 已按 `mc_` 前缀识别 PAT，告警源用受限 PAT 即成为正常主体。

- **P2 交付与闭环**：`DiagnosisLifecycleService`（加载→状态机→乐观版本写回）+ 生命周期 REST
  （confirm / transfer / actions/{id}/approve / actions/{id}/record-outcome / close）+ 队列列表
  （只读索引列、不解析聚合）+ **Vue 工作台** `mateclaw-ui/src/views/Troubleshooting/`
  （队列 + 判定链组件 + 处置弹窗，路由挂 `view:troubleshooting`）+ **`ts.` 飞书 card kind**。
  排障域测试增至 **56 项**，连同飞书卡片域共 71 项通过；`vue-tsc` 无错。
  关键设计判断：①操作人取自认证主体、不信请求体，审计不可伪造；②关闭与知识候选入 Outbox
  同事务，崩溃不会丢教训；③卡片点击必须能映射到 MateClaw 用户（走 `ExternalIdentityEntity`
  SSO 绑定），**未绑定即拒绝**——卡片不是绕过身份与权限的旁路；④卡片只放"确认"，
  批准生产写与关闭归档留在工作台（卡片摘要不足以支撑这两个决定）。

- **903001 端到端联跑已通过**（`Vertical903001Test`，3 项）：注册 SOP → 报障 → 判据求值 →
  规则命中 → 确认 → 转派 → 批准 → 登记外部结果 → RECOVERED 关闭 → 知识候选入 Outbox，
  用**真引擎 + 真状态机 + 真持久化服务**（含真 JSON 往返、真幂等键、真乐观版本），只把 mapper 换成内存存储。
  已坐实：①按知识库写法编排的 SOP 确实能驱动 6 类判据在真实观测值上正确点火，
  且**相邻的"实例不可达"规则不会误胜**（`reachable=true` 把宕机假设排除）；②重复报障命中五分钟桶、
  不开第二个案子；③**已批准但无已验证外部结果时，RECOVERED 关闭被拒**；④关闭沉淀的候选只进 Outbox，
  已审核 SOP 不被改写；⑤必需证据 MISSING 时弃权且**不产出任何恢复动作**。
  **未覆盖**：mapper 是内存的，不验证 SQL（SQL 由 `TroubleshootingMigrationTest` + 持久化单测覆盖）。

- **SOP 管理 API 已就绪**（2026-07-26）：`POST/GET /sops`、`GET /sops/{sys}/{code}`、
  `POST /sops/{sys}/{code}/status`（均需 `manage:troubleshooting`），
  外加 `GET /sops/candidates` 只读候选队列。**这拆掉了"真实 SOP 无法入库"这个阻塞**。
  状态流转单向 fail-closed（`candidate→approved→deprecated`，不可回退；approve 同时置 `verified`，
  因为 `operational()` 需二者皆真，半升级的 SOP 会一边看着已审核一边持续弃权）。
  **版本替换尚未完成**：deprecated 行仍占用唯一 routeKey，同一路由的新 sopId 会冲突；当前只能退役留痕，
  不能声称已经具备“退役后发新版”能力，后续需补历史版本 + 唯一当前版本模型。
  候选队列只读，**因为 Outbox 的 status 是"发布"语义而非"审核"语义，混用会让投递重试伪装成审核通过**——
  候选审核工作流是尚未设计的增量，见 `TODO.md` T6。

- **SOP 管理 Vue 已就绪**（2026-07-27）：管理员入口 `/troubleshooting/sops` 支持注册表筛选、
  完整合同检查、candidate JSON 即时校验和 `candidate→approved→deprecated` 显式确认。
  前后端都拒绝以 approved/verified 状态绕过审核；页面没有执行诊断或生产写的按钮。

- **P3 D8 证据源适配工程链路已就绪**（2026-07-27）：`EvidenceSourceAdapter` +
  `(system,signalKind)` 主备 `EvidenceSourceRouter` + `GuanceEvidenceAdapter` +
  `RecordedReplayAdapter`。排障入口只为缺失请求取证，已有调用方证据不重复查；任何源异常、HTTP 错误、
  畸形响应或模板不安全都 fail-closed 成 `MISSING`，继续沿用弃权且无恢复动作的红线。
  Guance 使用官方 `POST /api/v1/df/query_data_v1` 和 `DF-API-KEY` 请求头；凭据不进日志/回放。
  两个源默认关闭，随仓只有脱敏 903001 三信号样本；`GET /api/v1/troubleshooting/evidence/sources`
  只报告源级 readiness。**未完成的部分仍是 T2：没有内网真实响应，因此 measurement、字段别名、阈值与
  per-binding verification 均不能宣称已验证，`fixtureMode` 继续恒 true。**详见
  `evidence-adapter-runbook.md`。

- **P4 未命中路只读 Agent 工程链路已就绪**（2026-07-27）：无 error code、`SYMPTOM`、无 SOP 三类
  route miss 进入 `TroubleshootingAgentTriageService`；`TroubleshootingEvidenceTool` 复用同一
  `EvidenceSourceRouter`，服务端会话固定 Incident 上下文并限制取证次数。普通 Agent 绑定会兼容性扩入
  system-level/MCP 工具，故新增 `AgentService.chatWithToolAllowlist()` 与 `AgentGraphBuilder` 最终交集，
  受限图使用独立缓存键，模型实际只能看到 `collect_troubleshooting_evidence`；它不读会话历史/
  memory/wiki/runtime/skill/goal 上下文，禁用通用 `ToolResultStorage` 原始结果 spill 和 provider fallback，
  要求 Agent 显式绑定唯一 enabled 模型并跳过全局默认/capability 路由；模型歧义、provider 禁用/未配置或
  原生搜索开启均 fail-closed 409。运行时调用失败保守弃权，不自动选择备用 provider。已有/新采集的
  canonical EvidenceResult 的全部字符串字段与递归 key 在进入模型和 Diagnosis 前统一脱敏，危险 queryId
  安全重映射；脱敏结果仍随 Diagnosis 持久化以供审计。初始未受信上下文经脱敏、转义和独立字符预算
  确定性截断；工具会话同时校验 conversationId + workspaceId，queryId 必须安全且会话内唯一，重复调用不能覆盖
  已引用证据。硬作用域在构图/执行前清空请求级 ThinkingLevel 并在结束后恢复，受限图内再次忽略环境覆盖；
  工具原始参数只进入 Guard 与 callback，不进入 event/SSE/log/audit/approval，`NEEDS_APPROVAL` 在此路径直接拦截。
  输出必须是结构化 JSON，引用必须来自本次会话且证据非 `MISSING`；
  空结论或无效引用强制 `LOW + abstain`，有效 fallback 最高 `MEDIUM`。`Diagnosis` 升至 1.4 并兼容读取 1.3，
  fallback 永远不生成动作；Vue 展示只读建议与已核验证据，不调用确定性 derivation API。
  **运行仍未启用**：默认开关关闭，专用 workspace-local ReAct Agent、ToolGuard BLOCK 纵深规则与实机演练
  均待 operator 完成；`fixtureMode` 不变。详见 `agent-miss-path-runbook.md`。

**主攻与全部待办已独立成册**：见 **`docs/intelligent-troubleshooting/TODO.md`**
（T1–T10，每条含「为什么/完成标准」、四条红线、诚实缺口清单、工程约定与建议接手顺序）。
一句话概括：**主要矛盾仍是「接真实数据」**——T4/T5 工程底座已拆掉代码阻塞，下一关键路径仍是
T1 清路由键冲突、T2 内网核实观测云、T3 真实 SOP 入库并放开 `fixtureMode`，三者都需人的介入。

**钳制/并行（不占主力，多为需内网/人力项）**：
- L0 数据 blocker：3 个路由键一码多义（101014/101034/101040）owner 裁决 + 103 处字符丢失回源表恢复
  （清洗器 fail-closed，阻断未解决前拒绝覆盖 canonical KB，见 `l0/quality_report.md`）；
- 内网联调窗口准备（观测云 `*.prd.sangfor.com`，DF-API-KEY 鉴权）→ 核实 `l0/activated/903001.md` 的
  `«待核实»` 字段；
- 903001 模式复制到其他高频码（901002/2000001/801008…backlog 见 `l0/inventory_report.md`）。

**后续梯队**：P3/P4 工程链路已完成 → T2 内网验证 + P4 专用 Agent 配置/实机演练
→ P5 放权阶梯 + 知识运营（覆盖率/可自动化率纳入考核）。

## 5.5 前端/页面设计（HTML 原型已收敛方向，Vue 工作台已落地）

**产物入口**：`docs/intelligent-troubleshooting/index.html`（设计门户，汇报用；串起下列所有原型 + 设计主线叙事）。

**产品定位锁定（关键，别再跑偏）**：这套系统的页面**主角是「帮开发从现象快速定位到根因」**——不是运维审批流转台。用户明确纠正过两次：
1. "阶段"指的是**单次事件从症状到根因的定位过程**，不是处置流程（接入/批准/关闭）的人工流转；
2. 详情页要能看到这条**根因定位链**，服务于开发快速定位。

**设计主线（四次迭代收敛，index.html 有可视化）**：
- v1 `console-disposition.html`——信息陈列（三栏工作台）；
- v2 `console-disposition-v2.html`——决策中心（Diagnosis 提为常驻主角、流程降为进度带、置信阈值参照、批准前强制复核）；
- v3 `console-disposition-v3.html`——注意力自适应（按 不确定性×影响 三种姿态：高影响确认/自动驾驶/调查工作台；补影响面、活体状态、拆解式置信、异议一等公民）；
- **现行 `console-rca.html`——根因定位视图**（答案先行 + 收敛漏斗「全平台→系统→服务→依赖→根因」+ 五阶段定位链 现象/范围定位/取证/判定推理/根因，证据与 DQL 可展开重放；命中路 2 秒定位，未命中路 agent 探索到半路弃权、把开发放到"跑起来的起点"）。

**另一现行屏**：`console-overview.html`——值班总览看板（所有故障按处置阶段铺开、系统自动列褪背景/等人列高亮、卡片显示阶段滞留时长、主动喊瓶颈；点卡片下钻到定位视图）。

**视觉基线（已定，后续 Vue 实现照此）**：冷调中性 + 单一信号蓝 `#2f5cf5`；语义色只在有意义处（红=现象/绿=根因/琥珀=弃权）；**字体双角色有含义**——机器吐出的数据（错误码/指标/DQL/时间戳/置信度）用等宽，人读叙述用无衬线；统一描边 SVG 图标（不用 emoji）；避开"左侧色条+圆角卡"套路；支持浅/深双主题。

**页面上必须守的红线（对齐 §3）**：无"执行"按钮；批准=推进状态机、不执行；写恢复动作显示为"转派+外部登记结果"；agent 步骤标只读；结论强制挂证据引用。

**已落地的 Vue 实现**：`mateclaw-ui/src/views/Troubleshooting/{index,DerivationChain,SopManagement}.vue`
（队列 + 判定链 + 处置弹窗 + SOP 管理；诊断路由需 `view:troubleshooting`，SOP 管理路由需
`manage:troubleshooting`）。确定性诊断展示 SOP 判定链；`LLM_FALLBACK` 展示独立的只读 Agent 建议、
只高亮服务端核验过的引用，并跳过只适用于确定性路由的 derivation 请求。
HTML 原型仍是设计与汇报载体，**实现以 Vue 为准**；原型里 `console-diagnosis-detail.html` 是契约对齐版，
其判定链（代入运算、已排除 vs 无法求值）比当前 Vue 组件更细，是 Vue 侧后续要补齐的目标形态。

**已补齐（2026-07-26）**：`GET /diagnoses/{id}/derivation` + Vue 判定链接通——三态
（成立 / 已排除 / 无法求值）与**代入运算**（如 `2000 ÷ (2000 + 0) = 1 > 0.95`）已是真实数据。
两个关键设计判断：①**代入算式由服务端 `CriterionRenderer` 渲染**，前端不重实现求值，杜绝控制台与
引擎判读漂移；②推导是 `诊断 × SOP` 的投影而非新状态，而 SOP 会演进，故服务端**重算后与当时记录的
`triggeredSignals` 交叉核对**，不一致即置 `faithful=false` 并说明"SOP 已变更、以下反映当前知识"——
宁可承认还原不了，也不给一个看似合理的假推导。

**下一步（UI 线）**：
- [ ] 定位链的**阶段划分**（现象/范围定位/取证/判定/根因）需与一线实际排障心智核对，可能微调。
- [ ] 证据的"▷重放 DQL"已有 D8 适配器底座，但仍要完成 T2 真实绑定核实和专用重查 API；当前是 fixture 演示。
- [ ] 原型里的"影响面/活体状态/在场签收"等维度是否全部进 MVP，按放权阶段裁剪。

## 6. 指针与安全口径

- **新架构（唯一现行设计）**：`rfcs/intelligent-troubleshooting-design.md`（§1–§13 + §14 实施战略；
  每条结论有源码位置索引）。
- **实现入口**：后端 `mateclaw-server/src/main/java/vip/mate/troubleshooting/`（`controller/` 接入+生命周期、
  `card/` 飞书入站卡片、`service/` 编排与闭环、`agent/` 未命中只读分诊）；前端
  `mateclaw-ui/src/views/Troubleshooting/`；迁移为三方言 `V172__troubleshooting_domain.sql` 与
  `V173__register_troubleshooting_evidence_tool.sql`；测试入口
  `mateclaw-server/src/test/java/vip/mate/troubleshooting/` 及 `vip/mate/agent/AgentServiceToolScopeTest.java`。
- **前端设计门户**：`docs/intelligent-troubleshooting/index.html`（汇报入口，串起现行原型 + 演进；详见 §5.5）。
  现行原型 `console-rca.html`（主推·根因定位）、`console-overview.html`（总览看板）；
  迭代过程 `console-disposition{,-v2,-v3}.html`。
- **MetaClaw 时期历史资产**（架构结论已被 RFC 吸收/取代）：蓝图 v0.3 `architecture-blueprint.html`、
  走读复核 `architecture-review.md`（含 G1–G7 缺口表）、D7/D8 设计稿、旧原型
  `console-prototype{,-b}.html` / `console-workbench.html`、`executive-summary.html`。
  **`l0/` 数据资产仍现行有效**（sop_kb.json 146 码已脱敏、inventory/quality 报告、清洗闸门脚本、903001 取证草案）。
- **Python MVP 参考实现**（规则引擎/状态机/Outbox/38 测试的同构翻译源）：在 **webonne/MetaClaw** 仓库
  `zhinengpaizhang-dev` 分支的 `metaclaw_troubleshooting/` + `tests/`。本地克隆已剔除，远端仍在、只读参考。
- **安全**：源表《故障与措施》xlsx **含真实 Bearer/JWT token、内网 IP、人名，从未入库**（在用户本地）。
  `l0/sop_kb.json` 已脱敏（Bearer/JWT→`<BEARER_TOKEN>`，查询/JSON token→`<TOKEN>`，IP/人名保留）。
  若把源表纳入版本管理，务必先脱敏 token；webonne/MetaClaw 的旧 Git 历史快照可能保留修复前 token，
  如确认属有效凭证应立即轮换；未经明确授权不擅自改写 Git 历史。
- **纪律**：当前本地分支 `claude/intelligent-troubleshooting-design`；以用户当前明确选择的分支为准；
  不擅自开 PR；改 RFC 保持 § 编号连续；沿用仓库现有提交说明约定；不冒用未参与本轮工作的
  Co-Authored-By 身份。
- **方法论 skills**：已迁至本仓库 `.claude/skills/`（矛盾分析/集中兵力/持久战/群众路线/批评与自我批评等，
  `/<name>` 调用）。
