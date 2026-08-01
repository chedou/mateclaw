# 待办清单 · IT 智能排障系统

> 更新时间：2026-07-31
>
> 唯一现行产品事实：`recording-product-baseline.md`
>
> 唯一现行架构：`rfcs/intelligent-troubleshooting-architecture-v4.md`
>
> 架构评审：`architecture-review-v4.md`，结论 **APPROVED FOR P1 IMPLEMENTATION**
>
> 第一性原理评价与修订：`architecture-critique-v4.md`（用户已认可，现行为 **v4.4** / 蓝图 v0.18）
>
> 已选定的投影合同：`projection-contracts.md`（服务经理 + 开发两个受众；企微独立 UI 投影原型暂缓，通道 P3 T9 与 T10 纯文本闭环已落地）
>
> **通道复用（D17）**：企微/飞书一律扩平台现有 `ChannelAdapter`；普通消息走
> `ChannelMessageRouter` pre-route，模板卡片事件才走 `CardKind`，不新建入站——
> 平台自带 `vip.mate.channel.wecom`，详见 v4 §7.4。

## 0. 当前判断

当前主线不是继续扩错误码页面，也不是接入更多 Agent 工具，而是先把会议指定案例跑通：

```text
会话消息发送失败（无 error_code）
  → log_search
  → 提取 PS ID
  → log_trace_bundle
  → 确定性压缩
  → PlaybookDraft
  → 与人工参考解法比较
  → candidate（不可直接生效）
```

现已完成 P1 fixture-only 竖线：固定三次取证、结构化归纳、确定性校验、参考解法比较、幂等 candidate 边界和北极星时间戳。
P1 本身未改路由、企微或生产数据；其后 T15 已单独将双投影和 D14 运行时采集进入正式工作台。
仍未实现 Loop Controller 或多 Agent Challenger。
验证记录见 `p1-verification.md`。

## 1. 每个变更都要守的红线

**红线的唯一权威清单是 `rfcs/intelligent-troubleshooting-architecture-v4.md` §9。**
本文不再复述条目——此前同一批约束在 v4 §1.2、v4 §9、HANDOFF 和本文各写了一遍，
条数与措辞互不相同，"哪一份是权威"事实上已经不唯一（见 `architecture-critique-v4.md` §2.5）。
动手前直接读 v4 §9；发现分歧以 v4 §9 为准，并在那里修改。

## 2. 已有底座，不要重复建设

- [x] `vip.mate.troubleshooting` Java 领域模块、REST、RBAC、持久化和状态机。
- [x] `(system,errorCode)` 的确定性 SopEntry 与 903001 端到端竖线。
- [x] 受限 Agent miss-path，唯一只读证据工具，失败保守 abstain。
- [x] `EvidenceSourceRouter`、Guance Adapter、Recorded Replay Adapter、canonical schema。
- [x] `log_search` / `log_trace_bundle`、PS ID 一致性、行数/时间窗/脱敏边界。
- [x] `DeterministicLogTraceCompressor`，模型前产出有界调用链骨架。
- [x] `SopSynthesisService.preview()`，fixture scope 内可到 `READY_FOR_MODEL`。
- [x] Diagnosis 处置闭环：确认、转派、批准不执行、外部 outcome、恢复验证、关闭。
- [x] KnowledgeCandidate + Outbox 只表达发布语义；独立审核台账已支持
      `CANDIDATE/v0 → IN_REVIEW/v1 → REJECTED/v2`，不复用 Outbox status。
- [x] Vue 排障工作台和三套只读体验原型。

## 3. P0 · 架构和体验校准

- [x] 从 28:30 录音中抽取 F1–F11 产品事实，并区分事实与讨论脑暴。
- [x] 删除误引的其他项目口径，只保留 MateClaw。
- [x] 形成架构 v4：一条证据脊柱、在线诊断/知识生产两个闭环。
- [x] 拆分 `investigationMode` 与 `routeAuthority`。
- [x] 权威 Playbook 只含 ERROR_CODE / SCENARIO；OPEN_DISCOVERY 使用独立 DiscoveryPolicy。
- [x] 完成架构师评审并关闭 8 个高优先级问题。
- [x] 蓝图 v0.8 增加 Loop Engineering 与多 Agent 结构化反证；保持 P1 范围不变。
- [x] A/B/C 三套 Demo 浏览器冒烟通过。
- [x] 第一性原理评价 v4 并落修订：D5′ 晋升分档、北极星时间戳、成功样本对照、
      PENDING-EVIDENCE 标记、红线收敛到 v4 §9（`architecture-critique-v4.md`）。
- [x] 原型补齐区分度：4 种结局 × 3 档路由可信、北极星三段耗时、成功样本对照、
      conclusionType 标记、可点的处置按钮、重复 `:key` 修复；另出不依赖 dev server 的
      静态镜像 `experience-prototype-demo.html`。
- [x] **信息结构已选定**：集中兵力做**服务经理摘要 + 开发证据台**两个投影，业务摘要默认展开、
      开发证据默认折叠；企微独立 UI 投影原型暂缓，不阻塞 P3 T9 真实通道实现。开发证据的入口做成 `view=INLINE|SPLIT` 可切，
      两者渲染同一份投影，入口选择不影响后端合同。
- [x] 两个投影合同已固定：`projection-contracts.md`（BusinessSummary / DeveloperEvidenceView
      / NorthStarTimings，含服务端不变量）。**P1 只固定合同，不实现 Projection**。

## 4. P1 · 无错误码证据→PlaybookDraft 竖线（已完成）

### T1 · PlaybookDraft 合同与结构化归纳

- [x] 新增 `PlaybookDraft` 值对象：generationKey、proposedType/selector、evidencePlan、criteria、
  diagnosisHypotheses、humanActions、evidenceCitations、modelProvenance、validationErrors。
- [x] 模型输入只包含已确认上下文和 `LogTraceSkeleton`，不含原始 EvidenceResult/DQL。
- [x] 复用 MateClaw 现有模型配置工厂和 Spring AI 1.1.8 `BeanOutputConverter`。
- [x] 一次结构化调用、低温、固定 token 上限；空响应、坏 JSON、provider 失败返回 rejected result。
- [x] 当前 `SopSynthesisPreview` 与 API 路径保持兼容，不做全仓改名。

