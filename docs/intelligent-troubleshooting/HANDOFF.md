# HANDOFF · IT 智能排障 on MateClaw

> 更新时间：2026-07-29
>
> 仓库：`webonne/mateclaw`
>
> 分支：`claude/intelligent-troubleshooting-design`
>
> 当前架构：`rfcs/intelligent-troubleshooting-architecture-v4.md`
>
> 架构评审：**APPROVED FOR P1 IMPLEMENTATION**
>
> 第一性原理评价与修订：`architecture-critique-v4.md` —— 用户已认可，v4 现为 **v4.3 / 蓝图 v0.16**

## 1. 一句话

MateClaw 智能排障的中心是一条“报障上下文 → 只读取证 → 可引用诊断”的证据脊柱；它同时服务在线排障和
知识生产两个闭环。当前第一枪是会议指定的无错误码案例“会话消息发送失败”，不是继续扩 903001 页面。

## 2. 为什么重新定架构

2026-07-27 的 28:30 录音明确：团队真正的差异化是能使用观测云日志，经 PS ID 还原全链路，再由 AI 形成
根因假设和排查步骤。旧 v3 把日志→SOP 降成辅助能力、只把错误码当主轴、把 Web 当主要入口，和录音不符。

现已建立两个现行事实文件：

1. `recording-product-baseline.md`：F1–F11，区分会议事实和讨论脑暴；
2. `intelligent-troubleshooting-architecture-v4.md`：把事实推成工程合同。

旧 `architecture-v2.md`、`v3.md` 和 `meeting-change-plan.md` 仅用于追溯。

## 3. 已锁定架构决定

| # | 决定 |
|---|---|
| D1 | 产品中心是一条共享 Evidence Spine；在线诊断与知识生产是两个一等闭环 |
| D2 | 权威 Playbook 只分 ERROR_CODE / SCENARIO；OPEN_DISCOVERY 用独立 DiscoveryPolicy |
| D3 | `investigationMode` 与 `routeAuthority` 分开；模型提议不能伪装成确定性命中 |
| D4 | 错误码 approved Playbook 命中路保持零 LLM |
| D5 | PlaybookDraft 可在 outcome 前产生，但不满足按 origin 定义的资格不得 approved |
| D6 | 在线诊断与知识合成复用同一 Evidence Router/Adapter，不建第二套取证 |
| D7 | 企微群 @ 是主要一线入口；Web 用于开发证据、处置和知识审核 |
| D8 | 一份 Diagnosis 生成 BusinessSummary 与 DeveloperEvidenceView，不建两套事实 |
| D9 | 自动化永久止于只读；生产写只在系统外由人完成并登记 outcome |
| D10 | 所有能力继续在当前 Java MateClaw 运行，不引入第二运行时 |
| D11 | Agent 仍只看到唯一只读证据门面；内部按语义 Tool 与来源 Adapter 两层 SPI 插拔 |
| D12 | Loop Engineering 是一等控制机制；调查内循环与知识外循环都有显式状态、预算、验证和停止原因 · **PENDING-EVIDENCE** |
| D13 | 多 Agent 只做固定角色、固定一轮的结构化反证；先影子后治理，永不以共识/投票取得裁决权 · **PENDING-EVIDENCE** |
| **D5′** | `EVIDENCE_DERIVED` 晋升分校准期 / 运行期两档；退出校准期靠样本数据而非日期 |
| **D14** | 北极星用四个时间戳度量，三段差值分开统计 |
| **D15** | 证据合成必须取成功样本对照；缺失只降级不失败，且锁定校准期档 |
| **D16** | 未被真实失败检验过的设计分支标 `PENDING-EVIDENCE`，不得据以新增实现、接口或表结构 |
| **D17** | 通道一律复用平台现有 `ChannelAdapter` / `ChannelMessageRouter`，不新建入站；`CardKind` 只路由模板卡片事件，不能冒充普通 @ 消息 Intake；诊断卡片不得复用 tool-guard 的 `ApprovalNotice` 形状（v4 §7.4） |

修改 D4、D5/D5′、D9 必须单独 RFC 并由用户明确确认。
D12/D13 当前为 `PENDING-EVIDENCE`：在 P2 真实样本给出失败模式之前，不得据其新增实现。

