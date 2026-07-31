# HANDOFF · IT 智能排障 on MateClaw

> 更新时间：2026-07-31
>
> 仓库：`webonne/mateclaw`
>
> 分支：`claude/intelligent-troubleshooting-design`
>
> 当前架构：`rfcs/intelligent-troubleshooting-architecture-v4.md`
>
> 架构评审：**APPROVED FOR P1 IMPLEMENTATION**
>
> 第一性原理评价与修订：`architecture-critique-v4.md` —— 用户已认可，v4 现为 **v4.4 / 蓝图 v0.18**

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
| **D18** | 部署拓扑快照是 Workspace 资产，`deployment_topology_probe` 是 Diagnosis 内场景，`topology_synthetic_probe` 是可插拔只读 Tool，Guance CloudDial 只是首个 Adapter；安全结果回到同一 Evidence Spine，不建第二套诊断链路 |

修改 D4、D5/D5′、D9 必须单独 RFC 并由用户明确确认。
D12/D13 当前为 `PENDING-EVIDENCE`：在 P2 真实样本给出失败模式之前，不得据其新增实现。

**红线不在本文维护。** 唯一权威清单是 v4 §9；本文与 TODO 只引用，不复述条目
（此前四处各写一遍且条数措辞不一，见 `architecture-critique-v4.md` §2.5）。

蓝图已升级到 v0.18：v0.12 锁定通道复用，v0.13 校准正式工作台与双投影，
v0.14 校正企微普通消息入站接缝与身份边界，v0.15 记录 P3 T10 前半段的持久化异步调查、
幂等 Diagnosis、纯文本 BusinessSummary 与正式工作台深链，v0.16 记录 Diagnosis 关闭 outcome 的
持久化原路 @ 通知与正式工作台最终处置卡；v0.17 冻结部署拓扑独立结果的中间态，v0.18 将其修正为
Workspace 资产 + Diagnosis 场景 + 可插拔只读 Tool + 来源 Adapter + 同一证据详情。这些版本均不扩大
P1：P2 才在历史样本上影子运行 Evidence Challenger /
Safety Challenger，P4 才为 SCENARIO / OPEN_DISCOVERY 引入 Loop Control。

## 4. 当前代码真实状态

### 已完成

- Java 领域模块 `vip.mate.troubleshooting`、REST、RBAC、三方言 Flyway、状态机和持久化。
- 903001 确定性错误码竖线，命中路零 LLM。
- 受限 Agent miss-path：唯一只读证据工具、服务端会话、硬白名单、引用校验、abstain。
- **正式 Web Incident Intake（2026-07-29）**：`/troubleshooting` 已提供
  `operate:troubleshooting` 权限内的“上报事件”，直接复用既有 Incident API 与同一 Diagnosis 队列；
  旧 `/troubleshooting/legacy` 保留。表单只暴露 system/service/现象/严重级别、可选错误码与 Trace
  安全标识，默认演练；不允许调用方填写原始日志、DQL、凭据、影响人数、evidence、incidentId 或
  occurredAt。Intake 会在任何路由、持久化或模型调用前再次拒绝 Incident 字段中的 DQL、原始日志和
  堆栈正文。错误码命中仍零 LLM，未命中路径未启用时保留表单并明确 fail closed；非演练无码事件也以
  规范化 system/service/symptom/trace 建立五分钟稳定键，不再因缺 errorCode 绕过去重。
- `EvidenceSourceRouter`，Guance 与 Recorded Replay 两个 Adapter，canonical schema 和脱敏。
- **P2 T6 租户授权边界（2026-07-29）**：`workspaceId` 已贯穿 Intake、Agent 会话、SOP 合成、
  Router 和 Adapter；Guance 必须由唯一的 `workspace/system/service + signalKind → concrete binding`
  映射显式放行，映射缺失或歧义时在使用 API Key、发 HTTP 前 fail closed。
- **CSP CloudDial 试点绑定（2026-07-30）**：已为
  `${MATECLAW_TROUBLESHOOTING_CSP_WORKSPACE_ID} / csp-deployment / csp-prm-miniapp / synthetic_probe`
  新增默认不激活的 `csp-clouddial-pilot` Profile，只在操作员提供必填 workspace ID 后加载唯一资产授权，
  并按部署快照绑定 `D::http_dial_testing` 任务
  `客服数字化平台-首页-可用性监控`。Guance 仍默认关闭，API Key 仍只允许从环境注入，
  明文 HTTP 默认 fail closed；仅本地进程可在操作员明确授权后临时开启，正式部署仍必须关闭并迁移到
  HTTPS/受控 TLS 代理。尚未由自动化真实调用，不代表 T7/T8 通过。
- **部署拓扑拨测场景真实触发入口（2026-07-30）**：正式 `/troubleshooting` 以面向用户的
  “部署拓扑拨测分析”承载 `deployment_topology_probe` 场景 Playbook，
  管理员可从 Workspace 共享拓扑图库选择既有资产，也可导入新的
  `chain-board.runtime-topology-snapshot`。V187 只保存通过 512 KiB、节点/链路/拨测数量、凭据形态和
  URL 元数据校验的不可变快照及导入人/时间；同快照幂等复用，同名不同内容拒绝覆盖，最多 100 份，
  其他 Workspace 不可见。页面内提供服务端校验过的下载案例和三步导入说明。选定资产后调用
  `POST /api/v1/troubleshooting/sops/deployment-topology/topologies/{topologyId}/analyze`。服务端有界解析所有节点，仅对同时具有
  `url + guance_url` 的节点经现有 `EvidenceSourceRouter` 执行 Guance-only `synthetic_probe`；上传的
  `guance_url` 只提供任务身份/时间窗，不能控制 API 主机或 DQL。真实 CloudDial Explorer 链接携带的
  `lak / activeName / cols / viewType` 只按已知展示参数校验后忽略，不参与执行、指纹或持久化；`dql` 等未知参数仍拒绝。
  当前样例为 21 节点、27 链路、1 个可执行
  拨测。最多 32 个可执行拨测以 8 路并发共享 25 秒总预算，超时节点降级为 `UNAVAILABLE`，已完成结果保留。
  独立兼容接口仍不调模型、不落库；正式 Diagnosis 场景入口只持久化脱敏后的安全结果投影，不返回或落库
  原始响应/DQL/凭据。未覆盖节点不宣称健康，失败节点相邻链路只作核查提示。
- **能力命名与场景入口统一（2026-07-30，2026-07-31 补齐 Diagnosis 前置创建）**：正式工作台主按钮统一为“发起排障”，先选择
  “通用事件排障”或“部署拓扑拨测分析”；前者复用 Incident API 创建 Diagnosis，后者由服务端先创建或复用
  专属的 `SCENARIO_PLAYBOOK + EXPLICIT` Diagnosis，再通过 `topology_synthetic_probe` 只读工具运行；安全结果写入 V188 不可变运行记录并在同一
  排障详情展示。部署拓扑入口已从“更多能力”移出；该菜单只保留
  “排障规则库 / 无码场景预演 / 观测云接入与验收 / 诊断效果评估”四个低频治理与校准入口。内部
  Playbook、P2、T7、T8 合同名称不变，改动只作用于用户界面信息架构。
- **部署拓扑场景 Diagnosis 门禁（2026-07-31）**：新增
  `POST /api/v1/troubleshooting/scenarios/deployment-topology/diagnoses`，仅接收脱敏业务上下文；
  `scenarioKey/toolKey/selector/PlaybookRef` 均由服务端持有。创建事务锁定当前 active-approved 版本，
  同时核对 selector、operational 状态、SOP 身份及冻结 EvidenceRequest 中的
  `synthetic_probe + deployment_topology + topology_synthetic_probe`；任一不匹配即 409，不创建弱权威 Diagnosis。
  场景幂等键独立于普通事件和其他场景；创建成功但详情/能力投影加载失败时，前端明确提示
  “Diagnosis 已创建”，不会误导用户重复提交。该增量不调模型、不执行拨测、不扩大生产写权限。
