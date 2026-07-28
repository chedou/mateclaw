# 两个投影合同：BusinessSummary / DeveloperEvidenceView

> 状态：**信息结构已选定并进入正式工作台（2026-07-29）**——集中兵力做**服务经理**与
> **开发**两个受众，企微协同流随 P3 暂缓。
>
> 依据：架构 v4 §7.2（一份 Diagnosis 两种投影）、§5.5（Diagnosis 契约）、§5.10（北极星时间戳）、
> 录音基线 F5 / F7 / F8。
>
> 正式实现：服务端 `vip.mate.troubleshooting.projection` +
> `mateclaw-ui/src/views/Troubleshooting/FormalWorkbench.vue`。
> 原型：`mateclaw-ui/src/views/Troubleshooting/prototype/` +
> `experience-prototype-demo.html`（暂留作降级结局对照，不再是实现权威）。
>
> 通道消费方式见 §5（v4.2 补）：IM 侧复用平台已有 `channel/wecom`、`channel/feishu`，
> 不新建入站；出站交互卡片被 renderer 接缝形状挡住，先发纯文本。

---

## 0. 这份文档解决什么

页面选型定了之后，真正卡住开发的不是布局，而是**后端该给出什么字段**。本文把选中的信息结构
逐项翻译成两个类型化投影。P1 当时只固定形状、不实现 Projection；P1 收口后已按本文启动 T15，
服务端投影与正式 Web 工作台现已落地。

两条纪律先讲清楚：

1. **一份事实，两种投影**。两者都从同一个 `Diagnosis` 派生，前端不得自行推断结论或影响面。
2. **投影只产类型化事实，不产排版**。企微卡片、Web 页面各自在 Adapter/View 层排版；
   领域层不输出 HTML、Markdown 或卡片结构。

---

## 1. 选定的信息结构

一页两层，**业务摘要默认展开，开发证据默认折叠**：

```
┌─ 业务摘要（服务经理 / 二线 / 业务）──────────────── 默认展开
│  结论类型徽标 + 一句话结论 + 可信等级
│  问题 · 影响面 · 下一步            ← 三栏，全部来自类型化字段
│  北极星三段耗时                    ← 补问 / 调查 / 采纳，分开显示
│  处置按钮（文案写明「只推进状态，系统不执行」）
└─────────────────────────────────────────────────
┌─ 开发证据台（开发）────────────────────────────── 默认折叠
│  调查路径 + 证据收敛 + 成功样本对照
│  知识草稿状态
│  调用链竖栏 + 影响范围
│  证据时间线（含判据求值行）
│  排查步骤草稿 + 系统明确做不到
└─────────────────────────────────────────────────
```

**开发证据是原地展开还是独立视图，原型里做成了可切的 `view=INLINE|SPLIT`**，
两者渲染同一份 `DeveloperEvidenceView`，只是入口不同——这个选择不影响后端合同，
可以等真实使用反馈再定。

---

## 2. BusinessSummary

服务经理看到的**全部**。凡是这里没有的字段，服务经理就不该在页面上看到。

```java
public record BusinessSummary(
        String diagnosisId,
        ConclusionType conclusionType,   // LOCATED | EXCLUDED | HYPOTHESIS | INSUFFICIENT_EVIDENCE
        String headline,                 // 一句话结论，面向业务措辞，不含服务名/字段名
        String narrative,                // 2–3 句解释，含能力边界的自然语言表达
        Confidence confidence,           // HIGH | MEDIUM | LOW，枚举不是浮点
        String problem,                  // 报障现象（来自 Intake，不是模型改写）
        ImpactView impact,
        NextStep nextStep,
        DiagnosisStatus status,
        NorthStarTimings timings,
        boolean fixtureMode) { }

public record ImpactView(
        String functionScope,            // 受影响功能
        Integer affectedCustomers,       // 可空；未知就是 null，不用 0
        Integer affectedUsers,           // 可空
        BlastRadius blastRadius,         // SINGLE_CUSTOMER | MULTI_CUSTOMER | SYSTEM_WIDE | UNKNOWN
        List<String> evidenceRefs,       // 支撑该影响面的 queryId
        Instant observedAt,              // 可空
        String note) { }                 // 如「同窗口 214 个活跃客户零报错」

public record NextStep(
        String label,                    // 「解决方案」/「定位结果」/「排除结论」/「下一步」
        String text,
        String capabilityBoundary) { }   // 可空；写明系统做不到什么
```

**`nextStep.label` 随 `conclusionType` 变，这不是文案而是承诺**：