**红线不在本文维护。** 唯一权威清单是 v4 §9；本文与 TODO 只引用，不复述条目
（此前四处各写一遍且条数措辞不一，见 `architecture-critique-v4.md` §2.5）。

蓝图已升级到 v0.16：v0.12 锁定通道复用，v0.13 校准正式工作台与双投影，
v0.14 校正企微普通消息入站接缝与身份边界，v0.15 记录 P3 T10 前半段的持久化异步调查、
幂等 Diagnosis、纯文本 BusinessSummary 与正式工作台深链，v0.16 记录 Diagnosis 关闭 outcome 的
持久化原路 @ 通知与正式工作台最终处置卡。这些版本均不扩大
P1：P2 才在历史样本上影子运行 Evidence Challenger /
Safety Challenger，P4 才为 SCENARIO / OPEN_DISCOVERY 引入 Loop Control。

## 4. 当前代码真实状态

### 已完成

- Java 领域模块 `vip.mate.troubleshooting`、REST、RBAC、三方言 Flyway、状态机和持久化。
- 903001 确定性错误码竖线，命中路零 LLM。
- 受限 Agent miss-path：唯一只读证据工具、服务端会话、硬白名单、引用校验、abstain。
- `EvidenceSourceRouter`，Guance 与 Recorded Replay 两个 Adapter，canonical schema 和脱敏。
- **P2 T6 租户授权边界（2026-07-29）**：`workspaceId` 已贯穿 Intake、Agent 会话、SOP 合成、
  Router 和 Adapter；Guance 必须由唯一的 `workspace/system/service + signalKind → concrete binding`
  映射显式放行，映射缺失或歧义时在使用 API Key、发 HTTP 前 fail closed。默认 `asset-bindings=[]`。
- **P2 真源验证接缝（2026-07-29）**：新增 workspace/system/service 级的秘密无关就绪投影，
  只在精确资产与两个核心信号绑定均通过后检查凭据是否存在；未授权时连 API Key 都不读取。
  管理员可从正式工作台的“P2 真源门”触发 Guance-only
  `log_search → log_trace_bundle`；Router 先限定允许源，因此不会回退 Replay。报告仅含匹配数、
  PS ID、trace 节点数、绑定引用与时间戳，不含原始日志、DQL 或凭据，且明确不关闭 `fixtureMode`。
- 后续扩展已锁定为域内 `ReadOnlyEvidenceToolRegistry → Tool SPI → EvidenceSourceAdapter SPI`；当前尚未实现 Registry，不能把目标设计写成已完成代码。
- **与平台的融合已逐条核对（2026-07-28）**：领域包对平台只有 11 个 import
  （`AgentService`/`AgentBindingService`/`ChatOrigin`/`AgentEntity`、`AuthService`/`UserEntity`/
  `ExternalIdentityEntity`/`ExternalIdentityMapper`、`RequireWorkspaceRole`、`R`、`MateClawException`），
  反向平台侧有 5 个文件知道排障域（`Capability`、`FeishuCardDispatcher`、飞书 kind factory、
  `AgentGraphBuilder` 的调用级硬交集、`WikiRawMaterialEntity`）。单 JAR 兄弟包，D10 成立。
- **已发现并修正的融合缺口**：设计此前把企微当成需新建的入站通道，而平台自带
  `vip.mate.channel.wecom`（Adapter + 多 kind Dispatcher + `ChannelSessionStore`）。
  2026-07-29 进一步源码核对确认：`CardKind` 只处理模板卡片点击，普通 @ 消息实际走
  `WeComChannelAdapter → ChannelMessageRouter`。已在 Router 加通用 pre-route 接缝，不新建 webhook/签名校验。
- **P3 T9 IntakeSession 首段（2026-07-29）**：企微渠道只有显式设置
  `troubleshooting_intake_enabled=true` 才会被排障域接管；已实现
  `RECEIVED → AWAITING_INPUT → READY`、显式 `reportedAt/readyAt`、确定性补问、
  sourceMessageId receipt 幂等、稳定哈希 routing key、不可变 reportedAt 事件时间边界与乱序保护、
  聚合版本检查、覆盖事务提交的同节点锁、唯一键冲突回滚后单次重试、附件安全引用和
  H2/MySQL/Kingbase V175–V177（V177 从聚合真实首条时间修复历史回填并收紧非空）。企微 Adapter 解析并校验 `send_time`，Router 在 pre-route
  接管前写入带 channelId/targetId 的 `ChannelSessionStore`。READY 时原子释放 active key；
  迟到事件按 reportedAt 边界归入上一 Session，只登记回执且不覆盖聚合，时间更晚的新报障才创建新 Session。
  `reporterRef` 只是不可信通道身份：可报障/补充，不得审核或推进受审计状态。接管后不进
  Trigger/通用 Agent；入库失败与“已入库但回复失败”分类处理，不会误报为资料丢失。