- **P2 真源验证接缝（2026-07-29）**：新增 workspace/system/service 级的秘密无关就绪投影，
  只在精确资产与两个核心信号绑定均通过后检查凭据是否存在；未授权时连 API Key 都不读取。
  管理员可从正式工作台的“P2 真源门”触发 Guance-only
  `log_search → log_trace_bundle`；Router 先限定允许源，因此不会回退 Replay。报告仅含匹配数、
  PS ID、trace 节点数、绑定引用与时间戳，不含原始日志、DQL 或凭据，且明确不关闭 `fixtureMode`。
- **P2 真源接入向导（2026-07-30）**：正式队列新增“P2 真源接入”。向导不依赖先存在匹配
  Diagnosis，可编辑安全的 system/service/search key，生成不含凭据、不会自动落库的外部配置骨架，
  并复用既有 readiness/acceptance API 展示 T6→T7→T8。只有既有 readiness 门就绪才可进入原 T7
  只读验收；向导临时验证结果与当前 Diagnosis 侧栏状态隔离，非当前作用域也不会出现进入 T8 台账入口。
  异步结果必须继续匹配同一对话框 session、发起 origin 与完整 lookup identity，关闭/重开不能借用旧响应。
- **P2 真实 Evidence Spine 预览（2026-07-29）**：同一正式工作台现可继续触发
  Guance-only `log_search → log_trace_bundle → contrast_sample → deterministic compress`。
  它直接复用在线 Diagnosis 和 SOP 学习共享的 `EvidenceSpineOrchestrator`，只投影有界调用链、
  异常数、对照比率、结构化引用与应用侧总耗时；不返回原始行/日志正文/DQL，不调模型、
  不创建 candidate、不回退 Replay。对照缺失只降级为 `CORE_CHAIN_OBSERVED`；单条预览不代表
  T7/T8 已通过，也不会自动关闭 `fixtureMode`。
- **P2 T7 owner 验收接缝（2026-07-29）**：V184 为当前
  `workspace/system/service + Guance binding fingerprint` 保存不可变、秘密无关的 owner 验收。
  只有 Workspace owner 可提交，并必须逐项确认 measurement/字段、索引、同 PS ID、时间单位/窗口、DQL 延迟与 903001 历史冲突；
  服务端随后再次执行 Guance-only 两步读链。配置指纹覆盖端点、路由、查询模板、行数预算与字段映射，
  不含运行时凭据；变化后旧验收自动 `STALE`。记录只含结构计数、PS ID 哈希、应用侧耗时、actor/时间，
  不含搜索键、PS ID 原文、DQL、凭据或日志。Guance T8 采集和基线复跑都在任何 Router 调用前强制要求
  当前指纹已验收；默认环境仍无真实验收记录，因此 T7/T8 状态不变。
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
- **正式 Knowledge Review Inbox（2026-07-29）**：`GET /api/v1/troubleshooting/sops/review-inbox`
  按 workspace 统一读取证据生成、关闭结果沉淀和人工注册三类真实候选；正式
  `/troubleshooting/sops` 可按来源筛选并查看状态、资格缺口、证据引用、模型来源、参考解法和关闭结果。
  三类来源共用 V185 独立审核台账：无记录为 `CANDIDATE/v0`，登录管理员可开始审阅为
  `IN_REVIEW/v1`，再按精确版本拒绝为 `REJECTED/v2`。V186 在开始审阅时同时冻结 selector 的旧权威
  baseline；V185 已在途的 `IN_REVIEW` 记录由迁移冻结当时 baseline，不会因 source 唯一键永久卡死。
  批准命令重读当前资格与 server-owned routeable material，永远创建不可变的新 Playbook
  版本，替代或显式退役会同步把旧 review 推进到 `DEPRECATED`。审核台账保存服务端登录主体、理由与
  开始时的 validation/reference/model/fixture 快照；reason 拒绝凭据、DQL、原始日志和堆栈。Inbox 还为每个精确
  来源返回服务端当前资格投影：证据型显式处于默认 `CALIBRATION` 档并核对
  validation/reference/citation/fixture，candidate 生成本身不计作正例回放；人工型对完整 SOP 合同执行
  evidence request→criterion→rule 交叉引用校验；关闭型逐项暴露服务端可证明事实与缺口，前端不再自行拼资格原因。旧式
  candidate → approved 通用按钮继续关闭；旧 `POST /sops/{system}/{errorCode}/status` 也已 fail closed
  拒绝 `approved`，且不能退役 V186 版本化权威。有 review 的版本必须从原审核记录提交精确 review version
  与 reason；V186 回填且没有 review 的 LEGACY 权威另有精确 playbookVersion + 服务端 actor/reason 的
  审计退役命令。MANUAL source 现在以 `sopId` 唯一，不同不可变 source 可共享 selector，用于首版后的
  人工替代；H2/MySQL/Kingbase 的 nullable `active_selector_key` 唯一约束继续保证每个 selector 最多一个
  active approved。正式命中路径只返回 operational 权威，最新版本为 `DEPRECATED` 时直接 route miss，
  不回落复活 legacy 行；治理详情独立读取最新历史版本。Diagnosis 1.7 现冻结来源 Playbook owner，
  `knowledge-candidate.v2` 与关闭事务同时冻结 outcome、恢复验证、actor 和时间；历史 v1 候选继续显示
  `OUTCOME_VERIFICATION_NOT_PROJECTED / OWNER_REQUIRED`。2026-07-31 起，部署拓扑 selector 的 `MANUAL`
  候选可运行服务端固定正例、健康反例和缺证据弃权例；证明与精确候选/套件双指纹绑定，通过后才消除回放
  缺口，仍须人工审阅和批准。数据驱动的 `RUNTIME` 档切换以及 `EVIDENCE_DERIVED / OUTCOME_BACKED`
  的精确候选回放尚未接入 Gate，继续 fail closed；MANUAL fixture 通过不等于 T7/T8 已通过。
- H2/MySQL/Kingbase V174 candidate 表，generation key 按 workspace 唯一；四个北极星时间戳与三段成本已入合同。
- 固定 Replay Eval 已组合真实 Replay/Router/压缩/结构化解析/Validator/参考比较/Store；
  正例创建并幂等复用，危险输出在入库前被拒绝。
- Diagnosis 人工处置闭环与 Vue 工作台。
- **正式双投影纵切（2026-07-29）**：新增服务端
  `DiagnosisExperienceProjection` / `DiagnosisExperienceProjectionService` 与
  `GET /api/v1/troubleshooting/diagnoses/{id}/projection`；同一 Diagnosis 生成
  `BusinessSummary` 和 `DeveloperEvidenceView`，构造器落实结论置信、精确人数证据引用等不变量。
- **正式路由已吸收选定信息结构**：`/troubleshooting` 读取队列、完整 Diagnosis 与双投影真实 API；
  无查询参数时默认进入全宽传统列表，用户可切换到紧凑队列；点击列表记录进入独立全宽
  `view=detail` 详情且不保留队列侧栏；主动进入 `view=queue`，或使用无 `view`、只携带
  `diagnosisId` 的历史兼容深链时，显示紧凑队列并直接打开对应处置详情。业务摘要默认展开、开发证据
  默认折叠，并保留确认、转派、批准不执行、登记外部结果和关闭能力；四个低频治理与校准入口收进
  “更多能力”菜单，部署拓扑分析则进入“发起排障”的场景选择。原工作台临时迁到
  `/troubleshooting/legacy`，携同一个 `diagnosisId` 可直接回退。
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
- KnowledgeCandidate 与 Outbox 继续只表达发布语义；Review Inbox 的开始审阅、拒绝、批准、替代与退役
  已使用独立审核语义和乐观版本，批准不会原地修改 candidate。
- 三套只读 Demo 原型，均显式显示 Recorded Replay、MODEL_PROPOSED、MEDIUM、CANDIDATE。

### 尚未完成