| conclusionType | label | capabilityBoundary 必填 |
|---|---|---|
| `LOCATED` | 解决方案 / 定位结果 | 代码类必填「只定位，不给方案、不能自动恢复」 |
| `EXCLUDED` | 排除结论 | **必填**「这是排除不是定位」，且 `confidence ≤ MEDIUM` |
| `HYPOTHESIS` | 下一步 | 必填「仍需人确认」 |
| `INSUFFICIENT_EVIDENCE` | 下一步 | **必填**弃权原因；`nextStep.text` 不得给出根因 |

**服务端不变量（写进 record 构造器）：**

- `EXCLUDED` 时 `confidence` 不得为 `HIGH`；
- `INSUFFICIENT_EVIDENCE` 时 `confidence` 恒 `LOW`；
- `affectedCustomers/affectedUsers` 非空时 `evidenceRefs` 不得为空——**精确人数必须有证据引用**；
- `affectedCustomers/affectedUsers` 非空时 `observedAt` 也不得为空；每条引用必须通过 canonical
  `incident_impact` schema，全部公共字段和出现的声明人数都不得互相矛盾；
- `fixtureMode=true` 时投影必须携带该标记，前端必须显示「Recorded Replay · 非真实观测云」。
- Diagnosis 聚合使用平台全局 Long→String 精度保护；投影读取 canonical 数值时只兼容其严格十进制
  整数字符串表示，不接受指数、小数、空格、前导零或越界值，也不得借兼容逻辑推断人数。

---

## 3. DeveloperEvidenceView

开发展开后看到的。它比业务摘要多的不是"更多字段"，而是**可复算性**。

```java
public record DeveloperEvidenceView(
        String diagnosisId,
        InvestigationMode investigationMode,   // ERROR_CODE_PLAYBOOK | SCENARIO_PLAYBOOK | OPEN_DISCOVERY
        RouteAuthority routeAuthority,         // EXPLICIT | RULE_MATCHED | MODEL_PROPOSED
        String playbookRef,                    // 可空
        CallChainView callChain,
        List<EvidenceStep> steps,
        ContrastView contrast,
        DraftView draft,
        List<String> capabilityLimits,
        boolean fixtureMode) { }

public record CallChainView(
        String psId,                           // 可空：未贯通时为 null
        List<Hop> hops,                        // 可空数组；空时前端显示 emptyReason
        String emptyReason,
        BlastRadius blastRadius) { }

public record Hop(String hopId, String service, String duration, boolean anomalous) { }

/** 一步 = 一条证据或一次判据求值，两者都要能被点开看引用。 */
public record EvidenceStep(
        EvidenceStepKind kind,                 // EVIDENCE | CRITERION
        Instant at,                            // 证据采集时间；判据行为 null
        String title,
        String detail,
        String ref,                            // queryId 或 criterionId
        StepTone tone) { }                     // NORMAL | ANOMALY | EXCLUDED | UNEVALUATED

public record ContrastView(
        boolean available,
        String failedSample,                   // 「失败链路 session-state 1.82s」
        String baselineSample,                 // 「同接口成功样本 P50 40ms」
        String note,
        List<String> evidenceRefs) { }

public record DraftView(
        String draftId,                        // 可空
        String title,
        List<String> steps,                    // 弃权时为空数组
        String emptyReason,
        ReviewStatus reviewStatus,             // 只会是 DRAFT/CANDIDATE，永不 APPROVED
        String stateNote) { }
```

**服务端不变量：**

- `StepTone.EXCLUDED`（判据求值为假）与 `UNEVALUATED`（证据缺失）**必须分开**，
  投影不得把两者归并——它们在操作者心智里语义相反；
- 判据行使用 `kind=CRITERION` 且 `at=null`，不再用 `at="判据"` 这类字符串哨兵值；
- `draft.steps` 非空时 `draft.reviewStatus` 必须是 `DRAFT` 或 `CANDIDATE`；
- `contrast.available=false` 时前端要显示这一行而不是隐藏它，
  因为"没取到对照"本身是判断质量的信息（对应 v4 §5.7 该草稿锁定校准期档）；
- **不展示模型私有思维链**：`steps` 只能来自服务端证据与判据，不得放 prompt 或模型自述。

---

## 4. NorthStarTimings（两个投影共用）

```java
public record NorthStarTimings(
        Instant reportedAt,      // 报障第一条消息到达
        Instant readyAt,         // Intake READY
        Instant conclusionAt,    // 产出结论或 abstain
        Instant handoffAt,       // 人确认/转派/关闭；未发生为 null
        Duration intakeCost,     // reportedAt → readyAt
        Duration investigateCost,// readyAt → conclusionAt
        Duration adoptCost) { }  // conclusionAt → handoffAt；未发生为 null
```

