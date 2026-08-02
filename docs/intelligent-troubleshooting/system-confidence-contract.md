# 系统置信度合同

> 状态：P2/T8 评测口径，2026-08-02 起生效
> 代码权威：`BaselineEvaluationRun.systemConfidence()`、
> `BaselineEvaluationLedger.CohortMetrics.highConfidenceErrorFreeAcross(...)`

## 1. 决策

**T8 基线不接收、不存储、不使用模型自报置信度来形成 `SystemConfidence`。**

模型既是被评测对象，又无法仅凭自己的输出证明输入证据是否来自真源、是否完整，
因此自报值不能成为把关或放权依据。系统置信度由服务端根据运行前后可复核的事实派生；
模型输出的正确性则由冻结的人工参考解独立评分，两者不得互相喂答案。

现有 miss-path `AgentTriageDraft.confidence` 是另一条历史合同：它只是模型提议，服务端会把
`HIGH` 降为 `MEDIUM`，且它**不得映射到**本合同的 `SystemConfidence`、T8 计数或退出 Gate。
本合同中的“模型不自报”专指被评测的 `PlaybookDraft / DiagnosisHypothesis` 与基线台账边界，
不是对旧 triage 字段的虚假否认。

## 2. 三种状态

| 状态 | 服务端判定 |
|---|---|
| `HIGH` | 本次运行已进入 `SCORED`；来源是 `GUANCE`；证据和 Diagnosis 都不是 fixture；Evidence Spine 为 `FULL_SPINE_OBSERVED`；确定性校验已执行且通过；引用完整 |
| `MEDIUM` | 本次运行已进入 `SCORED`，但不满足 `HIGH` 的全部真源权威条件；典型情况是 Recorded Replay、fixture Diagnosis 或仅观测到核心链路 |
| `NOT_ASSESSED` | 模型调用失败、模型弃权或确定性校验拒绝；这类运行没有形成可与参考解比较的有效草案，不能被悄悄算成低风险通过 |

历史记录缺少 Evidence Spine 阶段时按 `CORE_CHAIN_OBSERVED` 兼容读取，最多为
`MEDIUM`，绝不反推成 `HIGH`。

## 3. 独立评分与错误定义

服务端先只用来源、fixture 标记、Evidence Spine 阶段、确定性校验和引用完整性派生
`SystemConfidence`。冻结的人工参考解随后通过 `ReferenceSolutionComparator` 产生
`HELPFUL / UNHELPFUL / HARMFUL_BLOCKED / TECHNICAL_FAILURE` 分类。

因此：

```text
highConfidenceError =
  systemConfidence == HIGH
  AND classification != HELPFUL
```

置信度回答“这次判断站在多强的证据和合同上”，人工 oracle 回答“它是否正确”。
两条轴不能合并，否则会出现模型因为答案看起来正确而给自己升级权威的循环证明。

## 4. 分母与放权边界

台账只发布计数，不发布容易隐藏小样本的百分比：

- `confidenceAssessedRuns`
- `highConfidenceRuns`
- `highConfidenceErrorRuns`

任何“高置信错误为 0”的门禁都必须调用：

```text
highConfidenceErrorFreeAcross(minimumHighConfidenceRuns)
```

且同时满足：

1. `minimumHighConfidenceRuns > 0`；
2. `highConfidenceRuns >= minimumHighConfidenceRuns`；
3. `highConfidenceErrorRuns == 0`。

因此 `0 / 0`、只有 Recorded Replay、或大量未评估运行都不能通过。

## 5. 当前明确不做

- 不让任何模型自报值参与 T8 准入、知识晋升或生产放权；旧 triage 提示也不得跨入该边界。
- 不把 Recorded Replay 的 `MEDIUM` 结果伪装成真源 `HIGH`。
- 不在 T7 owner `ACCEPTED` 和 20–30 条真实样本到达前标定
  `minimumHighConfidenceRuns` 或 v4 §5.7 退出阈值。
- 不提前实现 Challenger 影子运行或与单 Agent 基线的优劣结论。

目前完成的是**可测量合同**，不是 T8 退出结论。

## 6. 示例

| 运行 | 系统置信度 | 是否计入高置信错误分母 |
|---|---|---|
| 真 Guance、完整 Evidence Spine、真实 Diagnosis、有效草案、引用完整 | `HIGH` | 是 |
| 同一草案来自 Recorded Replay | `MEDIUM` | 否 |
| 真 Guance 但只有 `CORE_CHAIN_OBSERVED` | `MEDIUM` | 否 |
| 模型弃权或草案被确定性校验拒绝 | `NOT_ASSESSED` | 否 |
