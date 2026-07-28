# IT 智能排障架构 v4 · 架构师评审记录

> 评审日期：2026-07-28
>
> 评审对象：`rfcs/intelligent-troubleshooting-architecture-v4.md`
>
> 产品事实：`docs/intelligent-troubleshooting/recording-product-baseline.md`
>
> 结论：**APPROVED FOR P1 IMPLEMENTATION**
>
> v0.8 增量结论：**LOOP / ADVERSARIAL DESIGN ACCEPTED；不扩大 P1 实施范围**

## 1. 评审结论

方向成立：产品中心应是一条共享证据脊柱，而不是错误码查表页，也不是自由 Agent。录音明确要求的
“日志检索 → PS ID → 全链路 → AI 归纳排查步骤”同时服务在线定位和知识生产，这两个闭环应共享
Evidence，而不共享状态。

初稿存在八个高优先级语义/实施问题。全部已在 v4 中修正：

1. 权威 Playbook 与开放探索策略拆分；
2. 模型场景选路收紧为“只提议已注册 key”；
3. 八个目标模块不再被解释成 P1 八套新服务；
4. `AWAITING_INPUT` 从 Diagnosis 状态机移到 IntakeSession；
5. 知识晋升按来源定义硬资格并加入并发/幂等约束；
6. 领域投影只产出类型化事实，不渲染企微卡片或 HTML；
7. 人工解法比较改为结构化意图、顺序、引用和禁止动作；
8. 增加取证、压缩、模型和通道的硬预算与降级行为。

P1 可以开始，但只允许沿现有合成竖线增量实现。企微、通用 Planning、完整场景路由和正式页面吸收都不与
P1 混做。

## 2. Step 0 · Scope Challenge

### 2.1 What already exists

| 子问题 | 已有实现 | 评审决定 |
|---|---|---|
| 错误码确定性命中 | `TroubleshootingIntakeService` + `SopEntry.routingKey()` | 保留；P1 不改 `RouteMode` 或 903001 竖线 |
| 无错误码兜底 | `TroubleshootingAgentTriageService` | 保留安全笼子；P1 的 SOP 归纳不复用自由工具循环 |
| 只读取证 | `EvidenceSourceRouter` + Guance / Recorded Replay Adapter | 直接复用，禁止新建第二套路由器 |
| 日志到链路骨架 | `SopSynthesisService.preview()` + `DeterministicLogTraceCompressor` | 作为 P1 前三步，不重写 |
| 脱敏 | `TroubleshootingSecretRedactor` + Evidence Sanitizer | 模型前后继续复用 |
| 结构化模型输出 | MateClaw 已在 Goal/Wiki 域使用 Spring AI `BeanOutputConverter` | 复用该机制，另加领域校验，禁止自造 JSON parser |
| 诊断生命周期 | `Diagnosis` + `DiagnosisStateMachine` | 不塞入资料补问状态 |
| 候选与发布 | `KnowledgeCandidate` + Outbox | 发布状态保留；审核状态单独建模 |
| Web 工作台 | `/troubleshooting` Vue 页面 | 正式功能不回退；原型只用于选信息结构 |

### 2.2 最小改动

P1 只完成一条真实产品竖线：

```text
现有 preview
  → 结构化 PlaybookDraft 归纳
  → 确定性领域校验
  → ReferenceSolution 比较
  → candidate（不可生效）
```

最多新增两个服务 seam：模型归纳、确定性校验。比较器优先做纯函数/值对象，不做新的 Spring 服务。
Planning、Projection、WeCom Intake、Scenario Registry 和数据库版本替换在后续阶段按需实现。

### 2.3 复杂度判断

初稿的八个模块若直接翻译成八个 service，会触发明显的过度设计气味。评审后把它们保留为长期责任边界，
P1 只深化三个既有落点，符合“最小 diff、显式、不同时做结构与行为大迁移”的原则。

### 2.4 框架复用核对

- Spring AI 1.1.8 已提供 `BeanOutputConverter`，仓库也有成熟用法。它负责 schema 提示和转换，不负责业务可信。
- 现有 `EvidenceProperties.Guance.timeout` 默认 5 秒，Binding 默认 200 行、硬上限 500 行；继续使用，不引入新重试框架。
- 现有 Agent 工具白名单适合开放探索，不适合 P1 结构化归纳。P1 不给模型任何工具，只给压缩骨架。

结论：**scope reduced and accepted**。没有新基础设施、消息队列、微服务或第二运行时。

## 3. Architecture Review

### A1 · Playbook 与 Discovery 语义混用