- 除 CSP CloudDial 试点外，其他真实 Guance 资产授权值尚未由 owner 配置；试点的
  measurement/字段/返回结构也尚未用新密钥完成内网验证，其他场景的 PS ID/阈值同样未验证；
  `fixtureMode` 仍应为 true。现在有可操作的单次验证入口，但没有 owner 验收和真实返回，
  不得将“入口已实现”改写为“T7 已通过”。
- V184 已把 T7 owner 决策做成可留痕且配置变化自动失效的门禁，但本地没有真实 Guance 返回，
  当前不存在 `ACCEPTED` 记录；这仍是“验收装置已实现”，不是“owner 已验收”。
- 真实模型的输出质量和延迟数据仍未取得；V182 已提供固定输入/固定模型版本的单 Agent 基线运行与
  结构化质量/Token/时延记录，本地未配模型时继续 fail closed，不能把“可运行”写成“已评估”。
- 企微已完成消息接管、补问、READY 异步只读调查、幂等 Diagnosis、原路纯文本业务摘要与 Web 深链，
  以及“关闭且 outcome 已登记”后持久化原路 @ 通知；尚未完成的只是需单独平台评审的
  出站交互卡片（继续扩平台现有 `channel/wecom`，见 v4 §7.4 / D17）。
- 完整持久化 Scenario Playbook Registry 与 DiscoveryPolicy 尚未完成；当前只落了会议正例
  `message_send_failed` 的配置型 approved Evidence Spine 目录，用于先锁住 server-owned plan 边界。
- 双投影已能直接消费 Diagnosis 内既有 canonical evidence：`log_count` 产出带引用的事件量说明，
  `trace` 只作为部分异常 hop，`log_trace_bundle + contrast_sample` 可复算为有界调用链和成功样本对照；
  不新增表或第二份事实。
- **在线 Evidence Spine 已收口（2026-07-29）**：Agent 只能提交 workspace/system 可见的注册
  `scenario_key`；服务端从 `ApprovedEvidenceSpineCatalog` 解析搜索词、窗口和 Adapter 白名单，再固定执行
  `log_search → log_trace_bundle → contrast_sample`，与合成预览共用唯一
  `EvidenceSpineOrchestrator` 和既有 Router/Adapter。三次源调用先整体占用预算，完整 canonical evidence
  进入同一个 Diagnosis；初始 supplied evidence 和工具结果共用模型安全投影，只返回证据引用、白名单标量与
  去掉 query/entries/日志正文的确定性 trace 骨架，并拒绝直接请求其他 signal kind。计划预检失败会粘滞记录，
  核心 trace 缺失由服务端强制 abstain；对照不可用时保存显式 `MISSING`、不阻断核心链路，正式页明确显示
  “已采集但来源不可用”，不再误写成“尚未保存”。
- 真 Guance 尚未稳定产出可复算的 `incident_impact` 人数/BlastRadius；缺失时继续返回 null/UNKNOWN。
  1.3/1.4 旧记录也不回填伪造的 D14 数据。

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

正式工作台无码证据深链（2026-07-30）已通过：

- `/troubleshooting` 队列直接提供“无码证据预览”；点击后精确进入
  `/troubleshooting/sops?focus=evidence-synthesis`，并自动打开已有 `SynthesisPreviewDialog`。
- 该深链复用同一 synthesis preview API 和 Evidence Spine，不新建第二页、第二 API、
  Scenario 运行时或 candidate 通道；弹窗仍明示“不调用模型、不创建 candidate”。
- 前端 `19` 个测试文件 / `143` 个测试、`vue-tsc --noEmit`、直接 Vite 生产构建与
  `git diff --check` 全通过。登录态 Playwright 验收确认正式深链、自动弹窗和
  `/troubleshooting/legacy` 都正常，正式页、治理页与 legacy 页均为 `0` console error。
- 本增量只提升已实现 P1 证据能力在正式产品中的可发现性；真实 T7/T8、
  无码 Scenario/Open Discovery 和 Challenger/Loop 状态不变。

正式工作台 P2 真源接入向导（2026-07-30）已通过：

- 正式 `/troubleshooting` 队列可打开独立向导；浏览器分别验证当前 Diagnosis 的
  `CSDP/order-svc` 和独立会议作用域 `CSDP/csdp-session-service`。配置骨架只含精确 workspace ID、
  `log_search` / `log_trace_bundle` 占位符与 secret-manager 环境变量占位，不接收或显示真实凭据。
- 修改 system/service 后旧 readiness 立即失效，必须显式重新检查；本地默认 Guance 关闭时，T6/T7/T8
  依真实服务端状态全部阻断，“进入 T7 只读验收”保持禁用，没有用前端状态伪造准入。
- 前端 `20` 个测试文件 / `150` 个测试、`vue-tsc --noEmit`、定向 ESLint 与直接 Vite 生产构建通过，
  构建完成 `6281` 个模块转换；仓库既有 `npm run build` 仍因缺失
  `../scripts/check-snowflake-precision.sh` 前置脚本失败，本增量未扩大范围修改该基础设施。
- 登录态 Playwright 验收中正式页为 `0` console error；legacy 页面仍能读取同一 Diagnosis，但该存量
  v1.4 记录调用 `/derivation` 返回既有 `409 Conflict`，因此本轮不宣称 legacy 为零错误。
- 本增量没有新增后端 API、领域表、Guance transport、Scenario 运行时、candidate 或生产写能力；
  真实资产 binding、T7 owner 核实与 T8 20–30 条样本仍待内网 owner 完成。

在线 Diagnosis 共享 Evidence Spine（2026-07-29）已通过代码级验证：

- 后端排障域 + Skill Manifest 共 `332` 个测试全通过；新增覆盖同一编排器的三段依赖目标、PS ID 一致性、
  canonical schema、成功样本可选降级、在线 Diagnosis 三条 evidence/citation 持久化，以及 Replay 仅接受
  三个显式 server-owned online alias、未知 alias 继续精确 miss；核心 trace 缺失即使模型试图给结论也会
  被服务端强制降为 `INSUFFICIENT_EVIDENCE`。调用方伪造 server-owned stage ID 会在 Agent 与确定性
  intake 两个入口统一重映射，不能冒充“服务端已执行采集”。
- Agent 只可选择注册 `scenario_key`，不能提供 search term、window、平台、DQL 或其他 signal kind；完整
  EvidencePlan 由服务端 approved 配置解析。会话在调用前整体预留三次 source request，预检失败会粘滞并
  强制 abstain；预算不足时零 Router 调用。初始 supplied evidence 与工具响应统一不含 source query、原始
  `entries` 或日志正文，只含白名单标量、证据引用和确定性 trace 骨架。
- 正式投影会区分“对照从未保存”和“`ONLINE-CONTRAST-SAMPLE` 已保存为 `MISSING`”；后者显示
  `contrastAvailable=false`、来源不可用及证据引用，不再把降级状态写成未采集。
- 正式前端 `15` 个测试文件 / `119` 个测试通过，`vue-tsc --noEmit` 与 Vite 生产构建通过，构建完成
  `6270` 个模块转换；正式、Playbook 与 legacy 路由合同未改。
- 该收口没有解除 `fixtureMode`；当前仅实现一个配置型 approved 场景目录，尚未实现完整持久化
  Scenario Registry/Planning、DiscoveryPolicy、Loop Controller 或 Challenger。真 Guance 影响人数/
  BlastRadius 仍等待 T7 owner 配置与内网样本。

P2 正式准入阶梯与真源耗时证据（2026-07-29）已通过代码级验证：

- 正式工作台的“P2 真源门”现将 T6 唯一资产授权、T7 measurement/字段/同 PS ID 链路验收、
  T8 20–30 条历史样本基线分别投影，按实时 Guance readiness 给出下一步动作；页面不再把 T8
  样本数量误写进 T7，也不会把单次进程内观测伪装成 owner 验收。