完成标准：有效固定模型响应可生成 draft；模型未配置/失败时不创建 candidate，也不影响既有 preview。

### T2 · 确定性 PlaybookDraftValidator

- [x] selector/type/必填字段/长度/枚举/跨字段不变量校验。
- [x] evidence citation 必须属于本次 EvidenceBundle。
- [x] 拒绝 DQL、原始日志包、工具调用、生产写动作和未脱敏 secret。
- [x] 错误码候选不得由模型猜码进入 deterministic 权威；场景候选只能提议注册 selector。
- [x] 验证结果保存具体错误码和字段路径，供审核人理解，而不是只返回 false。

完成标准：伪造引用、危险动作、坏 selector、secret、DQL 均可被稳定拒绝并有测试。

### T3 · ReferenceSolution 比较与离线 Eval

- [x] 建会议正例 `会话消息发送失败` 的人工参考解法。
- [x] 参考解法结构：requiredStepIntents、forbiddenStepIntents、orderingConstraints、
  requiredEvidenceKinds。
- [x] 比较输出覆盖率、缺失步骤、顺序违规、引用缺口、危险动作，不做逐字相似度。
- [x] 至少加入一条负例，要求 abstain 或校验失败。
- [x] prompt/model/schema 变更必须跑固定 replay eval，并与上一次 baseline 比较。

完成标准：必需意图全覆盖、必要顺序满足、引用有效、禁止动作命中数为 0；差异逐项可解释。

### T4 · Candidate 幂等与不可晋升边界

- [x] `generationKey = hash(workspaceId, incident, bundle, modelConfigVersion, contractVersion)`。
- [x] 同一生成请求重试返回同一 candidate，不重复入库。
- [x] fixture 生成物始终保留 `fixtureMode=true`。
- [x] P1 只能创建 draft/candidate，不能写 active approved Playbook。
- [x] Outbox publication status 与 review status 分开；不复用 `PENDING/PUBLISHED` 表示审核。

完成标准：重复请求幂等；API/持久化往返不丢 fixture、引用和验证结果；任何接口都不能直升 approved。

### T4.5 · 成功样本对照与北极星时间戳（v4.1 新增）

来自第一性原理评价，用户已认可；论证见 `architecture-critique-v4.md` §2.3 / §2.4。

- [x] 合成流水线增加第 2.5 步 `contrast_sample`：同窗口同接口的**成功样本**对照。
- [x] `DeterministicLogTraceCompressor` 产出里带失败↔成功差异；模型看到的是差异，不是单条链路。
- [x] 对照取不到时**降级不失败**：草稿仍生成，标 `contrastAvailable=false`，
      并按 v4 §5.7 一律走校准期档，不得进入运行期晋升。
- [x] 记录四个北极星时间戳：`reportedAt` / `readyAt`（IntakeSession）、
      `conclusionAt` / `handoffAt`（Diagnosis）；abstain 也要写 `conclusionAt`。
- [x] 未发生的阶段保持 `null`，不得用 `0` 或当前时间填充。
- [x] 三段差值（补问成本 / 系统调查成本 / 人的采纳成本）分开统计，禁止只报总时长。

完成标准：对照命中与缺失两条路径都有测试；fixture 样本也能算出三段差值，
P2 拿到真实数据时有可比基线。

**为什么值得在 P1 就做**：对照是把"我们有全量日志"这个差异化兑现成**确定性判据**的最短路径
（"失败请求里 92% 有该特征、成功里 3% 有"不需要模型背书）；时间戳不在 P1 埋，
P2 就无法回答"到底省了多少人的时间"——而那是北极星本身。

### T5 · P1 测试清单

- [x] `PlaybookDraftInducerTest`：成功、空响应、坏 JSON、provider 失败、prompt injection。
- [x] `PlaybookDraftValidatorTest`：引用、selector、动作、DQL/raw log、跨字段不变量。
- [x] `ReferenceSolutionComparatorTest`：必需意图、顺序、禁止动作、证据类型、delta。
- [x] 扩展 `SopSynthesisServiceTest`：任一步失败不调用模型；成功只产 candidate。
- [x] 固定 Replay Eval：真实 Recorded Replay 组合固定模型正例 + 危险输出负例。
- [x] Candidate 集成测试：generationKey 幂等、不可直升 approved、fixture 标记保留。
- [x] 对照与时间戳测试：`contrastAvailable=false` 时降级不失败且锁定校准期档；
      四个时间戳往返不丢，未发生阶段保持 `null`。

### T5.5 · 正式 Evidence Spine 开发入口

- [x] 正式 `/troubleshooting/sops` 直接调用既有 synthesis preview API，不新增第二套取证实现。
- [x] 页面显式展示 `log_search → log_trace_bundle → contrast_sample`、PS ID 确定性调用链和
      失败/成功样本差异；对照缺失时保持不可用状态，不伪造测量值。
- [x] 该入口由服务端硬限制为 Recorded Replay，默认数据源仍关闭；不调用模型、不创建 candidate、
      不提供审核、晋升或生产写操作。
- [x] 正式 `/troubleshooting` 队列增加“无码证据预览”深链；只跳转到
      `/troubleshooting/sops?focus=evidence-synthesis` 并自动打开上述同一只读入口，不新建 API、
      页面、Scenario 运行时或 candidate 通道（2026-07-30）。

## 5. P2 · 接真实 Guance 和影子评估

### T6 · Workspace→观测资产授权

- [x] 设计 workspace/system/service 到 Guance 资产与 binding 的显式授权关系。
- [x] 未授权必须 fail closed，不能回退默认全局 API key/measurement。
- [x] 密钥只来自运行时配置，不进领域表、日志、prompt 或页面。
- [x] 正式工作台按 workspace/system/service 展示秘密无关的真源就绪投影。
- [x] 正式工作台增加独立于当前 Diagnosis 的“P2 真源接入”向导：owner/admin 可选择安全的
      system/service，取得只含 workspace 精确 ID、核心 binding 占位符与密钥系统环境变量名的外部配置
      骨架，再复用既有 readiness/acceptance API 检查 T6→T7→T8 门禁。页面不接收 API Key、不把占位符
      写回服务端；只有 readiness 进入 `READY_FOR_VALIDATION` 或 `CANONICAL_SIGNALS_OBSERVED` 才可转入
      既有 T7 只读验收。向导作用域与当前 Diagnosis 的侧栏/T8 状态隔离（2026-07-30）。
      异步响应必须继续匹配对话框 session、发起 origin，以及 system/service/searchTerm/window/occurredAt
      完整 lookup identity；关闭后重开不得复用旧响应。