- **P3 T10 READY 异步调查与原路摘要（2026-07-29）**：Intake 首次进入 READY 时与
  `mate_troubleshooting_intake_investigation` 的 PENDING 任务在同一事务提交；数据库租约 worker
  带 120 秒租约和最多 5 次常规处理，启动时补齐历史 READY 缺失任务。`source_intake_session_id` 的
  workspace 唯一约束保证一个 Intake 只创建/复用一个 Diagnosis；通知失败只重投既有 Diagnosis，
  不重复调查。常规预算耗尽后进入持久化终态投递并持续退避重试；恢复前会按 Intake 回查已落库
  Diagnosis，存在时继续发送 BusinessSummary，只有确实没有 Diagnosis 才发送 fail-closed 文本。
  worker 复用既有 `TroubleshootingIntakeService`：确定性 Playbook 命中仍为零 LLM，未命中仍进入原有
  只读、显式启用、fail-closed Agent 边界。稳定 raw `conversationRef` 只用于 Intake 身份与 routingKey，
  精确 `deliveryConversationId` 单独保存。只有 workspace/type/enabled 均匹配且本节点持有 active leader
  Adapter 时才认领；精确路由缓存 miss 会回源 DB，follower 不消耗任务。结果只从同一 Diagnosis 投影
  `BusinessSummary`，经 `ChannelSessionStore → ChannelManager.sendToWorkspaceConversation → proactiveSend`
  原路返回纯文本与 `/troubleshooting?diagnosisId=...` 深链；企微必须收到平台 ACK 后才完成任务，
  不发送 DeveloperEvidenceView、原始日志或 DQL。三方言 V178 + V179 已加入；未启用任何生产通道配置，
  也未增加生产写能力。
- **P3 T10 关闭结果原路通知（2026-07-29）**：Intake 来源 Diagnosis 进入 `CLOSED` 且
  `ClosureRecord` 已登记时，聚合更新与 V180 通知状态在同一事务边界提交。独立 120 秒
  租约 worker 只在 workspace/type/enabled 匹配且本节点持有精确 local leader 路由时认领，
  重读同一 Diagnosis 的 `BusinessSummary + ClosureRecord`，投递 outcome、原诊断、问题、处置摘要、
  恢复验证、能力边界、fixture 标记和正式页深链。`DeliveryOptions` 只将安全 reporter ID
  渲染为企微 `<@userid>`，非法 ID 被丢弃且不打印原值；平台 ACK 后才完成，失败持久退避、
  无硬重试上限。群聊由持久化 `ChannelSession.targetId != senderId` 判定，只有当前 Adapter 仍持有
  入站 reply context 才算可投递；服务重启后任务保持未认领，等群内新消息恢复 `req_id`，不回落
  `aibot_send_msg`。结案摘要入库前限制 500 字并拒绝凭据、DQL、原始日志与伪造 mention；旧记录出站
  另受脱敏、mention 转义和 1800 字硬预算。直接 Web/API Diagnosis 没有原路，保持 `NOT_APPLICABLE`。正式页已从
  `Diagnosis.closure` 展示“最终处置结果”；旧版路由不变。
- `log_search` / `log_trace_bundle`，PS ID 一致性、时间排序、行数/字符/时间窗边界。
- `DeterministicLogTraceCompressor`。
- `SopSynthesisService.preview()`：fixture scope 中跑到 `READY_FOR_MODEL`，不调模型、不入 candidate。
- **正式 Playbook 证据学习入口（2026-07-29）**：`/troubleshooting/sops` 已增加“无错误码证据预览”，
  直接调用正式 `POST /api/v1/troubleshooting/sops/synthesis/preview`，可见固定
  `log_search → log_trace_bundle → contrast_sample` Evidence Spine、PS ID 调用链和成功样本对照。
  服务端继续把该接口硬限制在 Recorded Replay；本次预览入口与弹窗没有模型调用、candidate 创建、审核或
  晋升入口，不改变 SOP 管理页已有的独立治理能力。Replay 在默认配置中仍为关闭，只有本地验证时才可显式启用。