- Guance-only `log_search → log_trace_bundle` 验证报告新增每步和端到端的应用侧 round-trip，
  作为后续 T8 取证 p50/p95 的同口径输入；它不宣称是 Guance 服务端 DQL 执行耗时，T7 仍需
  owner 用真实返回字段或观测平台核实。报告边界只包含结构化计数、PS ID、证据引用、时间戳和
  耗时，不包含 DQL、原始日志、搜索键、窗口或凭据。
- 排障域 + Skill Manifest 后端 `332` 个测试、前端 `15` 个测试文件 / `120` 个测试全通过；
  `vue-tsc --noEmit` 与直接 Vite 生产构建通过，构建完成 `6270` 个模块转换。
- Standards / Spec 双轴最终复核均 PASS；审查中发现并修复“分别观测两个核心信号被误写成同
  PS ID 链”和“应用侧 round-trip 被误写成服务端 DQL 执行耗时”两处 P2，最终无剩余 P0/P1/P2。
- 当轮后端已用最终工作树重启并监听 `18088`；正式、Playbook、legacy 三个前端路由
  均返回 200，未登录访问 Guance readiness 返回预期 401，未绕过 Workspace 权限。
- 默认 Guance 适配器、资产授权表与 `fixtureMode` 均未放开；本机使用登录页提供的本地管理员测试账号
  完成验收，没有绕过权限、重置账号或伪造真实 T7/T8 运行结果。Scenario Registry/Planning 继续等待
  真实样本门禁。

正式 Web Incident Intake（2026-07-29）已通过运行验收：

- 登录态浏览器从正式 `/troubleshooting` 上报 rehearsal 事件，真实创建并打开
  `diag-c09f30ab1fa54a5c940dead87203bd90`；队列同步新增记录，服务端在证据不足时诚实返回
  `INSUFFICIENT_EVIDENCE / NEEDS_INVESTIGATION / LOW`，没有伪造“已定位”。
- 无错误码 rehearsal 进入未命中路径时，因受限 Agent 未启用返回预期 409；对话框与输入保留，队列未新增
  伪 Diagnosis，页面明确说明 fail-closed。非演练五分钟幂等未用合成生产记录做浏览器写入，错误码与无码
  两条键生成、无码持久化及危险文本提前 400 均由 `IncidentDeduplicationKeyTest`、Persistence 与 Intake
  回归覆盖。
- 后端 Controller、去重、持久化和 Intake 定向 `39` 个测试通过；排障域 + Skill Manifest 全量
  `335` 个测试通过。前端 `16` 个测试文件 / `126` 个测试通过，`vue-tsc --noEmit` 与直接 Vite
  生产构建通过，构建完成 `6271` 个模块转换。
- 正式、Playbook、legacy 三个前端路由均返回 200；未登录访问后端 Diagnosis 列表返回预期 401。
  当前本地后端 PID `32933` 监听 `18088`，前端 PID `92308` 监听 `5173`；本增量未放开生产写、
  Guance 真源、Recorded Replay 或 fixture 边界。

P2 正式工作台完整 Guance Evidence Spine 预览（2026-07-29）已通过：

- 新增管理员只读入口 `POST /api/v1/troubleshooting/evidence/guance/spine/preview`，固定复用唯一
  `EvidenceSpineOrchestrator` 和 Guance-only 允许源，执行
  `log_search → log_trace_bundle → contrast_sample → deterministic compress`；没有 Replay 回退、
  模型调用、candidate 创建、证据持久化或生产写。
- 返回合同强制固定三步、固定 evidence reference、依赖顺序与 stage 一致；完整阶段的对照比例必须能由
  failure/success 样本计数按六位小数确定性复算。审查发现的一处不变量缺口已补负向测试并关闭；
  Standards / Spec 双轴最终均 PASS，无剩余 P0/P1/P2。
- 排障域 + Skill Manifest 后端 `343` 个测试全通过；前端 `16` 个测试文件 / `126` 个测试全通过，
  `vue-tsc --noEmit` 与改动文件 ESLint 通过；直接 Vite 生产构建完成 `6271` 个模块转换。
  `npm run build` 仍只因本节前文记录的缺失基线前置脚本而在 Vite 前停止。
- 最终工作树后端已重启，PID `92267` 监听 `18088`，前端 PID `92308` 监听 `5173`；正式、Playbook、
  legacy 三个路由均返回 200，新管理员入口未登录访问返回预期 401。
- 登录态浏览器检查正式页、Playbook 与 legacy 均为 0 console error。默认 Guance 仍显示适配器未启用，
  T6/T7/T8 fail closed，`打开真源验收` 保持禁用并明确 `fixtureMode` 不会自动关闭；没有伪造真实
  T7/T8 运行结果。单条预览只是采集真实 T8 样本的工具，下一主攻仍是 owner 配置 T7 真字段并累积
  20–30 条 T8 历史样本。

T8 历史样本台账基础设施（2026-07-29）已实现，真实样本与 Gate 仍未完成：

- 正式 `/troubleshooting` 增加管理员“T8 样本台账”；采集接口服务端重新执行同一 Guance-only
  `EvidenceSpineOrchestrator`，不信任或持久化浏览器预览，不回退 Recorded Replay，不调用模型；
- H2/MySQL/Kingbase V181 新增 workspace 隔离、sample key 幂等和乐观版本冻结；聚合只保存结构化
  Evidence Spine 投影、来源、fixture 分离标记和审计时间，不含搜索键、DQL、凭据、原始行或日志正文；
- 人工参考解只接受有序 required/forbidden intent key；关联 Diagnosis 必须 CLOSED，权威 outcome、
  恢复验证和业务安全摘要由服务端读取。冻结后不可改写，相同重试幂等，不同内容冲突；
- 页面分别展示 Guance/Recorded Replay、Evidence Spine 完整/核心链、参考解状态和关联 fixture
  Diagnosis；`20–30` 只是数量目标，合同与 UI 都没有 `passed` 或 T8 Gate verdict；
- 排障域 + Skill Manifest 后端 `363` 个测试、前端 `17` 个测试文件 / `130` 个测试全通过；
  `vue-tsc --noEmit`、改动文件 ESLint、`git diff --check` 和直接 Vite 生产构建均通过，构建完成
  `6275` 个模块转换；
- 最终工作树后端 PID `16551` 已以 schema V181 启动并监听 `18088`，前端 PID `92308` 监听
  `5173`。登录态浏览器验证正式页、T8 台账和同 Diagnosis 的 legacy 路由均正常；台账为 `0/20`、
  Guance 未就绪时采集按钮禁用，未伪造任何样本。控制台仅有登录/仪表盘既有的 settings 401、
  SSO providers 404、active model 500，没有本轮页面新增错误；
- 默认 Guance binding 仍为空、`fixtureMode` 仍未解除，本地台账当前没有伪造真实样本。下一步仍是
  owner 完成 T7 字段核实后，用该入口采集并冻结 20–30 条历史样本，再实现质量/性能聚合与影子对比。

T8 应用侧计时与分来源描述性统计（2026-07-29）已实现，真实样本与 Gate 仍未完成：

- 唯一 `EvidenceSpineOrchestrator` 现用单调时钟记录 `log_search`、`log_trace_bundle`、
  `contrast_sample` 三次 Router 往返，以及核心/对照两次确定性压缩的合计耗时；这些是 MateClaw
  应用侧墙钟时间，不是 Guance 服务端 DQL 执行时延。外层预览继续记录包含 readiness 开销的
  端到端总耗时，并拒绝“总耗时小于已测工作量”的矛盾投影；
- 安全计时投影随 T8 样本聚合保存，不新增表或迁移。V181 已存 JSON 没有 `timings` 时按“未测量”
  兼容读取，零毫秒仍表示真实的亚毫秒观测；只有四段计时完整的样本才进入汇总；
- 台账采用 nearest-rank，分别计算 Guance / Recorded Replay 的取证、确定性压缩、端到端总耗时
  p50/p95，两个来源绝不混算。正式页同时展示每组可测样本数；零样本明确显示“暂无可测样本”，
  并说明模型耗时、结果质量与 Gate verdict 尚不在本次统计内；