`[P1] (confidence: 9/10) architecture-v4 §5.6 — DISCOVERY 没有可批准的根因或 selector，却与错误码/场景知识共享 Playbook 生命周期。`

风险是“安全策略已批准”会在界面或代码里被误读成“诊断知识已批准”。已修正为：权威 Playbook 只含
`ERROR_CODE | SCENARIO`，开放探索使用独立 `DiscoveryPolicy`，两者只复用 EvidencePlan 值对象。

### A2 · 模型提议场景可越权生成调查程序

`[P1] (confidence: 9/10) architecture-v4 §3 — 只限制 scenarioKey 不够，模型若能控制 EvidenceRequest 参数，仍可扩大查询范围或注入危险查询。`

已修正为 `ScenarioProposal` 只返回注册 key 和候选参数。EvidencePlan、DQL、超时、行数、平台绑定全来自
approved Playbook；参数按 `ParameterBindingSpec` 和已确认上下文绑定，校验失败转开放探索或 abstain。

### A3 · 目标模块被误当成本期类清单

`[P1] (confidence: 9/10) architecture-v4 §4 — 八个模块若一次落地，会跨 Controller、状态机、数据库、模型和前端，无法小步验证。`

已明确目标边界与 P1 实现边界分离。P1 不创建 Planning、Projection、WeCom 或新状态机。

### A4 · `AWAITING_INPUT` 污染 Diagnosis 状态机

`[P1] (confidence: 9/10) DiagnosisStateMachine + architecture-v4 §8 — 当前 Diagnosis 只接受已形成的 Incident；资料不全还没有资格成为 Diagnosis。`

已拆成 IntakeSession、Diagnosis/Case、Knowledge 三个状态机。资料补齐只在 IntakeSession 发生。

### A5 · 知识晋升条件不够机械化

`[P1] (confidence: 8/10) architecture-v4 §5.7 — “经过人工审核”不是可执行规则，单次成功样本可能被误晋升。`

已按 `EVIDENCE_DERIVED / OUTCOME_BACKED / MANUAL` 定义最低证据；批准创建新版本，加入 generationKey、
乐观版本检查和 selector active-approved 唯一约束。

### A6 · Experience Projection 可能变成万能渲染层

`[P2] (confidence: 8/10) architecture-v4 §4/§7 — 通用 DeliveryProjection 若包含企微/Web 排版，会把领域与通道 UI 强耦合。`

已收紧为 `BusinessSummary` 和 `DeveloperEvidenceView` 两个类型化事实投影；Channel/View 只负责排版。

### A7 · “与人工解法一致”不可重复验收

`[P1] (confidence: 10/10) recording baseline F4 + architecture-v4 §11 — 自由文本逐字比较会鼓励针对单例调 prompt。`

已定义 `ReferenceSolution`：必需步骤意图、禁止动作、顺序约束、必需证据类型。验收看语义覆盖和安全，
不看措辞相似度。

### A8 · 影响人数可能制造假精确

`[P1] (confidence: 8/10) architecture-v4 §5.2 — affectedUsers=0 若没有证据，会被业务方读成“已证明无人受影响”。`

已增加 `evidenceRefs + observedAt`，未知保留 null/UNKNOWN，禁止默认 0。

Architecture Review：8 issues，全部关闭。

## 4. Code Quality Review

### C1 · `SopDraft` 名称窄于真实领域

场景型和错误码型产物都是 Playbook，开放探索又不是 SOP。新领域合同统一为 `PlaybookDraft`；现有
`SopSynthesisPreview` 和 API 路径暂保留，避免无价值的大规模改名。

### C2 · 复用 Spring AI 转换器，但不把转换成功当验证成功

仓库已有 `BeanOutputConverter` 用法。P1 复用它输出 Java record，再由领域 Validator 检查引用、selector、
动作、长度、枚举和跨字段不变量。解析失败返回 rejected draft，不自动重试到“碰巧能解析”。

### C3 · 现有取证编排存在两个调用点

在线 Intake 和 SOP Synthesis 都直接逐个调用 `EvidenceSourceRouter`。当前没有必要马上抽象批量 Orchestrator；
P1 固定两次调用且已有安全边界。等场景 Playbook 需要多证据计划时再抽，避免提前 DRY 出错误抽象。

### C4 · 兼容契约不在 P1 一次替换

`RouteMode`、`SopEntry`、`Diagnosis` 已有持久化与前端消费者。P1 先新增 draft/eval 合同，不同时改路由、
数据库和 UI。后续通过扩展再替换迁移。