- [x] 增加管理员触发的 Guance-only `log_search → log_trace_bundle` 单次只读验证；
      在 Router 调用前限制允许源，不得回退 Recorded Replay。
- [x] 将部署拓扑拨测从独立分析能力归位为 `deployment_topology_probe` Diagnosis 场景：拓扑快照作为
      Workspace 资产，`topology_synthetic_probe` 作为只读 Tool，Guance CloudDial 作为首个 Adapter；
      选定资产和脱敏结果均写入同一 Diagnosis Evidence Spine，详情页可查看不可变运行历史（V188）。
- [x] “发起排障 → 部署拓扑拨测分析”已能先由服务端创建/复用专属场景 Diagnosis：
      浏览器只提交 system/service/现象/严重程度/可选 Trace，服务端固定
      `deployment_topology_probe` 并在同一事务内锁定精确 active-approved Playbook。冻结版本必须显式要求
      `assetType=deployment_topology + toolKey=topology_synthetic_probe`，否则 409 fail closed。新建 Diagnosis 保持
      `INSUFFICIENT_EVIDENCE / LOW / NEEDS_INVESTIGATION`，不在拨测前输出根因或处置建议。
      非演练重试使用包含 scenarioKey 的五分钟幂等命名空间，不会与普通无码事件或其他场景错误合并（2026-07-31）。

2026-07-29：`workspaceId` 已贯穿唯一 Evidence Router/Adapter 脊柱；Guance 只有命中唯一、精确的
`asset-bindings[workspaceId,system,service].signal-bindings[signalKind]` 后才会读取运行时 API Key 并发请求。
重复/缺失/大小写归一后歧义均在 transport 前返回 `MISSING`。这只完成授权机制，**不代表**
任何真实资产、measurement、字段或阈值已经通过 T7。

2026-07-30：首个试点已写入默认不激活的 `csp-clouddial-pilot` Profile，激活时必须提供精确授权
`${MATECLAW_TROUBLESHOOTING_CSP_WORKSPACE_ID} / csp-deployment / csp-prm-miniapp / synthetic_probe`，
并把 `D::http_dial_testing` 标准请求外层、任务名、列映射写入 Guance Adapter 与配置。开关、API Key 和
明文 HTTP 许可默认仍 fail closed；仅本地联调可在操作员明确授权后临时设置
`MATECLAW_TROUBLESHOOTING_GUANCE_ALLOW_INSECURE_HTTP=true`，正式部署仍禁止。正式工作台已增加管理员
“部署图拨测 SOP”入口与 `/api/v1/troubleshooting/sops/deployment-topology/analyze`：上传快照后解析所有节点，
只把同时具备 `url + guance_url` 的节点经既有 Router/Guance Adapter 批量查询。本次样例为 21 节点、
27 链路、1 个可执行拨测；其他 20 个节点保持未覆盖。入口最多接受 32 个可执行拨测，以 8 路并发共享
25 秒总预算，超时节点只记 `UNAVAILABLE` 并保留已完成结果。尚未由自动化发起携 Key 的真实 HTTP 请求。
单次验证报告只保留匹配数、PS ID、trace 节点数、证据引用和时间戳；不保留原始行、
查询文本或凭据。验证报告现同时返回每个 Guance 核心信号与端到端的应用侧 round-trip 耗时，
作为后续 T8“取证时延”的同口径输入；它不冒充 Guance 服务端 DQL 执行耗时，后者仍需 owner
在 T7 用真实返回字段或观测平台核实。正式工作台把 T6 授权、T7 真字段验收、
T8 20–30 条历史样本拆成三个明确门禁并给出下一步动作；单次成功仍只是 owner 执行 T7 的工具，
不是 T7 或 T8 完成证明。

2026-07-31：新增默认不激活的 `csdp-guance-evidence-pilot` Profile，精确授权
`workspace 1 / CSDP / csdp-session-service` 的 `log_search / log_trace_bundle / contrast_sample`。
三份合同已在真实 Guance `csp-rpc-msg` 数据上运行：失败搜索返回规范 PS ID，同 ID 链路返回
3 条原子 JSON 日志；独立的 `failed` 失败终态与带显式 `success` 标记的成功终态 cohort 使用同一
时间范围和单桶聚合，四项均按 `@trace_id` 去重，对照可复算。统一 `EvidenceSpineOrchestrator` 首次返回
`FULL_SPINE_OBSERVED`，三次真源请求均为 `CANONICAL_RESULT_OBSERVED`，无 Replay 回退。
早期同状态条件构造的对照结果已废弃；当前失败样本量仍小，只证明机制可运行，不代表判据已泛化。
这仍是一次不持久化预览：尚无 owner `ACCEPTED` 记录，尚未进入 T8 样本台账，
`fixtureMode` 不变；CloudDial `synthetic_probe` 仍待独立真源验收。

### T7 · 内网核实

- [x] 建立持久化、按当前 binding 配置指纹失效的 owner 验收接缝：只有 Workspace owner 明确核对
      measurement/字段、索引、同 PS ID、时间单位/窗口、DQL 延迟与 903001 冲突，且服务端再次跑通
      Guance-only 两步读链后，才保存 V184 秘密无关验收记录；Guance T8 采集与基线复跑都在 Router
      调用前强制校验当前指纹。该接缝不代表下面任何真实核实项已完成。
- [x] 在真实 CSDP SendMsg 数据上核实 `csp-rpc-msg`、原子 `message` JSON 内的
      `trace_id / level / msg`、毫秒时间戳类型和同 PS ID 一致性；JSON `source` 是源码位置，
      canonical `service` 改由服务端 binding 固定提供，并明确拒绝 trace 跨 series 序号拼接（2026-07-31）。