- `contrast_sample` 成功样本对照；缺失只降级并锁定校准期，不中断草稿生成。
- `PlaybookDraftInducer`：复用现有模型配置和 Spring AI 结构化输出，最多一次低温调用。
- `PlaybookDraftValidator`：确定性拦截猜码、伪引用、secret、DQL/raw log、工具调用和生产写。
- `ReferenceSolutionComparator`：对会议正例按意图、顺序和证据类型比较，不做文字相似度。
- `SopSynthesisService.generate()` 与 `POST /sops/synthesis/candidates`：幂等创建/复用只待审 candidate。
- 独立 `reviewStatus=CANDIDATE` / `validationStatus=VALID`，且
  `approvalEligibility=NOT_ELIGIBLE`；不写 active approved Playbook。
- H2/MySQL/Kingbase V174 candidate 表，generation key 按 workspace 唯一；四个北极星时间戳与三段成本已入合同。
- 固定 Replay Eval 已组合真实 Replay/Router/压缩/结构化解析/Validator/参考比较/Store；
  正例创建并幂等复用，危险输出在入库前被拒绝。
- Diagnosis 人工处置闭环与 Vue 工作台。
- **正式双投影纵切（2026-07-29）**：新增服务端
  `DiagnosisExperienceProjection` / `DiagnosisExperienceProjectionService` 与
  `GET /api/v1/troubleshooting/diagnoses/{id}/projection`；同一 Diagnosis 生成
  `BusinessSummary` 和 `DeveloperEvidenceView`，构造器落实结论置信、精确人数证据引用等不变量。
- **正式路由已吸收选定信息结构**：`/troubleshooting` 读取队列、完整 Diagnosis 与双投影真实 API，
  业务摘要默认展开、开发证据默认折叠，并保留确认、转派、批准不执行、登记外部结果和关闭能力。
  原工作台临时迁到 `/troubleshooting/legacy`，携同一个 `diagnosisId` 可直接回退。
- **Diagnosis 1.5 运行时事实已落地（2026-07-29）**：显式持久化
  `investigationMode` / `routeAuthority` / `conclusionType` / `NorthStarTimings`，保持 1.3/1.4 JSON 兼容；
  规则被已取得证据全部反证时产出可确认的 `EXCLUDED`，缺证据才是 `INSUFFICIENT_EVIDENCE`。
  报障/就绪/结论时间在 intake 和调查边界采集，第一次人工确认记录 handoff/adopt cost。
- **Diagnosis 1.6 结构化影响合同已落地（2026-07-29）**：`IncidentContext.impact` 从字符串升级为
  `IncidentImpact(functionScope, affectedCustomers?, affectedUsers?, blastRadius, evidenceRefs,
  observedAt?, note)`；1.3–1.5 字符串按 `UNKNOWN` 兼容读取。正式投影只在引用的非缺失
  `incident_impact` canonical evidence 能逐项复算人数、扩散范围和观测时间时展示精确值；精确人数必须
  同时带 `observedAt`，每条引用都要通过 schema 且公共字段一致，任何引用缺失、混入非影响证据或相互
  矛盾都一律降级为 null/UNKNOWN。Intake 在路由、取证和持久化前统一脱敏影响文本。当前完成的是合同与
  信任边界，不代表真源已产出影响数据。
- KnowledgeCandidate 与 Outbox 发布语义；尚无独立审核语义。
- 三套只读 Demo 原型，均显式显示 Recorded Replay、MODEL_PROPOSED、MEDIUM、CANDIDATE。

### 尚未完成

- 真实 Guance 资产授权值尚未由 owner 配置，measurement/字段/PS ID/阈值也未完成内网验证；
  `fixtureMode` 仍应为 true。现在有可操作的单次验证入口，但没有 owner 配置和真实返回，
  不得将“入口已实现”改写为“T7 已通过”。
- 真实模型的输出质量和延迟评估；本地未配模型时已验证 fail closed。
- 企微已完成消息接管、补问、READY 异步只读调查、幂等 Diagnosis、原路纯文本业务摘要与 Web 深链，
  以及“关闭且 outcome 已登记”后持久化原路 @ 通知；尚未完成的只是需单独平台评审的
  出站交互卡片（继续扩平台现有 `channel/wecom`，见 v4 §7.4 / D17）。
