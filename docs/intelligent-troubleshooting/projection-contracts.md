# 两个投影合同：BusinessSummary / DeveloperEvidenceView

> 状态：**信息结构已选定（2026-07-28）**——集中兵力做**服务经理**与**开发**两个受众，
> 企微协同流随 P3 暂缓。
>
> 依据：架构 v4 §7.2（一份 Diagnosis 两种投影）、§5.5（Diagnosis 契约）、§5.10（北极星时间戳）、
> 录音基线 F5 / F7 / F8。
>
> 原型：`mateclaw-ui/src/views/Troubleshooting/prototype/`（Vue，实现权威）
> + `experience-prototype-demo.html`（静态镜像，可直接演示）。

---

## 0. 这份文档解决什么

页面选型定了之后，真正卡住开发的不是布局，而是**后端该给出什么字段**。本文把选中的信息结构
逐项翻译成两个类型化投影，让 P1 可以在不实现 Projection 的前提下先把合同定住
（v4 §4 明确 P1 不新建 Projection 实现，本文只固定形状）。

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
│  证据收敛（链路 + 成功样本对照 + 异常点）
│  知识草稿状态
│  处置按钮（文案写明「只推进状态，系统不执行」）
└─────────────────────────────────────────────────
┌─ 开发证据台（开发）────────────────────────────── 默认折叠
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
- `fixtureMode=true` 时投影必须携带该标记，前端必须显示「Recorded Replay · 非真实观测云」。

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
        String at,                             // 相对时间或「判据」
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

---

## 5. 落地边界

| 阶段 | 做什么 |
|---|---|
| **P1（当前）** | **只固定本文合同，不实现 Projection**。合成竖线照 v4 §4 推进；时间戳与对照按 T4.5 落到数据里 |
| P2 | 真实 Guance 打通后，用真实样本校验 `ImpactView.evidenceRefs` 与 `ContrastView` 是否恒能取到 |
| P3 | 企微 Adapter 消费 `BusinessSummary` 排版并原路回复；投影本身不变 |
| P5 | 正式 `/troubleshooting` 吸收该结构；删除 dev-only 原型路由与两个原型组件 |

**删除清单（吸收完成后一并删）**：
`prototype/TroubleshootingExperiencePrototype.vue`、`prototype/DeveloperEvidencePanel.vue`、
`experience-prototype-demo.html`、router 里的 dev-only 分支。