- [x] 将成功样本对照改为独立 `failed` / 显式 `success` 终态 cohort，为两边使用同一服务器时间范围、
      24 小时单桶 rollup，并按 `@trace_id` 去重；失败特征命中率在真实数据上严格高于成功样本
      （2026-07-31）。`success` 终态标记的业务语义仍纳入下面 owner T7 acceptance。
- [ ] 由 Workspace owner 完成索引、时间窗、DQL 延迟和旧 route 冲突复核，并对当前配置指纹提交
      `ACCEPTED`；提交前不得开放 T8 真源采样。
- [ ] 用当前受控运行时 API Key 跑通 CSP `synthetic_probe`，核对 `status_code/url/name`、时间排序和无数据语义；
      本地联调可按本次操作员明确授权临时开启 insecure HTTP，完成后立即关闭；正式部署仍须使用 HTTPS
      端点或受控 TLS 代理，不得提交 Key。
- [x] 用会议案例跑真实 `log_search → log_trace_bundle`，确认搜索和 3 条关联日志使用同一 PS ID。
- [ ] 核实 903001 的字段/阈值与三处历史 route key 冲突；这只阻塞错误码竖线，不阻塞 P1 fixture。
- [ ] 真实源未验收前 `fixtureMode` 不得改为 false。

### T8 · 历史样本与性能基线

- [x] 正式工作台增加 Guance-only 的单条完整 Evidence Spine 预览：复用唯一
      `EvidenceSpineOrchestrator` 执行 `log_search → log_trace_bundle → contrast_sample →
      deterministic compress`，只返回调用链骨架、异常数、对照比率、证据引用和应用侧总耗时；
      不持久化原始日志、不调模型、不回退 Replay。
- [x] 在真实 Guance 环境运行首条不持久化预览：`log_search → log_trace_bundle →
      contrast_sample → deterministic compress` 返回 `FULL_SPINE_OBSERVED`，且
      `sourceRequestCount=3`（该项不等于 T8 台账已有 1 条样本）。
- [x] 建立管理员 T8 历史样本台账基础设施：采集时由服务端重新执行 Guance-only Evidence Spine，
      V181 只保存结构化计数、PS ID、调用链骨架、对照、耗时和固定证据引用；不接收浏览器预览、
      outcome、fixture 标记或审计 actor。人工参考步骤只接受结构化 intent key，关联 Diagnosis 必须
      已关闭，权威 outcome 与安全摘要由服务端读取并冻结；台账没有 `passed` 或 Gate verdict。
- [x] 共享 `EvidenceSpineOrchestrator` 记录三次 Router 往返和两次确定性压缩的应用侧墙钟时间；
      台账只纳入完整计时样本，以 nearest-rank 分别计算 Guance / Recorded Replay 的取证、压缩和
      端到端 p50/p95。V181 旧 JSON 缺少计时时兼容读取并排除统计；该指标不是 Guance DQL 时延，
      也不包含模型耗时或质量结论。
- [x] 建立 candidate-free 单 Agent 基线运行接缝：V182 样本冻结精确有界模型输入 SHA-256 与人工
      `DRAFT/ABSTAIN` 期望；运行时先原子占住样本+模型版本键，再按 Guance / Recorded Replay
      重放同一 lookup、核对输入指纹，并对固定模型配置只调用一次。结果只保存模型/组合时延、
      Token、Validator code 和逐样本结构比较分类，不保存草案正文、
      拒答正文、搜索键、原始证据、candidate 或 Gate verdict；正式台按 Guance/Recorded Replay
      分来源展示已有运行事实，但当前没有真实样本结果。
- [x] 关闭基线并发与复现缺口：模型版本使用 `model-config/v2` 覆盖并钉死 model + provider 配置快照；
      15 分钟 claim 每 4 分钟 CAS 续租，丢失所有权会中断当前有界外部调用，并在 persistence / evidence /
      model / complete 边界拒绝继续；ABSTAIN 校验完整 proposal，安全的协议残留或应弃权却生成安全草案
      归 `UNHELPFUL`，样本人工 reference 的 forbidden intents 进入 ValidationContext；只有危险原因、命中样本级
      禁止动作、越权引用等真实安全问题归 `HARMFUL_BLOCKED`。
- [x] V183 增加不可变采集修订：相同 capture identity 每次先重跑 Guance/Replay，同模型输入指纹复用
      最新 revision，证据漂移自动创建 `rN`，旧样本和人工参考解不覆盖；并发异指纹争用同一 revision 时
      核对数据库赢家指纹，不一致则基于最新 revision 有界重试，绝不误返回另一份输入。
- [x] Replay 采集按钮绑定服务端 capability：同时核对 fixture workspace/system/service scope、
      `log_search` / `log_trace_bundle` 路由、Adapter 支持与精确搜索样本；页面只提交 `diagnosisId`，
      服务端从 Diagnosis 与 `ApprovedEvidenceSpineCatalog` 唯一解析 scenario/search/window，浏览器提交目标字段
      直接返回 400；无码主案例不依赖 Guance 表单或错误码，默认关闭时明确禁用。Guance/Replay 基线按钮
      分别恢复各自来源的冻结 lookup context，不再用 Guance context 代跑 Replay 样本。
- [x] Guance 样本采集与基线复跑增加 T7 服务端门禁：只接受当前 workspace/system/service 的 V184
      owner acceptance，查询模板、字段映射、端点或路由配置变化后旧验收自动 `STALE`，任何真源请求前
      返回 409；Replay 仍保持独立 fixture capability，不受这条真源门禁混淆。
- [ ] 建 20–30 条历史样本，保留人工结论、参考步骤和 outcome。
- [ ] 统计 p50/p95 取证/压缩/模型/总时延、引用完整率、必需意图覆盖率、abstain 质量。
- [ ] 分开统计“没帮上忙”和“引向错误方向”；有害动作、高置信错误为 0 才可继续放权。
- [x] Recorded Replay 与真实 Guance 结果分组展示和统计，并在每个来源内继续分开
      真实 Diagnosis / fixture Diagnosis，禁止混成一个成功率。