- Scenario Playbook Registry 与 DiscoveryPolicy。
- 双投影已能直接消费 Diagnosis 内既有 canonical evidence：`log_count` 产出带引用的事件量说明，
  `trace` 只作为部分异常 hop，`log_trace_bundle + contrast_sample` 可复算为有界调用链和成功样本对照；
  不新增表或第二份事实。
- 在线 Diagnosis 尚未稳定保存完整 `log_trace_bundle`、`contrast_sample`，真 Guance 也尚未稳定产出
  可复算的 `incident_impact` 人数/BlastRadius；
  缺失时继续返回 null/UNKNOWN。1.3/1.4 旧记录也不回填伪造的 D14 数据。

## 5. Demo

开发环境路由只在 Vite dev 模式存在，不影响生产构建和真实 `/troubleshooting` 权限：

- 合并页（开发证据原地展开）：`.../prototype/troubleshooting?view=INLINE`
- 分屏（业务/开发切换）：`.../prototype/troubleshooting?view=SPLIT`
- 企微独立 UI 投影（**原型暂缓**）：`.../prototype/troubleshooting?view=WECOM`

**不启服务也能演示**：`docs/intelligent-troubleshooting/experience-prototype-demo.html`
（Vue 组件的静态镜像，双击即开；Vue 仍是实现权威，两者一并删除）。

**已选定（2026-07-28）**：集中兵力做**服务经理摘要 + 开发证据台**，业务摘要默认展开、
开发证据默认折叠；企微独立 UI 投影原型保留结构但不再投入。这不表示通道 P3 暂缓：
P3 T9 与 T10 纯文本闭环已落地，含 leader 切换后的 DB 路由回源、平台 ACK 交付、
关闭 outcome 持久化原路 @ 通知与正式页最终处置卡；交互卡片仍单独暂缓。
两个投影的类型化合同见 `projection-contracts.md`。

**正式入口已吸收（2026-07-29）**：

- 正式真实数据工作台：`http://127.0.0.1:5173/troubleshooting`
- 正式 Playbook 管理与无错误码证据预览：`http://127.0.0.1:5173/troubleshooting/sops`
- 旧版兼容处置台：`http://127.0.0.1:5173/troubleshooting/legacy`
- dev-only 原型暂时保留用于降级结局对照；正式页补齐等价测试场景后再按删除清单移除。

**原型的三个轴**：

- `view` = 开发证据怎么进：`INLINE`（原地折叠展开）/ `SPLIT`（独立视图切换）——两者渲染同一份投影
- `outcome` = 系统最终能说什么：`HYPOTHESIS` / `EXCLUDED` / `INSUFFICIENT` / `SOURCE_DOWN`
- `authority` = 这条路径凭什么被选中：`EXPLICIT` / `RULE_MATCHED` / `MODEL_PROPOSED`（置信上限随之变化）

只演 happy path 的原型没有区分度——**「查不出来」才是这套系统最常产出的结局**，
三种降级结局（弃权 / 排除 / 源故障）现在都能在同一版式下看到。

原型文件（评审完与静态镜像一并删除）：

- `mateclaw-ui/src/views/Troubleshooting/prototype/TroubleshootingExperiencePrototype.vue`
- `mateclaw-ui/src/views/Troubleshooting/prototype/DeveloperEvidencePanel.vue`
- `/prototype/troubleshooting` 是 dev-only publicPrototype 路由（`import.meta.env.DEV` 条件注册），
  生产构建不含该分支；正式 `/troubleshooting` 鉴权和 capability gate 未放宽。

## 6. P1 已收口，下一门是 P2 真实证据

```text
SopSynthesisService.preview()              已完成
  → contrast_sample                        已完成，缺失只降级
  → PlaybookDraftInducer                   已完成，最多一个模型调用
  → PlaybookDraftValidator                 已完成，确定性信任边界
  → ReferenceSolutionComparator            已完成，纯结构比较
  → candidate + generationKey              已完成，不可 approved

四个北极星时间戳                        已完成，合成与在线 Diagnosis 1.5 均记录
```