- Standards / Spec 本地双轴审查发现的 V181 旧 JSON 兼容性和端到端耗时一致性缺口已补测试关闭；
  没有新增模型调用、Replay 回退、candidate、生产写或 `fixtureMode=false` 路径；
- 排障域 + Skill Manifest 后端 `369` 个测试、前端 `17` 个测试文件 / `131` 个测试全通过；
  `vue-tsc --noEmit`、改动文件 ESLint、`git diff --check` 与直接 Vite 生产构建通过，构建完成
  `6275` 个模块转换。`npm run build` 仍因基线 `package.json` 引用了仓库中不存在的
  `../scripts/check-snowflake-precision.sh` 而在 Vite 前停止，本轮未扩大范围修补该上游问题；
- 最终工作树后端 PID `76866` 以 schema V181 监听 `18088`，前端 PID `92308` 监听 `5173`；
  正式 `/troubleshooting`、旧版 `/troubleshooting/legacy` 均返回 200，未登录 T8 API 返回预期 401。
  隔离登录态浏览器验证正式页、T8 台账和 legacy 均为 0 console error；本地台账诚实保持 `0/20`，
  两个来源均显示 0 条可测样本，未伪造真实观测；
- 下一步仍是 owner 完成 T7 真实 binding/字段核实并采集 20–30 条历史样本；单 Agent 运行接缝已在
  下一节补齐，但没有真实样本就没有可报告的质量/成本基线，Challenger 也仍不能启动。

T8 可复现单 Agent 基线接缝（2026-07-29）已实现，真实样本、Challenger 与 Gate 仍未完成：

- 新采集样本同时冻结 Evidence Spine 的 `evidenceOccurredAt` 与精确有界 `SynthesisModelInput` SHA-256；
  服务器只在内存中持有脱敏 `LogTraceSkeleton` 来生成指纹，样本/API 仍不保存或返回日志正文、DQL、
  搜索键、窗口或凭据。V181 旧样本缺少指纹时保持可读，但必须重新采集才能运行基线；
- 人工参考解新增显式 `expectedDisposition=DRAFT|ABSTAIN`。关联 Diagnosis 仍须 CLOSED，outcome、恢复验证
  和业务安全摘要仍由服务端读取；旧调用兼容默认 DRAFT，但正式 HTTP 请求不能省略期望行为；
- `POST /evaluation-samples/{sampleId}/baseline-runs` 先读取并钉死实际执行的 model + provider 配置快照，
  以不含凭据的 `model-config/v2` 指纹区分版本，再用数据库租约原子占住样本+模型版本运行键；未抢到的
  并发请求不访问证据源、不调模型。15 分钟 claim 在外部取证/模型调用期间每 4 分钟 CAS 续租，续租失败
  会中断当前有界外部调用，且 persistence/evidence/model/complete 每个边界重新核对所有权；旧 worker
  不再继续或发布结果，释放/到期后新 worker 才能接管。抢到后按冻结 lookup key
  重跑 Guance-only 或 fixture-confined Recorded Replay Evidence Spine，
  输入指纹漂移即 409 并要求保留旧样本、另采新样本；随后固定一个默认模型配置执行一次结构化归纳。
  没有可用模型时在访问证据源前 409；不会创建 candidate、触发审核或改变 approved Playbook；
- H2/MySQL/Kingbase V182 增加运行租约、证据 fixture / Diagnosis fixture 标记，完成后只保存模型版本、
  模型/组合时延、Token、Validator code、引用/必需意图/顺序/
  禁止意图比较和逐样本 `HELPFUL / UNHELPFUL / HARMFUL_BLOCKED / TECHNICAL_FAILURE` 分类。草案正文、
  拒答正文、原始证据、lookup material、candidate、approval 和 Gate verdict 均不在合同或表中；
- 正式 T8 台账可分别采集 Guance 真源与 Recorded Replay 对照，对两类新冻结样本运行基线；
  已有运行不再遮挡“当前模型版本”按钮，相同版本返回幂等结果，模型配置变更后创建新版本运行。
  页面按样本来源恢复 Guance 或 Replay 的冻结 lookup context，无码 Replay 样本不再错误依赖 Guance context。
  汇总先按 Guance / Recorded Replay，再按真实/fixture Diagnosis 分层显示模型 p50/p95、证据+模型总
  p50/p95 和 Token。页面明确这些只是描述性事实，不等于 T8 通过，不会关闭 `fixtureMode`；
- V183 为 H2/MySQL/Kingbase 增加 `capture_identity_key + capture_revision` 和 workspace 内唯一约束。
  每次 Guance / Replay 采集都会先重跑来源：输入指纹未变时幂等返回最新 revision，漂移时自动创建
  不可变 `rN`，旧样本及其人工 oracle 不覆盖；并发异指纹争用同一 revision 时会核对数据库赢家
  `modelInputHash`，不一致则基于最新 revision 有界重试。核心链没有 contrast 时参考解不会伪造
  contrast 必需项；
- 拒答只有在人工预期 `ABSTAIN`、完整 proposal 没有草案载荷、原因安全有界且证据落地时才计为
  `HELPFUL`；理由必须同时表达证据不足并引用本次实际 evidence ID / signal kind。安全但残留字段的拒答、
  或应弃权却生成的安全草案进入 `UNHELPFUL`；残留 payload 仍带当前 ValidationContext 校验 selector、
  signal kind、citation 及该样本人工 reference 的 `forbiddenStepIntents`；危险原因、命中样本级禁止
  humanAction/evidencePlan、越权或伪造引用进入 `HARMFUL_BLOCKED`，拒答正文仍不持久化；
- `GET /evaluation-samples/recorded-replay/capability?diagnosisId=...` 在服务端读取同 Workspace Diagnosis，核对
  workspace/system/service fixture scope、两个核心路由、Adapter 与 `ApprovedEvidenceSpineCatalog`，只在精确
  fixture 匹配唯一已批准方案时返回其原始 `scenarioKey/searchTerm/window`。采集 POST 只接受 `diagnosisId`，
  浏览器附带 target 字段直接返回 400；
  正式页无码主案例不依赖 Guance 表单或 errorCode，只有 capability 为 READY 才允许 Replay 采集，默认关闭
  和范围外场景都会显示明确原因；
- 排障域 + Skill Manifest 后端 `425` 个测试、前端 `17` 个测试文件 / `134` 个测试全通过；
  `vue-tsc --noEmit`、改动文件 ESLint、`git diff --check` 与直接 Vite 生产构建通过，构建完成
  `6275` 个模块转换。当前没有伪造 Guance 样本或真实模型结果；D12/D13、Loop、Planning、
  Evidence/Safety Challenger 继续保持 `PENDING-EVIDENCE`。

T7 owner 验收与 T8 真源门禁（2026-07-29）已实现，真实验收与真实样本仍未完成：

- H2/MySQL/Kingbase V184 新增不可变、workspace 隔离的 Guance binding 验收记录。只有 Workspace owner
  可提交 measurement/字段、索引、同 PS ID、时间单位/窗口、DQL 延迟与 903001 冲突清单；服务端提交时
  重新执行 Guance-only 两步读链，并在前后两次计算端点、路由、查询模板、行数预算与字段映射指纹，
  配置变化时拒绝写入或把既有记录投影为 `STALE`；运行时凭据轮换不改变字段级验收。
- 验收聚合只保存配置 SHA-256、结构计数、PS ID SHA-256、应用侧耗时、actor 与时间，不保存搜索键、
  PS ID 原文、DQL、凭据或日志。Guance T8 样本采集和已冻结样本的基线复跑都在任何 Router/真源调用前
  通过同一个 `GuanceEvidenceAcceptanceService.requireAccepted` fail closed；Recorded Replay 继续走独立
  fixture capability，不读取或继承 Guance 验收状态。