- [ ] 在同一批样本上影子运行 Evidence Challenger + Safety Challenger，各一次调用、固定一轮。
- [ ] 与单 Agent/单次归纳基线比较引用完整率、弃权质量、危险动作拦截、p50/p95、token 和失败率。
- [ ] 无可复现质量收益或成本不可接受时，停止在影子模式，不进入在线或晋升 Gate。
- [ ] 在这批样本上确定 v4 §5.7 的**退出校准期阈值**（必需意图覆盖率、危险动作拦截率、
      高置信错误数为 0），并统计 §5.10 三段时间差；退出条件是数据达标，不是排期到点。

2026-07-29：上述完整预览与单 Agent 基线接缝已接入正式台账，当前完成的是**采集、冻结参考解、
可复现输入指纹、不可变采集修订、钉死 Provider 的单模型版本运行、分组计数与应用侧
取证/压缩/模型/总时延描述性统计能力**，
不是 20–30 条真实样本本身，更不是 T8 验收结论。`contrast_sample` 未绑定或不可用时仍保留核心
同 PS ID 链路，显式标记对照缺失并继续校准期；Guance 与 Recorded Replay、证据 fixture 与关联
Diagnosis fixture 分开记录。逐样本引用/意图覆盖、安全且证据落地的拒答原因、危险提议分类已经具备
结构化存储；Recorded Replay 采集和基线执行已接入。当前只有一次真源预览事实，没有 owner 验收后持久化的
T8 真实样本，仍不能产出质量结论；
Challenger 影子运行和两者对比仍未实现。
只有 owner 完成 T7、实际累积 20–30 条并跑完质量/完整性能统计后，
才能计算和评审整体 T8 基线。

## 6. P3 · 企业微信一线闭环

### T9 · IntakeSession（**扩平台现有企微通道，不新建入站**）

平台已自带 `vip.mate.channel.wecom.WeComChannelAdapter`（支持 proactiveSend 与交互卡片）
和 `WeComCardDispatcher` 多 kind 注册表。飞书排障 kind 只示范了卡片点击的前缀隔离；企微普通 @
消息不走 Dispatcher，而是在现有 Router 上接 pre-route handler。
详见架构 v4 §7.4 / D17。

- [x] 普通 @ 消息经平台现有 `WeComChannelAdapter → ChannelMessageRouter` 入站；Router 已增加
      显式开关的 `ChannelMessagePreRouteHandler`，已接管报障不再进 Trigger/通用 Agent，失败保守关闭。
      **不自建 webhook、不自建签名校验**——那是 Adapter 的职责。`WeComCardKind`
      只路由模板卡片点击，不再被当作普通消息 Intake 入口。
- [x] `conversationRef` / `reporterRef` / `sourceMessageId` / 附件引用取自 `ChannelMessage`
      （`chatId` / `senderId` / `messageId` / `contentParts`）与 `ChannelSessionStore`；Router 在 pre-route
      接管前保存带 channelId/targetId 的原通道路由，不新建会话表。raw `conversationRef` 保持业务身份稳定，
      单独持久化 `deliveryConversationId` 作为精确 ChannelSessionStore key，不用配置 ID 污染 routingKey。
- [x] `RECEIVED → AWAITING_INPUT → READY` 独立记在 `IntakeSession`；显式记录
      `reportedAt/readyAt`；补问往返沿用通道会话，
      **不塞进 `DiagnosisStateMachine`**。
- [x] 附件只保存受控 `storedName` 或消息级引用与元数据；不持久化本地路径/签名 URL，视频不做内容理解。
- [x] sourceMessageId 独立 receipt 表保证幂等；企微 `send_time` 经校验后作为事件时间，
      不可变 `reportedAt` 划分跨 Session 归属，`receivedAt <= lastMessageAt` 拒绝乱序覆盖；聚合版本检查 +
      active-key 唯一约束覆盖并发更新/创建冲突；锁覆盖完整事务边界，唯一键冲突回滚后只重试一次；
      READY 时原子释放 active key，稳定哈希 routing key 保留事件时间定位；迟到旧消息归入上一 Session，
      只有时间更晚的消息才创建新报障。
- [x] Intake 只将 `reporterRef` 当作不可信通道身份，未绑定仍可报障/补充，但不得用它
      审核或推进受审计状态。将来增加此类操作时，必须复用 `ExternalIdentityEntity`
      映射 workspace 主体，未绑定即拒绝该操作。

### T10 · 原路回复与关闭通知

- [x] 回调线程只提交 Intake + PENDING 调查任务并立即回复“已收到/还缺什么”；完整调查由数据库租约
      worker 异步执行，不让群消息等待取证或模型。真实企微 2 秒 p95 仍需上线后观测。
- [x] READY 与调查任务同事务提交；worker 带 120 秒租约、最多 5 次常规处理，启动时补齐历史 READY
      缺失任务。`source_intake_session_id` 唯一约束保证同一 Intake 只创建/复用一个 Diagnosis；通知失败
      不重跑调查。常规预算耗尽后进入持久终态投递并持续退避重试；先按 Intake 回查 Diagnosis，存在则继续
      投递摘要，确实不存在才投递明确 fail-closed 文本。
- [x] 业务摘要来自同一 Diagnosis 的 `BusinessSummary` 类型化投影，由通道交付 renderer 生成纯文本；
      首行保留 `conclusionType + confidence`，能力边界和 fixture 标记不截断。
- [x] 调查完成后经 `ChannelSessionStore → ChannelManager.sendToWorkspaceConversation → proactiveSend`
      原路返回，附 `/troubleshooting?diagnosisId=...` 正式工作台深链。只有 workspace/type/enabled 匹配且本节点
      持有 active leader Adapter 时才认领；精确路由缓存 miss 回源 DB，follower 不烧任务，平台 ACK 后才完成。
- [x] 关闭且 outcome 已登记后原路 @ 原报障人：Diagnosis 关闭更新与 V180 通知状态在同一事务边界提交；
      120 秒租约 worker 只在本节点持有精确 workspace 路由时认领，用
      `ChannelAdapter.proactiveSend(targetId, content, DeliveryOptions)` 发送纯文本最终结果与正式页深链。
      企微仅对安全 reporter ID 生成 `<@userid>`，平台 ACK 后才完成；失败持久退避且无硬重试上限。
      群聊还必须持有当前入站 reply context；重启后没有 `req_id` 时不认领、不回落
      `aibot_send_msg`。结案摘要入库前限制 500 字并拒绝凭据/DQL/原始日志/伪造 mention，出站文本
      继续做脱敏、mention 转义与 1800 字硬预算。
      非 Intake 来源的 Web/API Diagnosis 明确为 `NOT_APPLICABLE`，不伪造原路。