P1 本身只深化了 synthesis/evidence seam；P1 收口后已单独启动 T15 正式页面吸收并实现双投影。
READY 异步交接采用领域表 + 租约 worker，没有引入消息中间件。仍未创建独立 Planning 实现、
第二条 WeCom 入站、Loop Controller、Challenger 或第二运行时；已实现的 Router pre-route、
IntakeSession 状态机和 READY 调查任务不得再写成“未创建”。

验收案例必须是“会话消息发送失败（无 error_code）”。比较采用 requiredStepIntents、forbiddenStepIntents、
orderingConstraints、requiredEvidenceKinds，不做逐字相似度。

## 7. 安全与信任边界

**唯一权威清单：`rfcs/intelligent-troubleshooting-architecture-v4.md` §9。**
本文不再复述条目——同一批约束此前在 v4 §1.2、v4 §9、本文和 TODO 各写一遍且互不一致
（见 `architecture-critique-v4.md` §2.5）。动手前读 v4 §9；要改红线也只改那里。

## 8. 验证现状

P1 后端的可复现结果和 HTTP 响应见 `p1-verification.md`。当前已确认：

- 固定 Replay Eval 正例可创建/复用 candidate，危险负例在入库前拒绝。
- Spring 上下文启动并将本地 H2 迁移到 V174。
- 本地 HTTP preview 返回 `READY_FOR_MODEL`，对照差值 `0.89`。
- 本地无模型配置时 generate 返回 `MODEL_REJECTED / MODEL_UNAVAILABLE`，`candidate=null`。
- 真实 Guance 与真实模型效果未验证，不得将 Recorded Replay 结果等同生产成功。

本轮三套原型已通过：

- `vue-tsc --noEmit`
- 直接 Vite production build
- 浏览器 A/B/C DOM 与视觉冒烟

注意：`npm run build` 的前置脚本引用缺失的 `../scripts/check-snowflake-precision.sh`，因此 wrapper 会在执行
Vite 之前失败；直接 `vue-tsc` 和 Vite build 均通过。这是仓库已有构建脚本缺口，不是原型代码错误。

正式双投影纵切（2026-07-29）已通过：

- 排障域后端全量 `214` 个测试，0 failure / 0 error / 0 skipped；其中覆盖 Diagnosis 1.5、
  D14 请求前置计时、ISO-8601 Duration HTTP 合同、`EXCLUDED`/`UNEVALUATED` 分离及首次人工接管；
- 前端全量 `114` 个测试、`vue-tsc --noEmit` 与直接 Vite production build；
- Spring 上下文已用当前工作树真实重启成功，`127.0.0.1:18088` 监听；正式页和旧版页均返回 200；
- 登录态浏览器创建 Diagnosis 1.5 演练记录
  `diag-aa2e3a4ddea94c94b3f93986d87de6ce`：`reportedAt → readyAt → conclusionAt` 返回真实时间，
  两段亚秒耗时显示 `<1秒`，首次“确认结论”后 `handoffAt` 与 `adoptCost=2分55秒` 写回；
- 重启到最终代码后创建缺字段演练 `diag-bc311517817c4908b76477ff7fb1e945`，结果为
  `INSUFFICIENT_EVIDENCE / NEEDS_INVESTIGATION / LOW`，证明缺失字段不会被误升为 `EXCLUDED`；
- 正式页默认只展开 `BusinessSummary`；路由、调用链、对照、知识草稿、判据与能力边界只在开发证据台展开；
  Recorded Replay 边界、旧版同 `diagnosisId` 跳转均通过，正式页控制台 0 error，仅剩平台既有 intlify warning。
- 投影测试已覆盖已有 evidence 的渐进能力：903001 的 `trace` 只显示一个明确的部分异常 hop；
  完整 `log_trace_bundle` 显示有界三跳链路；`contrast_sample` 显示失败 92% 对成功 3%，
  `log_count=148` 只描述事件量并明确不等于 148 名客户/用户。
- 运行态复验 `diag-e0c5b51e77544d278e0dd30ad2b25d7c`：Diagnosis 聚合往返后被全局
  Long→String 精度保护写成十进制字符串的时间戳/时延/计数仍可严格复算；正式页显示
  `order-api → order-service → mongo-primary`、`未记录 / 42 ms / 3001 ms` 与 92% 对 3% 的对照，
  所有排障接口 200、控制台 0 error。指数、小数、空格、前导零和 long 越界字符串继续 fail closed。