- Standards / Spec 双轴审查先发现并关闭两处 P1：普通 admin 可代 owner 验收，以及 Guance 基线复跑
  绕过门禁；最终复核均 PASS，无剩余 P0/P1/P2。回归明确验证 `requireAccepted → observe` 顺序、
  `STALE` 时零真源/模型调用、Replay 不经过真源门和 owner-only 注解。
- 最终工作树后端排障域 + Skill Manifest `439` 个测试、前端 `17` 个测试文件 / `135` 个测试全部通过；
  `vue-tsc --noEmit`、改动文件 ESLint、`git diff --check` 与直接 Vite 生产构建通过，构建完成 `6275`
  个模块转换。
- 后端 PID `25353` 已从 schema V183 真实迁移到 V184 并监听 `18088`，前端 PID `92308` 监听 `5173`；
  health、正式 `/troubleshooting` 与旧版 `/troubleshooting/legacy` 均返回 200，未登录验收 API 返回预期 401。
  登录态应用内浏览器确认正式页显示“当前绑定不可验收”、T6/T7/T8 全部 fail closed、Guance 采样禁用、
  Replay 独立显示 fixture 范围原因，台账诚实保持 `0/20`；旧版页面正常，两页均为 0 console error。
- 默认环境仍没有 Guance owner 配置、真实返回或 `ACCEPTED` 记录，`fixtureMode` 不变；必须由 owner 完成
  真实 T7，再积累并评审 20–30 条 T8 样本。Loop、Planning 与 Evidence/Safety Challenger 继续
  `PENDING-EVIDENCE`，不能把本节写成 T7/T8 已通过。

T14 版本化知识晋升与审计退役（2026-07-30）已实现，真实来源证明仍未完成：

- H2/MySQL/Kingbase V186 新增不可变 Playbook version store。开始审阅冻结当时 active authority
  baseline；批准时重读服务端当前资格与 routeable material，并永远创建新版本。乐观审核版本、冻结
  baseline 与数据库 nullable `active_selector_key` 唯一约束共同防止并发双权威；替代时旧版本和旧 review
  同步进入 `DEPRECATED`。
- MANUAL source 改为以 `sopId` 唯一，不同不可变 source 可共享同一 selector；V185 已在途 review 由迁移
  冻结 baseline。V186 回填的 LEGACY 权威只能用精确 `playbookVersion`、服务端 actor/reason 和 CAS 审计
  退役；有 review 的版本只能回到原审核记录退役。通用状态接口不能批准或退役版本化权威。
- 确定性命中只读取 operational authority；最新版本已退役时直接 route miss，不回落复活 legacy source。
  治理页另读最新历史版本，展示来源、Playbook/review version 与退役审计。批准/退役后详情立即采用服务端
  响应，不再残留旧状态。
- 历史 `knowledge-candidate.v1` 在正式页仍明确显示
  `OUTCOME_VERIFICATION_NOT_PROJECTED / POSITIVE_REPLAY_REQUIRED / OWNER_REQUIRED`；新生成的 v2 候选会消除
  已由关闭事务证明的 outcome/owner 缺口，但仍保留 `POSITIVE_REPLAY_REQUIRED`，因此没有因命令落地而被
  伪装成可晋升。`EVIDENCE_DERIVED / OUTCOME_BACKED` 的
  server-owned promotion material、真实 T7 owner 验收和 20–30 条 T8 样本仍待接入。
- 浏览器首轮验收发现 version mapper 的共享列片段把 `SELECT` 拼成 `SELECTid`，真实详情接口返回 500；
  已补 H2/MyBatis 集成测试覆盖 active/current/review/playbook/latest 五条查询并修复。最终排障域 + Skill
  Manifest 后端 `483` 个测试、前端 `18` 个测试文件 / `140` 个测试全部通过；`vue-tsc --noEmit`、
  改动文件 ESLint、`git diff --check` 与直接 Vite 生产构建通过，构建完成 `6276` 个模块转换。
- 本地 schema 已从 V185 真实迁移到 V186；后端 PID `90360` 监听 `18088`，前端 PID `92308` 监听
  `5173`，actuator health 为 UP。登录态浏览器验证正式工作台、Playbook 治理页和
  `/troubleshooting/legacy` 均为 0 console error；T8 台账诚实保持 `0 / 20` 且显示“暂无可测样本”。
  下一主攻仍是 owner 完成 T7 真配置/验收、积累并评审真实 T8 样本，再接 Challenger 与 Loop；本增量
  没有放开生产写或 hit-path LLM。

T14 关闭候选事实投影（2026-07-30）已实现，精确候选回放仍未完成：

- Diagnosis 合同升级为 1.7，确定性命中时把来源 Playbook 的 owner 冻结到聚合；人工转派继续只修改
  `routeToTeam`，不能反向篡改知识 owner。1.3–1.6 旧聚合保持可读，缺失 owner 时不补猜。
- `knowledge-candidate.v2` 与 ClosureRecord 在同一个纯状态转换和数据库事务中生成，冻结 outcome、
  `recoveryVerified`、actor 与时间；候选 proof 必须与 createdBy/createdAt 一致。历史 v1 Outbox 载荷仍可读取，
  但资格策略继续返回 `OUTCOME_VERIFICATION_NOT_PROJECTED`。
- 新 OUTCOME_BACKED 候选的审核详情展示知识 owner、outcome proof、恢复验证和登记时间；服务端资格策略
  只消除已被合同证明的缺口，`POSITIVE_REPLAY_REQUIRED` 仍然阻止批准。不得拿 candidate-free 的 T8
  `BaselineEvaluationRun` 冒充精确候选回放。
- 候选版本边界现在是硬约束：仅接受 v1/v2，v1 携带 proof/owner、v2 缺少 proof、未知版本都在
  合同边界直接拒绝；资格策略显式按版本投影。前端 Diagnosis 1.7 已类型化
  `sourcePlaybookOwner / knowledgeCandidates`，治理页区分“历史 v1 未投影”与“当前合同缺口”。
- Standards / Spec 双轴最终复审均 PASS，无剩余 P0/P1/P2。排障域 + Skill Manifest 后端 `491`
  个测试、前端 `18` 个测试文件 / `141` 个测试全部通过；`vue-tsc --noEmit`、变更文件 ESLint、
  `git diff --check` 与直接 Vite 生产构建通过，构建完成 `6276` 个模块转换。
- 后端 PID `45297` 以 schema V186 监听 `18088`，前端 PID `92308` 监听 `5173`。登录态真实页面验证
  `/troubleshooting`、`/troubleshooting/sops`、`/troubleshooting/legacy` 都正常且各自 0 console error；
  现存 v1 关闭候选正确显示历史 proof/owner 缺口和 `POSITIVE_REPLAY_REQUIRED`，未被伪装成可晋升。

T14 Diagnosis 精确 Playbook 权威引用（2026-07-30）已实现：

- Diagnosis 合同升级为 1.8；新的确定性命中路在落库前按 `SopEntry.sopId` 以
  `SELECT ... FOR UPDATE` 锁定仍为 active-approved 的 V186 版本，同时校验 selector 和完整路由合同；
  锁查询与 Diagnosis 插入在同一事务中。缺版本或并发替换导致内容不一致时 409 fail closed，
  不持久化不可核验的诊断。
- Diagnosis 冻结 `sourcePlaybookVersionRef(playbookId, playbookVersion)` 并在所有后续生命周期转换中保留。
  1.3–1.7 存量 JSON 保持可读；1.8 确定性聚合缺少引用时在合同边界直接拒绝。
- `DiagnosisDerivationService` 不再读取当前 active SOP，只按冻结引用读取精确历史版本；旧诊断缺引用、
  版本丢失或 selector 不一致都显式停止，不用今日知识伪造当时判定链。冻结版本重算与聚合信号
  不一致时按数据完整性故障暴露。
- 正式开发证据台的调查路径显示 `selector · playbookId@vN`；历史记录明示“未冻结版本”。
  旧处置台判定链接口失败时也显示保守停止文案，不留未处理 Promise，
  也不继续渲染“没有判据/规则”的伪空合同。