Code Quality Review：4 issues，全部关闭。

## 5. Test Review

### 5.1 Test framework

- 后端：JUnit 5 + Mockito + AssertJ，命令见 `TODO.md`。
- 前端：Vitest；原型当前只做类型检查、构建和浏览器冒烟，不进入生产组件测试。
- 模型质量：需要离线 eval，禁止以真实网络模型调用作为单元测试。

### 5.2 Code path coverage diagram

```text
P1 SOP SYNTHESIS COVERAGE
=========================
[+] SopSynthesisService.preview()                         CURRENT
    ├── [★★★ TESTED] fixture workspace/system/service gate
    ├── [★★★ TESTED] log_search missing/malformed/unsafe PS ID
    ├── [★★★ TESTED] trace PS ID mismatch/overflow/order
    ├── [★★★ TESTED] secret redaction + 128 KiB/200-entry bounds
    └── [★★★ TESTED] deterministic skeleton → READY_FOR_MODEL

[+] PlaybookDraft induction                               P1
    ├── [GAP → UNIT] valid structured output → draft
    ├── [GAP → UNIT] empty/malformed/provider failure → rejected result
    ├── [GAP → UNIT] prompt-injection text remains inert data
    └── [GAP → EVAL] same replay set meets quality baseline across prompt/model change

[+] PlaybookDraft validation                              P1
    ├── [GAP → UNIT] valid citations/selectors/human actions accepted
    ├── [GAP → UNIT] fabricated citation rejected
    ├── [GAP → UNIT] write/tool/DQL/raw-log content rejected
    └── [GAP → UNIT] selector/type/cross-field mismatch rejected

[+] ReferenceSolution comparison                          P1
    ├── [GAP → UNIT] required intents + ordering + evidence pass
    ├── [GAP → UNIT] missing required intent fails with explicit delta
    └── [GAP → UNIT] forbidden action is a hard failure

[+] Candidate boundary                                    P1
    ├── [GAP → INTEGRATION] generationKey makes retries idempotent
    ├── [GAP → INTEGRATION] candidate cannot become active approved
    └── [GAP → INTEGRATION] fixtureMode survives API/persistence round trip

USER FLOW COVERAGE
==================
[+] Demo variants A/B/C
    ├── [BROWSER VERIFIED] Recorded Replay is visible
    ├── [BROWSER VERIFIED] MODEL_PROPOSED + MEDIUM is visible
    ├── [BROWSER VERIFIED] CANDIDATE cannot be mistaken for approved
    └── [GAP → DESIGN REVIEW] user has not yet selected the winning information structure
```

当前基础路径 5/5 已覆盖。P1 新增 14 条分支均已进入实施测试清单，其中 1 条需要 eval、3 条需要集成测试；
这些是尚未实现的计划缺口，不是当前已上线回归。

### 5.3 具体测试文件

| 测试 | 类型 | 关键断言 |
|---|---|---|
| `synthesis/PlaybookDraftInducerTest.java` | unit | 结构化成功、空响应、坏 JSON、provider 失败、注入文本 |
| `synthesis/PlaybookDraftValidatorTest.java` | unit | 引用、selector、动作、DQL/raw log、跨字段不变量 |
| `synthesis/ReferenceSolutionComparatorTest.java` | unit | 必需意图、顺序、禁止动作、证据类型、可解释 delta |
| 扩展 `SopSynthesisServiceTest.java` | unit | 任一步失败不调用模型；成功只产 candidate |
| 扩展 `SopSynthesisReplayTest.java` | integration/eval fixture | 会议正例 + 至少一条负例，固定基线对比 |
| Candidate persistence test | integration | generationKey 幂等、不可直接 approved、fixture 标记保留 |

Test Review：14 planned gaps；已经全部转成明确测试要求。silent critical gap = 0。

## 6. Performance Review

### P1 · 企微不能同步等待完整调查

两次 Guance 顺序调用最坏已到 10 秒，之后还有模型。企微 Intake 必须 2 秒内确认收到/补问，调查异步返回；
该要求属于 P3，不在 P1 先造队列。

### P2 · 数据量必须在模型前硬截断

已有 Binding 200 行默认/500 行绝对上限，Compressor 200 条/128 KiB 上限。P1 沿用并在模型前停止，不能靠
token 截断掩盖不完整证据。

### P3 · 模型只调用一次

P1 使用一次结构化调用、低温、固定 token 上限。转换/校验失败可见地失败，不做无界自纠错循环。真实样本
测出失败分布后，再决定是否允许一次受控修复调用。