- [x] 未映射为可信 workspace 主体时，只允许报障/补充与接收只读摘要；本轮未增加任何通道审核、
      确认、关闭或其他受审计状态推进入口。
- [ ] **出站交互卡片先不做**：`WeComCardRenderer` / `FeishuCardRenderer` 的签名都是
      `render(ApprovalNotice)`（tool-guard 形状），且"批准=回放执行"与排障"确认=只推进状态"
      语义相反。**严禁把 `BusinessSummary` 适配成 `ApprovalNotice`**——先泛化平台接缝（单独评审），
      在此之前 IM 出站只发纯文本摘要。

## 6.5 T10.5 · 收敛 `RouteMode`（不要无限期停在中间态）

**现状（2026-08-01 源码核对）**：`Diagnosis` 里三个字段并存，且新字段是从旧字段**推导**出来的——

```java
RouteMode routeMode,                                  // 旧的一维
InvestigationMode investigationMode,                  // = defaultInvestigationMode(routeMode)
RouteAuthority routeAuthority,                        // = defaultRouteAuthority(routeMode)
```

下游判断（含前端 `DerivationChain.vue`）仍在用 `routeMode == DETERMINISTIC | LLM_FALLBACK`。

**为什么必须收敛**：D3 的原意是把"怎么查"和"为什么选中"拆成两个**独立**维度。
现在新维度没有独立信息量——`RULE_MATCHED` 与 `MODEL_PROPOSED` 在数据上无法区分，
因为两者都由同一个 `DETERMINISTIC` 推导而来。等 P4 的场景 Playbook 落地、模型开始提议
`scenarioKey` 时，会发现**可信等级根本没有地方存**，那时再改要动已入库的历史记录。

v4 §10 允许这个兼容中间态，但它是迁移的一站，不是终点。

**收敛步骤**（建议随 P4 T11 一起做，不单独排期）：

- [ ] 确定性诊断工厂**显式**写入 `investigationMode` + `routeAuthority`，不再走 `defaultXxx(routeMode)` 推导。
- [ ] 新增的场景路径按真实来源写 `RULE_MATCHED` / `MODEL_PROPOSED`；两者必须能在数据上分开统计。
- [ ] 下游判断（服务端 + `DerivationChain.vue` + 列表筛选）改读 `investigationMode`，
      `routeMode` 退化为纯持久化兼容字段。
- [ ] 历史记录**不回填猜测值**：1.x 旧行保持由 `routeMode` 推导，并在投影上可辨识，
      不能让"推导来的"和"真实写入的"混在一张统计表里。
- [ ] 收敛完成后，`RouteMode` 在契约文档里标注为 deprecated-for-read。

**完成标准**：`grep routeMode ==` 在服务端与前端的业务判断中为 0 处；
`RULE_MATCHED` 与 `MODEL_PROPOSED` 能在同一批样本上分别统计出条数。

---

## 7. P4 · 场景 Playbook 与开放探索

### T11 · Scenario Playbook

- [x] 先为会议正例 `message_send_failed` 建配置型 approved Evidence Spine 目录：模型只提交 workspace/system
      可见 `scenario_key`，搜索词、窗口、平台白名单和三阶段 request ID 全由服务端解析；当前平台固定
      `recorded-replay`。这只锁定 Planning 安全边界，不等于完整持久化 Registry 已完成（2026-07-29）。
- [ ] 先做 `slow_interface`、`system_unavailable`，再考虑更多场景。
- [ ] approved Scenario Playbook 拥有固定 EvidencePlan、ParameterBindingSpec、criteria 和输出策略。
- [ ] 模型只产 `ScenarioProposal(scenarioKey, parameterCandidates, reason, confidence)`。
- [ ] key 必须来自注册表，参数只绑定已确认字段/本次证据；模型不得产 DQL、EvidenceRequest 或工具名。
- [ ] `MODEL_PROPOSED` 结论最高 MEDIUM；显式/规则命中与模型提议分开统计。

### T12 · DiscoveryPolicy

- [ ] OPEN_DISCOVERY 不注册成 Playbook，不拥有 selector/已批准根因。
- [ ] Policy 只限定 allowedSignalKinds、证据调用次数、迭代、上下文和置信上限。
- [ ] 继续只暴露唯一只读证据工具；不得因新增场景而扩大 Agent 工具面。
- [ ] 引入 `LoopPolicy / LoopRun / LoopOutcome`，统一迭代、证据、模型、时长、上下文预算和 stopReason。
- [ ] ERROR_CODE 路固定一轮且零 LLM；只有 SCENARIO / OPEN_DISCOVERY 可在预算内继续取证。
- [ ] Agent 不得递归创建 Agent、延长预算或直接从 Challenger 请求证据源。

### T13 · Impact 与排除结论

- [x] `IncidentImpact` 增加功能范围、可空人数、BlastRadius、evidenceRefs、observedAt；Diagnosis 1.6
      兼容 1.3–1.5 字符串影响。正式投影仅接受能由本次非缺失 `incident_impact` canonical evidence
      逐项复算的精确事实；精确人数强制带观测时间，所有引用必须通过 schema 且彼此无矛盾。Intake 在
      路由、取证和持久化前统一脱敏影响文本。真 Guance 产出仍属 T7/T15 未完成项。
- [x] 未知人数保持 null/UNKNOWN，不用 0 冒充已测量；正式前端仅在人数非空时渲染数字。
- [x] `EXCLUDED` 与 `UNEVALUATED` 分开；“平台侧未见异常”不能写成“已定位客户网络问题”。

## 8. P5 · 知识治理

### T14 · Review status 与版本替换

- [x] 正式 `/troubleshooting/sops` 增加统一 Knowledge Review Inbox；服务端按 workspace
      同时读取 `EVIDENCE_DERIVED` PlaybookKnowledgeRecord、`OUTCOME_BACKED` 关闭候选与
      `MANUAL` 注册候选，页面展示来源、审核/校验状态、晋升资格、缺失条件、证据引用、模型与参考解法。
      未开始独立审核时统一投影为 `CANDIDATE/v0`；每条来源的当前资格由服务端返回，人工候选执行
      完整合同交叉引用校验，关闭候选则显式保留当前合同无法证明的 outcome/恢复验证缺口，
      不得由前端猜成已校验。旧式 candidate → approved 按钮已撤下。