- 本增量没有改变候选晋升资格；`POSITIVE_REPLAY_REQUIRED`、真实 T7/T8、Challenger/Loop
  `PENDING-EVIDENCE`、hit-path 零 LLM 和生产写禁用边界全部保持。
- 最终工作树排障域 + Skill Manifest 后端 `499` 个测试、前端 `19` 个测试文件 / `142` 个测试全部通过；
  `vue-tsc --noEmit`、变更文件 ESLint、`git diff --check` 与直接 Vite 生产构建均通过。后端 PID `44207`
  监听 `18088` 且 health 为 UP，前端 PID `92308` 监听 `5173`。登录态应用内浏览器确认正式入口继续读取
  真实 API；旧版历史诊断展开后显示“判定链暂不可重建”，保留证据步骤并隐藏不可核验的判据/规则步骤，
  不再出现“该 SOP 没有定义判据/规则”的误导文案。
- 2026-07-30 又通过正式 Web“上报事件”入口创建演练 Diagnosis
  `diag-cfebdf495b944dcea030bf167fe66354`，不是直接写库或前端样例。正式工作台从真实 API 展示
  `CSDP:903001 · sop-csdp-903001-demo@v1`，证明新 1.8 聚合已冻结并投影精确权威版本；由于当前
  Guance/Replay 在线取证均未启用，三个证据结果为 `MISSING`、对应判据保持 `UNEVALUATED`，结论诚实落为
  `INSUFFICIENT_EVIDENCE / LOW / NEEDS_INVESTIGATION`，没有用 Playbook 命中伪造根因。该验证只证明
  正式工作台与 1.8 确定性主链一致，不代表 T7/T8、无码场景路或真实观测已经通过。
- Standards / Spec 双轴最终复审均 PASS；并发权威替换、存量兼容写入旁路、失败态伪空合同、重复编排、
  来源版本命名和残留非锁定 service 查询入口均已关闭，最终代码范围无剩余 P0–P3。
- 部署图拨测 SOP 增量完成后，排障域 + Skill Manifest 后端 `515` 个测试、前端 `21` 个测试文件 /
  `153` 个测试全部通过；`vue-tsc --noEmit`、变更文件 ESLint、`git diff --check` 与直接 Vite 生产构建
  均通过。后端 PID `27460` 监听 `18088` 且 health 为 UP，前端 PID `92308` 监听 `5173`。登录态应用内
  浏览器已加载真实样例部署图并确认 `21` 节点 / `27` 链路 / `1` 个可执行拨测 / `20` 个未配置节点，
  控制台 0 error；自动化没有点击“运行只读拨测 SOP”，因此没有向外部 Guance 发送 API Key，也不把
  此次入口验收表述为真实 canonical 返回或 T7/T8 通过。
- 排障队列传统列表与全宽详情增量完成后，前端 `22` 个测试文件 / `161` 个测试全部通过；`vue-tsc --noEmit`、
  变更文件 ESLint、`git diff --check` 与直接 Vite 生产构建均通过，构建完成 `6294` 个模块转换。登录态
  应用内浏览器确认无参数默认列表、列表/队列切换、列表记录进入无队列侧栏的全宽详情、返回列表、无
  `view` 的 `diagnosisId` 历史深链和“更多能力”5 个入口均正常，控制台 0 error；该增量只改变队列与详情
  呈现，不改变真实 API、只读证据与禁止生产写入边界。
- 能力命名与场景入口统一后，前端 `22` 个测试文件 / `163` 个测试全部通过；`vue-tsc --noEmit`、变更文件
  ESLint、`git diff --check` 与直接 Vite 生产构建均通过，构建完成 `6297` 个模块转换。登录态应用内浏览器
  确认“更多能力”只保留 4 个新名称，“发起排障”展示通用事件与部署拓扑两个场景，部署拓扑场景可进入
  既有 JSON 上传和只读分析界面；排障规则库、无码场景预演、观测云接入与验收、诊断效果评估的页面或
  弹窗标题均一致，控制台 0 error。验收没有上传快照或运行拨测，因此没有调用外部 Guance，也没有创建
  Diagnosis 或改变 T7/T8 状态。
- Workspace 共享拓扑图库增量及真实 CloudDial Explorer 展示参数兼容修复后，排障域 + Skill Manifest 后端 `527` 个测试、前端 `22` 个测试文件 /
  `164` 个测试全部通过；`vue-tsc --noEmit`、变更文件 ESLint、`git diff --check` 与直接 Vite 生产构建均
  通过，构建完成 `6297` 个模块转换。后端 PID `82173` 启动时已将本地 H2 从 V186 成功迁移至 V187，
  前端 PID `92308` 继续监听 `5173`。登录态应用内浏览器确认空图库、既有拓扑选择器、导入名称与 JSON
  入口、三步案例、可展开示例 JSON 均正常，控制台 0 error；验收没有实际导入快照、下载文件或运行拨测，
  因此没有新增共享资产、调用外部 Guance、创建 Diagnosis 或改变 T7/T8 状态。
- 部署拓扑拨测归位 Diagnosis 场景后，后端排障域 + Skill Manifest `535` 个测试、前端 `22` 个测试文件 /
  `164` 个测试全部通过；`vue-tsc --noEmit` 与直接 Vite 生产构建通过，构建完成 `6297` 个模块转换。
  V188 为 `deployment_topology_probe` 保存不可变的脱敏 Tool 运行记录；正式详情页已实测选择 Workspace 共享
  拓扑、运行 `topology_synthetic_probe`、展示节点观测，并展开三次历史的资产、状态、摘要、警告、节点状态和
  安全证据引用；刷新后仍保留。最终一次在修复后的 admin POST 与短事务锁路径上真实执行并成功落入历史。
  POST 继续要求 Workspace admin；最终写入在短事务内锁定 Diagnosis，使关闭状态与证据 append 不存在检查后竞态。
  当前本地真源请求返回 HTTP 200，
  但 `series` 为空，因此 Router 诚实投影为 `UNAVAILABLE`，Diagnosis 保持
  `INSUFFICIENT_EVIDENCE`，没有把无数据误写为网络健康，也没有落库 API Key、DQL 或原始响应。
- 部署拓扑受控 Scenario Diagnosis 入口完成后，后端排障域 + Skill Manifest `553` 个测试、前端
  `23` 个测试文件 / `170` 个测试全部通过；`vue-tsc --noEmit`、变更文件 ESLint、`git diff --check`
  与直接 Vite 生产构建均通过，构建完成 `6298` 个模块转换。服务端在同一事务内按
  `system:scenario:deployment_topology_probe` 锁定已审核启用的精确 Playbook，校验冻结的
  `synthetic_probe + deployment_topology + topology_synthetic_probe` 证据合同，再创建或复用
  `SCENARIO_PLAYBOOK` Diagnosis；浏览器不能指定 Playbook 版本、Tool Key 或查询参数，权威缺失或
  合同不匹配时返回 409 fail-closed。登录态应用内浏览器已确认“发起排障”入口、受控表单、
  `csdp:scenario:deployment_topology_probe` 权威选择器和提交按钮状态，控制台 0 error；验收没有提交
  表单，因此没有新增 Diagnosis 或调用外部 Guance。Spec / Standards 双轴复审均 PASS，无提交阻断项。

T14 MANUAL 精确候选固定回放 Gate（2026-07-31）已实现，人工批准和真实 T8 边界保持不变：

- 服务端资源 `manual-playbook-replay-suites.json` 同时托管部署拓扑导入示例、固定正例、健康反例与缺证据
  弃权例；启动时校验示例必须能通过自己的套件。浏览器只能按 selector 下载候选示例、按 source ID 触发
  回放，不能上传 fixture、预期答案、证明或候选内容来影响一次回放。
- 零 LLM evaluator 先锁定 `synthetic_probe + deployment_topology + topology_synthetic_probe` 证据合同，
  再复用确定性判据与规则语义核对每个预期结局。V189 在 H2/MySQL/Kingbase 保存不可变证明，只含通过计数、
  结构化失败码、服务端登录主体/时间和候选/套件双 SHA-256；不保存 fixture 观测值、DQL、日志、密钥或原始响应。