### P4 · 当前不引入缓存或消息中间件

没有真实吞吐数据。先用 20–30 条历史样本测 p50/p95；若重复生成成为实际成本，再按 generationKey 做结果缓存。

Performance Review：4 issues，全部进入 v4 资源预算。

## 7. Failure Modes

| 生产失败 | 测试 | 错误处理 | 用户可见结果 |
|---|---|---|---|
| Guance 超时/5xx | 已有 Adapter/Router 测试 | canonical `MISSING`，停止合成 | 明确“证据源不可用” |
| PS ID 缺失、格式异常或链路混入另一 PS ID | 已有 synthesis/adapter 测试 | 409 / evidence missing | 明确失败，不产草稿 |
| 日志量或字符超限 | 已有 compressor 测试 | 拒绝压缩 | 明确“超出安全边界” |
| 日志含 secret 或 prompt injection | redactor 已有；P1 补 inducer 测试 | 脱敏、作为数据封装、模型无工具 | rejected/可审草稿 |
| 模型坏 JSON/空响应/超时 | P1 unit | rejected result，无 candidate 晋升 | 明确“模型归纳失败” |
| 模型伪造 evidence citation | P1 unit | Validator 拒绝 | 明确列出无效引用 |
| 模型给生产写、DQL 或工具调用 | P1 unit | Validator 硬拒绝 | 明确列出策略违规 |
| 人工参考解法缺失 | P1 unit | 不具备晋升资格 | 可保存 draft，不可批准 |
| 重试制造重复 candidate | P1 integration | generationKey 幂等 | 返回同一 candidate |
| 两人并发批准同 selector | 后续 governance integration | 乐观锁 + active-approved 唯一约束 | 一方收到版本冲突 |
| fixture 与真实 Guance 混报 | P1 integration | fixtureMode 端到端保留 | 页面持续显示 Recorded Replay |
| 企微消息重复/乱序 | P3 测试 | source message id 幂等 + IntakeSession 版本 | 明确已处理/等待补充 |

没有“无测试 + 无错误处理 + 静默失败”的 P1 路径。

## 8. NOT in scope

- 真实 Guance measurement、字段、PS ID 和阈值验证：需要内网数据，放在 P2。
- 企微 webhook、补问和原路 @：入口事实已锁定，放在 P3，不与核心竖线混做。
- 通用 Investigation Planning 和 Scenario Registry：P1 用固定会议案例，P4 再扩。
- K8S、代码搜索、影响面探针等新 Adapter：先证明日志竖线，再按场景增加。
- 自动执行 SOP、自愈、改数据、改配置、改代码：产品和安全边界明确排除。
- 自动给代码补错误码/自动提 PR：只允许形成待人工处理的知识提示。
- 正式吸收某套 UI 原型：等用户比较 A/B/C 后再决定。
- 新微服务、Python orchestrator、消息中间件：当前无必要。

## 9. TODO 决定

以下内容已直接转成 `TODO.md` 的分阶段任务，不再作为“可能以后做”的模糊条目：

1. P1：无错误码“会话消息发送失败”证据→PlaybookDraft→比较→candidate；
2. P2：真实 Guance 绑定和 20–30 条历史样本；
3. P3：企微 IntakeSession 与原路闭环；
4. P4：Scenario Playbook 与独立 DiscoveryPolicy；
5. 横切：Knowledge review status 与 Outbox publication status 分离。

FaultClass 五分类不来自录音，保持调查项，不进入当前合同。

## 10. 实施顺序与并行化

P1 的核心代码都集中在 `troubleshooting/synthesis`，并共同修改 `SopSynthesisService`，并行 worktree 会制造
无收益的冲突。**Sequential implementation, no parallelization opportunity.**

```text
1. PlaybookDraft + ReferenceSolution 值对象
2. 结构化归纳 seam + 单测
3. 确定性 Validator/Comparator + 单测
4. SopSynthesisService 编排 + replay/eval
5. candidate 幂等边界 + 集成测试
```

P2 的真实绑定和 P3 的企微接入在 P1 合同稳定后可以成为两条并行 lane，但不提前开工。

## 11. Completion Summary