- [x] 旧 `POST /sops/{system}/{errorCode}/status` 已拒绝 `candidate → approved`；V186 版本化
      Playbook 不能从通用状态接口退役：有 review 的版本必须携精确 review version 与 reason，迁移生成的
      LEGACY 权威必须携精确 playbookVersion，并统一记录服务端 actor/reason/退役时间（2026-07-30）。
- [x] 新建/扩展审核状态：DRAFT → CANDIDATE → IN_REVIEW → APPROVED/REJECTED → DEPRECATED。
  - [x] H2/MySQL/Kingbase V185 独立审核台账；无记录为 `CANDIDATE/v0`，可开始为
        `IN_REVIEW/v1`，可按精确版本拒绝为 `REJECTED/v2`。重试幂等，并发旧版本 409；
        审核人只从登录主体取得，reason 禁止凭据、DQL、原始日志和堆栈。
  - [x] V186 开放服务端门禁的 `APPROVED / DEPRECATED`：批准前重读当前资格与 server-owned
        routeable material，退役只作用于该审核创建且仍占有 selector 的 active 版本；重试幂等，旧版本 409。
        V185 已处于 `IN_REVIEW` 的记录在迁移时冻结当时 active baseline；不同 `sopId` 的不可变 MANUAL
        source 可共享 selector，避免首版终态后无法创建替代候选。
- [ ] EVIDENCE_DERIVED / OUTCOME_BACKED / MANUAL 分别按 v4 的最低证据计算晋升资格。
  - [x] 当前来源事实已由统一服务端策略计算并随 Inbox 返回：证据型显式处于默认
        `CALIBRATION` 档并核对 validation/reference/citation/fixture，不把 candidate 生成当正例回放；
        人工型核对 owner 与证据请求→判据→规则交叉引用；关闭型不再用
        “尚未实现”占位，而是逐项列出可证明事实与缺口；前端只消费该投影，缺失时 fail closed（2026-07-29）。
  - [x] Diagnosis 1.7 在确定性命中时冻结来源 Playbook owner（与后续人工 `routeToTeam` 分离）；
        `knowledge-candidate.v2` 在同一个关闭事务中冻结 outcome、恢复验证、actor 与时间。新关闭候选可由服务端
        消除 `OUTCOME_VERIFICATION_NOT_PROJECTED / OWNER_REQUIRED`，历史 v1 行继续 fail closed。候选合同仅接受
        v1/v2：v1 不得携带 proof/owner，v2 缺少服务端关闭 proof 直接拒绝（2026-07-30）。
  - [x] Diagnosis 1.8 在确定性落库前从 V186 版本库复核并冻结精确
        `playbookId + playbookVersion`；复核 active-approved 行时持有锁并与 Diagnosis 插入保持同一事务，
        判定链只从该不可变版本重建。新合同缺少引用直接拒绝，
        1.3–1.7 旧行继续可读但重建时 fail closed，正式开发证据台显示精确版本或“历史未冻结”，
        不用当前 active Playbook 冒充历史知识（2026-07-30）。
  - [x] `MANUAL` 首个精确候选 Gate 已接入部署拓扑场景：服务端托管固定正例、健康反例和缺证据弃权例，
        对完整候选执行零 LLM 确定性回放；V189 只持久化计数、失败码、执行主体/时间以及候选和套件双
        SHA-256，不保存 fixture 事实、查询或原始响应。证明只对精确候选与精确套件有效，合同或套件变化
        自动失效；通过后仍须人工开始审阅并批准，浏览器不能提交 fixture、预期答案或证明（2026-07-31）。
  - [ ] 将 `EVIDENCE_DERIVED / OUTCOME_BACKED` 的真实 T8 正例、负例或弃权回放接入各自的精确候选 Gate；
        selector 单 active-approved 已由 V186 数据库唯一约束关闭，以 ≥20 条样本和高置信错误数为 0 驱动
        `CALIBRATION ↔ RUNTIME` 切换。该样本门禁不适用于不分阶段的 `MANUAL`，固定 fixture 回放通过也
        不代表 T7/T8 真源验收完成。
- [x] 审核记录 reviewer、reason，并在开始审阅时冻结 validation summary、
      reference comparison、模型版本、fixture 与当时的资格缺口（2026-07-29）。
- [x] approved 永远创建新版本；审核开始冻结旧权威 baseline，批准时乐观校验，V186 以 nullable
      `active_selector_key` 数据库唯一约束防并发双权威；替代或显式退役同时把旧 review 置为
      `DEPRECATED`。确定性命中只读取 operational 权威；最新版本已退役时直接 route miss，绝不回落
      复活 legacy 行；治理详情仍可读取最新历史版本（2026-07-30）。
- [ ] 定义 `AdversarialEvalReport`：反证、缺证据、危险动作、权威违规、未解决分歧和成本。
- [ ] Challenger 首期只读冻结 EvidenceBundle；缺证据只返回 EvidenceGap，由 Loop Control 决定是否补证。
- [ ] P2 影子评测达标后才允许 `PROMOTION_GATE`；报告不可用不得默认通过。
- [ ] 确定性 Gate 与人工审核裁决；Agent 共识或票数永远不是批准条件。

### T15 · 双投影吸收

- [x] 用户选定 Demo 信息结构后，提炼 `BusinessSummary` 与 `DeveloperEvidenceView`。
- [x] 服务经理默认只看问题、影响、结论/下一步、状态；开发证据默认折叠。
- [x] 不展示模型私有思维链，只展示证据、判据和可复算推导。
- [x] 页面不自行推断影响或结论；所有事实来自后端投影。
- [x] 正式 `/troubleshooting` 已吸收双投影并读取真实 API；旧处置台临时保留在
      `/troubleshooting/legacy`，跳转携带同一个 `diagnosisId`（2026-07-29）。