- Knowledge Review Inbox 实时重算当前候选和当前套件指纹；无套件、无证明、证明失效或回放失败都 fail closed。
  精确证明通过只会把满足 owner 与合同校验的 MANUAL 候选推进到 `ELIGIBLE_FOR_APPROVAL`，不会使其 routeable，
  不会自动开始审阅或代替审核人批准；批准仍由 V185/V186 乐观版本与 selector 单权威约束控制。
- 正式治理页可载入服务端示例、运行固定回放，并展示 suite、正负例计数、执行主体/时间、双指纹和失败码。
  这是 v4 §5.7 的 MANUAL 首版 bootstrap 证明，不是 Guance T7 owner 验收，也不是 T8 的 20–30 条真源样本；
  `EVIDENCE_DERIVED / OUTCOME_BACKED` 的精确候选 Gate、Challenger 与 Loop 继续待真实数据。
- 完成 Spec / Standards 双轴复审后，回放要求候选的全部 EvidenceRequest 与套件逐项精确覆盖，避免额外
  必需或可选取证绕过证明；MANUAL 完整合同统一校验版本、预算、安全字段、证据—判据—规则引用与动作边界，
  在线诊断和回放共用同一确定性规则解释器。后端排障域 + Skill Manifest `576` 个测试、前端 `23` 个测试文件 /
  `171` 个测试全部通过；`vue-tsc --noEmit`、变更文件 ESLint、`git diff --check` 与直接 Vite 生产构建均通过，
  构建完成 `6298` 个模块转换。此前仓库 `npm run build` 的前置脚本引用了不存在的
  `../scripts/check-snowflake-precision.sh`；本轮已补齐精度守卫，完整 `npm run build`（精度检查、
  `vue-tsc --noEmit`、Vite 生产构建）恢复通过。本地 H2 已从 V188 成功迁移至 V189，登录态应用内浏览器已确认
  治理页可从服务端载入 `CSDP:scenario:deployment_topology_probe` 完整候选合同并解除表单校验，控制台 0 error。
  验收没有点击注册或运行回放，因此没有新增候选、证明、审批记录、Diagnosis 或外部 Guance 调用。

P2 首条真实 Guance Evidence Spine（2026-07-31）已观测：

- 新增默认不激活的 `csdp-guance-evidence-pilot` Profile，只授权
  `workspace 1 / CSDP / csdp-session-service` 的 `log_search / log_trace_bundle / contrast_sample`，
  三份路由都硬限 `guance`，不存在 Recorded Replay 后备。
- Guance scalar 可能按字段返回多个 series，Adapter 只在同一个 component 内按相同观测时间合并。
  trace 则强制一个行集 series：每行的 `message` 是一条原子 JSON 日志，只从该记录提取白名单字段；
  跨 series 序号拼接一律拒绝，避免相同时间戳或返回重排造成错配。字段冲突、混合 PS ID、超行数或
  canonical 类型异常均 fail closed，trace DQL 不含会遮蔽 `maxRows + 1` 哨兵的 `LIMIT`。
- `contrast_sample` 仍是一次 Router/HTTP 源调用，但含四个 DQL component：失败/成功 cohort 各有
  样本总数和固定特征命中数。失败终态使用 `failed AND sendmsg`，成功终态使用
  `success AND sendmsg AND NOT failed`；四项均按 `@trace_id` 去重，共享服务端时间范围并各自压成
  单个 24 小时桶。固定特征命中率必须在失败 cohort 严格更高；早期同状态条件和仅 `NOT failed`
  的代理对照结果均已废弃。`success` 终态标记的业务语义仍待 owner 在 T7 正式确认。
- 真实运行暴露并修复了三个边界问题：应用全局 Long-to-String Jackson 设置不得把 Guance
  `timeRange` 数组写成字符串；Spring relaxed Map binding 的嵌套 JSON alias 必须使用
  `"[message@trace_id]"` 这类原样键语法；native curl 必须以 `-q` 禁用用户 `.curlrc`。
  三处均有回归测试。
- 当前本机 TUN 下 Java HTTP 连接无法稳定访问该内网主机，试点 Profile 使用受限
  `native-curl` transport。API Key 和请求体只经 stdin 传入，不进 argv、临时文件或子进程环境；
  错误不回显 stderr 或凭据，并以 `-q` 禁用用户配置。Key 仍仅存本地忽略的环境文件，未写入跟踪文件。
  独立成功 cohort 扫描量较大，试点超时显式设为 45 秒；这项延迟仍待 owner 在 T7 复核接受。
- 真实预览通过本地管理端点返回 `FULL_SPINE_OBSERVED`：`matchCount=2`、
  PS ID 存在但未输出原文、3 条 trace、服务序列为 `csp-rpc-msg`、异常数为 1，
  最终快照的失败对照为 `2/2`、显式成功终态对照为 `0/14047`（实时样本量会变化），
  且三个 Step 均为 `CANONICAL_RESULT_OBSERVED`；
  `sourceRequestCount=3`，没有回退 Replay。
- 2026-07-31 晚间用当前代码和同一授权重新验证：端点、API Key、`csp-rpc-msg` measurement、三份 binding
  与单关键词聚合均可用，但已审核的 `failed AND sendmsg` 在当前 24 小时和运行手册历史窗口均返回 0，
  因此完整预览按设计停在 `log_search=MISSING`。没有提交 owner acceptance，也没有伪造或持久化 T8 样本；
  下一次执行需要 owner 提供仍可命中的历史故障时间窗，或审核一份新的失败筛选合同。
- 这只证明“三次真源取证 + 确定性压缩 + 双投影”的首个真实竖线可执行。当前没有
  Workspace owner `ACCEPTED` 记录，也没有持久化到 T8 历史样本台账；`fixtureMode`
  不变。失败样本仍只有少量观测，不能外推为通用判据。下一步是 owner 复核索引、时间窗、DQL 延迟和当前 binding 指纹，提交 T7 acceptance，
  然后才能采集首条真实 T8 台账样本。CloudDial `synthetic_probe` 仍是独立未完成的真源合同。

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
   `projection-contracts.md`；D14 已进 Diagnosis 1.5，在线 Diagnosis 与知识合成也已通过共享
   Evidence Spine 稳定保存 canonical hop/对照。下一步是让真 Guance 产出经 owner 核实的同构事实，
   不是再造一套展示数据。
3. P1 T1→T5（含 T4.5）已完成；修改 prompt/model/schema 必须重跑固定 Replay Eval。
4. P3 纯文本闭环已收口；交互卡片需单独平台评审，不阻塞 P2 真实数据验证。不新建入站，
   不把 BusinessSummary 伪装成 tool-guard ApprovalNotice。
5. P2 T6 授权机制、真源验证接缝、部署拓扑拨测场景入口和首条 CSDP SendMsg
   `FULL_SPINE_OBSERVED` 已完成；下一主攻是由 owner 对当前指纹提交 T7 acceptance，
   再将真源运行持久化为首条 T8 台账样本，逐步积累 20–30 条。CloudDial
   `synthetic_probe` 仍需核对空 `series` 的任务时间窗并完成独立验收。
6. 部署拓扑 `MANUAL` 固定回放 Gate 已完成；示例导入、回放和人审是三个独立动作，不要自动批准候选，
   也不要拿这份 fixture 证明替代 T7/T8。
7. 真实样本稳定后再实现 Scenario Registry/Planning；不要先搭空平台。

## 10. 不要做

- 不再引用已确认属于其他项目的旧架构材料，后续只使用 MateClaw。
- 不把 v2/v3 或下载目录里的旧蓝图当现行设计。
- 不把五类 FaultClass 写成录音已定要求。
- 不让模型猜 error code 后进入 deterministic route。
- 不把内部思维链展示给开发，只展示证据、判据和可复算推导。
- 不擅自开 PR，不提交包含真实 token/IP/人名的源表。