三段**必须分开显示**，禁止只给总时长——否则无法判断该优化补问、调查还是呈现（v4 §5.10 / D14）。
未发生的阶段保持 `null`，前端显示「未发生」，不得用 `0`。

**实现现状（2026-07-29）**：`Diagnosis` 1.5 已持久化该值对象；1.6 又把 `IncidentImpact` 纳入同一
聚合，且兼容 1.3–1.5 的字符串影响。Servlet Filter 在 Spring 请求映射与
校验前捕获 `reportedAt`，路由与必填信息就绪后捕获 `readyAt`，结论或 abstain 产出时捕获 `conclusionAt`，
第一次人工确认记录 `handoffAt`。旧 1.3/1.4 记录使用全 null 的 `unrecorded()`，不回填当前时间。

---

## 5. 投影怎么被通道消费（v4.2 补，源码复核后）

投影只产类型化事实，排版归 Channel Adapter——但"归 Adapter"具体是哪个接缝，此前没写。补上：

```
BusinessSummary                     领域产出，无 HTML / 无卡片结构
      │
      ├── Web：mateclaw-ui 直接渲染（已通）
      │
      └── IM：Channel 层排版
             ├── 纯文本 → ChannelAdapter.proactiveSend(targetId, content, DeliveryOptions)
             │            ✅ 现在就能用，企微/飞书都支持
             └── 交互卡片 → WeComCardKind.renderer / FeishuCardRenderer
                          ⛔ 被接缝形状挡住，见下
```

**被挡住的原因（架构 v4 §7.4）**：两个通道的 renderer 签名都是 `render(ApprovalNotice)`，
是 tool-guard 的形状。而 `ApprovalNotice` 的"批准"语义是**回放执行**被扣住的工具调用，
排障的"确认"只推进状态机、执行 0 个工具。

**因此这条写进合同：严禁把 `BusinessSummary` 适配成 `ApprovalNotice` 去复用现成 renderer。**
那样做会让一次排障确认在通道层看起来像一次工具批准——这是语义伪装，比缺功能危险。

顺序是：先泛化平台的 card kind renderer（参数化 payload 类型，单独评审），
再由排障域提供 `BusinessSummary → 卡片 payload` 的实现。在此之前 IM 出站只发纯文本摘要。

**给 Channel Adapter 的额外要求**（`BusinessSummary` 已经能满足，实现时别丢）：

| 要求 | 依据 |
|---|---|
| `conclusionType` 与 `confidence` 必须出现在卡片/文本首行 | 服务经理要先知道这是定位还是排除 |
| `nextStep.capabilityBoundary` 非空时必须原样带上，不得因为"太长"截掉 | 承诺边界属于结论的一部分 |
| `fixtureMode=true` 时必须显示 `Recorded Replay · 非真实观测云` | A10 |
| 卡片上**只放"确认"**，批准与关闭留在 Web | 摘要不足以支撑那两个决定 |
| 点击者必须能映射到 workspace 主体（`ExternalIdentityEntity`），未绑定即拒绝 | 不可追责的身份等于废掉审批 |

`DeveloperEvidenceView` **不进 IM**：它服务的是坐在 Web 前的开发，卡片里塞调用链只会变成噪音。
IM 侧给一条深链回 Web 即可。

## 6. 落地边界

| 阶段 | 做什么 |
|---|---|
| P1（已收口） | 只固定本文合同、不实现 Projection；合成竖线照 v4 §4 推进 |
| P2 | 真实 Guance 打通后，用真实样本校验 `ImpactView.evidenceRefs` 与 `ContrastView` 是否恒能取到 |
| P3 | 企微 Adapter 消费 `BusinessSummary` 排版并原路回复；投影本身不变 |
| P5 / T15（进行中） | 正式 `/troubleshooting` 已读真实投影 API；Diagnosis 1.5 已补 D14，1.6 已补结构化 `IncidentImpact`。投影可直接消费既有 `log_count` / `trace` / `log_trace_bundle` / `contrast_sample` / `incident_impact`；但在线真源仍待稳定产出完整 hop、对照与经引用的影响人数 |

**删除清单（正式页覆盖所有降级场景后再删）**：
`prototype/TroubleshootingExperiencePrototype.vue`、`prototype/DeveloperEvidencePanel.vue`、
`experience-prototype-demo.html`、router 里的 dev-only 分支。