- [x] 正式工作台已提供受 `operate:troubleshooting` 保护的 Web 事件上报入口，复用既有
      `POST /api/v1/troubleshooting/incidents` 与唯一 Diagnosis 主链，不新建第二套 Intake。浏览器只提交
      system/service/现象/严重级别及可选错误码、Trace 安全标识，默认演练；不接受原始日志、DQL、凭据、
      影响人数、调用方 evidence 或自定义 incidentId；服务端 Intake 在路由、持久化或模型前再次拒绝 Incident
      字段中的 DQL、原始日志和堆栈正文。错误码优先走零 LLM Playbook；未命中路径未启用时明确
      fail closed。非演练事件统一服从五分钟幂等：错误码事件按 route，无码事件按规范化 system/service/
      symptom/trace 生成稳定键（2026-07-29）。
- [x] `Diagnosis` 1.5 已持久化 `investigationMode` / `routeAuthority` / `conclusionType`
      与 D14 四时间戳；定位、排除、假设、弃权不再由前端或投影根据 `routeMode` 猜测。
- [x] `reportedAt` 由 Servlet Filter 在请求映射前捕获；Duration 以 ISO-8601 输出；首次人工确认补写
      `handoffAt/adoptCost`，登录态浏览器已完成 Diagnosis 1.5 创建与确认验收。
- [x] 判据字段缺失、类型错误或不可解析时保持 `UNEVALUATED`；只有完整可求值且为假的判据才能形成
      `EXCLUDED`，避免把“没取到”升级成“已排除”。
- [x] 双投影已直接复用 Diagnosis 内既有 canonical evidence：`log_count` 只作为带引用的事件量，
      不冒充客户/用户数；`trace` 只显示“部分异常 hop”；`log_trace_bundle + contrast_sample`
      可确定性压缩为有界调用链和失败/成功样本对照。未新增表、接口或第二套证据结构。
- [x] 聚合持久化的 Long→String 精度保护已纳入投影边界：只接受 canonical 十进制整数表示，
      完整链路、对照计数和事件量往返后仍可复算；宽松数值强转继续 fail closed。
- [x] `IncidentImpact` 已进入 Diagnosis 1.6 / Intake / HTTP / 投影合同；精确人数、BlastRadius、
      observedAt 必须由安全 evidenceRefs 指向的本次 canonical `incident_impact` 证据逐项复算，
      每条引用都必须通过 schema，任一引用混入非影响证据或彼此不一致都返回 null/UNKNOWN，不把任意
      日志量或前端输入冒充人数。
- [x] 在线 Diagnosis 的安全 `log_search` 已由服务端固定展开为
      `log_search → log_trace_bundle → contrast_sample`，与 `SopSynthesisService` 复用唯一
      `EvidenceSpineOrchestrator`。Agent 只提交注册 `scenario_key`；搜索词、窗口和平台白名单来自 approved
      服务端配置。三次 Router 调用先整体预留预算；预检失败粘滞并强制 abstain。完整 canonical evidence
      保存进同一个 Diagnosis，supplied evidence 与工具响应共用模型安全投影，不含 query、原始 `entries`
      或日志正文。核心 trace 缺失由服务端强制 abstain；对照缺失保存为显式 `MISSING` 并在正式页显示
      “已采集但来源不可用”，不冒充正常基线或“尚未保存”（2026-07-29）。
- [ ] 真 Guance 仍需稳定产出经核实的 `incident_impact` 人数/BlastRadius；旧记录或缺失事实继续返回
      null/UNKNOWN。1.3/1.4 的 D14 也不回填伪数据。

## 9. 明确不进入当前计划

- 自动重启、扩容、切流、改配置、改数据、改代码。
- 自动给代码补 error code 或自动提 PR。
- 把错误码确定性命中路交给 LLM/Workflow。
- 把 Wiki、Memory、Skill 或聊天记录当成诊断权威。
- `CODE_BUG / DATA_FIX / BUSINESS_OPERATION / EXTERNAL_CLIENT / INFRASTRUCTURE` 五类 FaultClass。
  录音只支持“代码类只定位、数据/业务类可给人工建议”两种能力边界；五分类需样本证明后另行设计。
- 继续堆 dev-only 原型；信息结构已选定，后续产品增量只进入正式工作台。

## 10. 工程约定与验证命令

- 后端测试：JUnit 5 + Mockito + AssertJ。

  ```bash
  mvn -pl mateclaw-server -am \
    -Dtest='vip.mate.troubleshooting.**.*Test' \
    -Dsurefire.failIfNoSpecifiedTests=false test
  ```

- 前端类型检查：

  ```bash
  cd mateclaw-ui
  node --max-old-space-size=6144 ./node_modules/vue-tsc/bin/vue-tsc.js --noEmit
  ```

- 前端直接构建：

  ```bash
  node --max-old-space-size=6144 ./node_modules/vite/bin/vite.js build
  ```

  `../scripts/check-snowflake-precision.sh` 已补齐；当前 `npm run build` 会依次执行 Snowflake ID 精度检查、
  `vue-tsc --noEmit` 和 Vite 生产构建，三段均通过后才算前端构建成功。

- 新增表或列必须同时更新 MySQL、H2、Kingbase 三份 Flyway 迁移。
- 不擅自开 PR；源表 xlsx 含真实 token/IP/人名，未入库且不得入库。

## 11. 推荐接手顺序

1. 先读现行录音基线、v4.4、HANDOFF 和本清单；正式 `/troubleshooting` 是实现权威，不再以 Demo 反推产品。
2. 主攻 P2 真实 Guance measurement/字段/阈值核实和 20–30 条影子样本；当前 CloudDial 请求已到达真源，
   但样例查询尚未返回 series，必须保持 `INSUFFICIENT_EVIDENCE`，不得伪造健康结论。
3. 沿同一 Evidence Spine 补结构化影响、完整 hop 与成功样本对照，不另建一套数据。
4. P3 纯文本闭环已收口；交互卡片仍是需单独平台评审的后续项，不阻塞 P2 真实数据验证。
   不新建入站，不把 `BusinessSummary` 伪装成 tool-guard `ApprovalNotice`。
5. 有真实样本和时延数据后再做 P4 场景路由；不要先搭空的通用 Planning 框架。
6. P2 影子评测证明收益后，再把固定 Challenger 报告接入 P5；知识审核状态和版本替换须在 candidate 真实积累前完成。