Diagnosis 1.6 结构化影响纵切（2026-07-29）已通过：

- 排障域 + Skill Manifest 后端全量 `249` 个测试，0 failure / 0 error / 0 skipped；覆盖字符串兼容、
  canonical schema、精确人数观测时间、非影响引用、互相矛盾引用、Intake 脱敏和正式投影降级；
- 前端全量 `114` 个测试、`vue-tsc --noEmit` 与直接 Vite production build；未知客户数/用户数不再渲染为 0；
- 双轴 code review 最终无剩余 P0/P1/P2；后端以最终工作树重启并监听 `18088`，编译态合同版本为 `1.6`，
  `http://127.0.0.1:5173/troubleshooting` 返回 200 且 Vite 已提供本轮最新模块。

P3 T9 IntakeSession 首段（2026-07-29）已通过：

- 排障域 + Skill Manifest + Channel pre-route/provider-time 共 `279` 个后端测试，
  0 failure / 0 error / 0 skipped；覆盖 source-message 幂等、相等时间戳拒绝覆盖、
  A 已 READY/B 已打开时 A 的迟到事件仍归 A、企微秒/毫秒 `send_time` 与异常回退、原通道路由写入；
- H2 真实启动先由 v175 迁移至 v176，再应用 v177 的真实首条时间回填与非空约束；最终进程 PID `95174`
  监听 `18088`；正式页、旧版页均返回 200，后端 health 返回预期的未登录 401；
- v0.14 `MANIFEST.sha256` 全量校验通过，三张 Draw.io/SVG XML 通过，当前 RFC/蓝图/投影合同与
  v0.14 快照逐字一致；三张图继续与 v0.13 二进制一致（本轮只修实现语义，没有伪造新图形版本）。

P3 T10 前半段与可靠投递收口（2026-07-29）已通过：

- 排障域 + Skill Manifest + Channel pre-route/provider-time/leader-route 共 `317` 个后端测试，
  0 failure / 0 error / 0 skipped；覆盖 READY 原子入队、历史 READY 补偿、租约抢占、Diagnosis 唯一归属、
  平台 ACK、通知重试复用、第五次宕机后按 Intake 恢复 Diagnosis、leader 路由 DB 回源、BusinessSummary
  纯文本边界、正式深链和无 Diagnosis 时的 fail-closed 回复；
- H2/MySQL/Kingbase V178 新增 Intake 调查任务与 Diagnosis 来源唯一约束，V179 分离精确投递路由并新增
  持久终态投递计数；本地 H2 已由 v178 真实迁移至 v179，当前 Java PID `13423` 监听 `18088`；
- 正式页、带 `diagnosisId` 的深链、旧版页与后端 health 均返回 200；本地后端显式配置
  `MATECLAW_TROUBLESHOOTING_WORKBENCH_BASE_URL=http://127.0.0.1:5173`；
- Standards / Spec 双轴最终复核均要求关闭第五次恢复误报、leader 路由缓存断层和会话 key 重复规则；
  修复后两轴均 PASS，无剩余 P0/P1/P2；
- v0.15 `MANIFEST.sha256` 全量校验通过，三张 Draw.io/SVG XML 通过，当前
  RFC/蓝图/投影合同/评价与快照逐字一致；三张图继续与 v0.14 二进制一致。

P3 T10 关闭结果通知与正式页闭环（2026-07-29）已通过：

- H2/MySQL/Kingbase V180 新增 Diagnosis 关闭通知状态、租约、退避与完成时间；本地 H2 已真实
  由 v179 迁移到 v180，当前 Java PID `28131` 监听 `18088`；
- 排障域 + Skill Manifest + Channel pre-route/provider-time/leader-route 共 `340` 个后端测试，
  0 failure / 0 error / 0 skipped；覆盖关闭事务排队、直接 Web/API 不适用、租约 CAS、平台 ACK、
  无硬重试上限、leader 不可用时不烧任务、纯文本类型化结果、fixture/能力边界/深链保留，
  恶意 reporter/正文不伪造 @ 或泄露、结案业务文本安全与硬预算，以及重启/重连后 reply context
  失效时不误发；调度条件、重试时间、租约抢占和 worker 所有权另由真实 H2 mapper SQL/CAS 覆盖；