- Step 0 Scope Challenge：scope reduced，P1 从八个目标模块收敛到现有合成竖线 + 两个 seam
- Architecture Review：8 issues found，8 resolved
- Code Quality Review：4 issues found，4 resolved
- Test Review：coverage diagram produced，14 planned gaps captured
- Performance Review：4 issues found，4 resolved in budgets/degradation rules
- NOT in scope：written
- What already exists：written，全部优先复用
- TODOS.md updates：5 staged items，FaultClass 保持调查项
- Failure modes：12 evaluated，0 silent critical gaps in P1 plan
- Outside voice：skipped，本次为单架构评审
- Parallelization：P1 sequential；P2/P3 later may run in 2 lanes
- Lake Score：16/16 评审问题均选择完整、安全且增量的处理方式
- Unresolved architecture decisions：0
- Unresolved product choice：A/B/C Demo 信息结构仍待用户选择

**最终结论：APPROVED FOR P1 IMPLEMENTATION。**

## 12. v0.8 · Loop Engineering / 多 Agent 反证增量评审

用户于 2026-07-28 接受以下架构判断：Loop Engineering 进入主架构；多 Agent 只作为结构化反证与评测机制，
先离线/影子运行，不进入默认在线排障主链。该增量不改变录音产品事实，也不修改 D4 零 LLM、D5 candidate
治理或 D9 零生产写。

### 12.1 Scope Challenge

当前 `TroubleshootingAgentTriageService`、Agent Graph 与服务端证据会话已经有迭代上限、唯一工具绑定、证据调用
上限和保守弃权。缺口不是再引入一个 Agent 框架，而是把预算、检查点、验证反馈和停止原因收敛成排障领域
合同。因此：

- 不引入第二运行时、Python orchestrator 或新的多 Agent 基础设施；
- P1 仍是一次结构化归纳 + 确定性校验，不实现 Loop Controller 或 Challenger；
- P2 在 20–30 条历史样本上做固定角色、固定一轮的影子评测；
- P4 才为 SCENARIO / OPEN_DISCOVERY 引入领域 `Investigation Loop Control`；
- 只有影子评测证明质量收益后，P5 才把反证报告纳入知识晋升资料。

### 12.2 Architecture Review

| 新问题 | 风险 | 处理决定 |
|---|---|---|
| A9 · 多 Agent 进入每次在线排障 | 延迟、成本和协调错误放大 | 默认在线仍单 Agent；Challenger 先异步影子运行 |
| A10 · 用 Judge / 多数票代替事实 | 错误共识被包装成高置信 | 确定性 Gate 与人工审核裁决；Agent 共识永不升级权威 |
| A11 · Challenger 另开工具面 | 反证角色绕过 EvidencePlan 和预算 | Challenger 首期只读冻结 EvidenceBundle；只返回 EvidenceGap |
| A12 · 循环由模型自行停止 | 无限迭代或过早“宣布完成” | LoopPolicy 服务端强制；LoopRun 记录预算、检查点和 stopReason |
| A13 · 多角色变成浅服务群 | 调用方需要理解角色、模型和轮次 | 只暴露 `evaluate(EvalSubject)`；角色是实现内部 Adapter |
| A14 · 没有等预算基线 | 无法证明多 Agent 值得成本 | P2 同样本比较质量、延迟与 token；无收益则不进入治理 Gate |

六项问题均已在 RFC v4 的 v0.8 增量中关闭。

### 12.3 Deep Module Review

- `Investigation Loop Control` 的 Interface 只有 `run(LoopInput, LoopPolicy)`，实现隐藏计划、取证、验证、恢复和停止；
- `Adversarial Evaluation` 的 Interface 只有 `evaluate(EvalSubject)`，实现隐藏 Challenger 数量、模型分配和合并；
- 删除前者会让预算/检查点/停止逻辑散回 Diagnosis、Synthesis 和 Agent Graph 调用方；删除后者会让反证协议、
  成本和失败策略散回 Knowledge Governance，两个 Module 都通过 deletion test；
- 测试只跨上述 seam 断言 `LoopOutcome` 与 `AdversarialEvalReport`，不依赖 prompt 文本或内部 Agent 轮次。

### 12.4 新增验证门

1. `LoopPolicy` 超限稳定产生 `ABSTAIN/ESCALATE`，且 stopReason 可审计；
2. 模型不能修改 allowedSignalKinds、预算或恢复策略；
3. Challenger 伪造引用、越权工具或生产写建议必须被报告/Validator 拒绝；
4. 任一 Challenger 失败产生 `UNAVAILABLE`，不得默认 PASS；
5. 单 Agent 与对抗评测在同一历史样本和等预算基线上比较；
6. 引用完整率、弃权质量或危险动作拦截没有可复现收益时，保持影子模式。

**增量结论：设计可进入现行蓝图和后续路线；P1 实施许可不变，多 Agent 在线放权仍未获批准。**