- 前端 `14` 个测试文件 / `115` 个测试全通过，`vue-tsc --noEmit` 通过，直接 Vite 生产构建
  完成 `6266` 个模块转换；
- 正式 `/troubleshooting`、已关闭 Diagnosis 深链、`/troubleshooting/legacy` 与后端 health 均返回 200；
  应用内浏览器实测“最终处置结果 / 已恢复 / 人工验证时间”可见，控制台 0 error；
- v0.16 继续冻结 v0.15 的三张图与生成源，本版只校准已验证的实现状态，不伪造新架构语义。

P2 真源门与单次只读验证（2026-07-29）已通过代码级验证：

- 排障域 + Skill Manifest 后端共 `317` 个测试，0 failure / 0 error / 0 skipped；
  其中 Guance Adapter/Router/自动配置/就绪/验证/API 定向共 `40` 个测试。
- 新测试覆盖未授权时不读 API Key且零 transport 调用、重复资产作用域 fail closed、
  归一化后重复的 source route fail closed、secret 形态 binding 引用不出投影、超大窗口与越界
  `occurredAt` 稳定返回 400、Guance-only 两步调用、同一 PS ID 一致性、Guance 无结果时绝不回退 Replay，
  以及原始日志/DQL/凭据/搜索键/窗口不进报告。
- 前端 `14` 个测试文件 / `116` 个测试全通过，`vue-tsc --noEmit` 与直接 Vite 生产构建通过；
  `npm run build` 仍被仓库已有的缺失前置脚本拦住。
- 本轮未修改 RFC、蓝图或三张图：真源门是已定 P2/T7 实施接缝，没有新增架构语义；
  当前 v0.16 继续有效。真实 T7/T8 依然未完成。

正式无错误码 Evidence Spine 入口（2026-07-29）已通过：

- 前端 `15` 个测试文件 / `119` 个测试全通过，`vue-tsc --noEmit` 通过，直接 Vite 生产构建
  完成 `6270` 个模块转换；后端 synthesis Replay / service / controller 定向 `14` 个测试全通过。
- 登录态浏览器从正式 `/troubleshooting/sops` 打开“无错误码证据预览”并完成会议案例回放；页面显示
  4 条日志命中、同一 PS ID 三段调用链、92%↔3% 成功样本对照与 `+89` 个百分点差异，控制台 0 error。
- 本地运行时只为验证显式启用了 Recorded Replay；默认配置仍为关闭。本次预览交互与 API 均未调用模型、
  创建 candidate、执行审核/晋升或扩大任何生产写边界；T7/T8 状态不变。

后端定向测试命令：

```bash
mvn -pl mateclaw-server -am \
  -Dtest='vip.mate.troubleshooting.**.*Test' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

## 9. 接手顺序

1. 先读 `recording-product-baseline.md`、架构 v4、架构评审、TODO。
2. 信息结构**已选定并已进入正式路由**（服务经理 + 开发两个投影；企微独立 UI 原型暂缓，
   P3 T9 与 T10 纯文本闭环已进入真实通道接缝），合同见
   `projection-contracts.md`；D14 已进 Diagnosis 1.5，投影也已能消费既有 canonical hop/对照；
   下一步是让真实在线取证稳定产出这些事实，而不是再造一套展示数据。
3. P1 T1→T5（含 T4.5）已完成；修改 prompt/model/schema 必须重跑固定 Replay Eval。
4. P3 纯文本闭环已收口；交互卡片需单独平台评审，不阻塞 P2 真实数据验证。不新建入站，
   不把 BusinessSummary 伪装成 tool-guard ApprovalNotice。
5. P2 T6 授权机制和真源验证接缝已完成；下一主攻是由 owner 在运行时配置真实资产映射，
   用正式工作台的“P2 真源门”跑通首个会议案例，再完成 T7 字段核实和 20–30 条 T8 影子样本。
6. 真实样本稳定后再实现 Scenario Registry/Planning；不要先搭空平台。

## 10. 不要做

- 不再引用已确认属于其他项目的旧架构材料，后续只使用 MateClaw。
- 不把 v2/v3 或下载目录里的旧蓝图当现行设计。
- 不把五类 FaultClass 写成录音已定要求。
- 不让模型猜 error code 后进入 deterministic route。
- 不把内部思维链展示给开发，只展示证据、判据和可复算推导。
- 不擅自开 PR，不提交包含真实 token/IP/人名的源表。
