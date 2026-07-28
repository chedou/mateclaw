# P1 主链路验证记录

> 最后更新：2026-07-29
>
> 设计基线：`rfcs/intelligent-troubleshooting-architecture-v4.md` v4.2
>
> 验收案例：会话消息发送失败（无 `error_code`）

## 1. 结论

P1 fixture-only 竖线已连通：

```text
log_search
  → 提取 PS ID
  → log_trace_bundle
  → contrast_sample
  → 确定性 LogTraceSkeleton
  → 一次结构化模型归纳
  → 确定性 Validator
  → ReferenceSolution 结构比较
  → 幂等、只待审、不可晋升的 candidate
```

这个结论仅适用于脱敏 Recorded Replay 和固定模型响应。真实 Guance 字段、
measurement、PS ID 贯通和真实模型质量属于 P2，本文不将 fixture 证据表述为生产验证。

## 2. 已验证的合同

| 层次 | 验证结果 |
|---|---|
| 证据链 | 固定 3 次取证；失败样本 92/100、成功样本 3/100，差值 `0.89` |
| 模型输入 | 仅确认上下文、证据 ID/类型和 `LogTraceSkeleton`；不传原始 `EvidenceResult`、DQL 或全量日志 |
| 模型调用 | 一次、低温 `0.1`、上限 1800 token；空响应、坏 JSON、provider 失败均类型化拒绝 |
| 确定性 Gate | 拒绝猜 error code、伪造引用、secret、DQL/raw log、工具调用和生产写动作 |
| 参考解法 | 比较必需/禁止意图、步骤顺序和证据类型，不使用逐字相似度 |
| Candidate | `generationKey` 幂等；`reviewStatus=CANDIDATE`、`validationStatus=VALID`、`approvalEligibility=NOT_ELIGIBLE` |
| 时间戳 | `reportedAt / readyAt / conclusionAt / handoffAt`；未发生的 handoff/adopt 保持 `null` |
| 存储 | H2/MySQL/Kingbase 三方言 V174；审核状态与发布状态分离 |

## 3. 固定 Replay Eval

`PlaybookSynthesisReplayEvalTest` 组合的是真实 Recorded Replay Adapter、Router、确定性压缩器、
Spring AI 结构化解析、Validator、ReferenceSolutionComparator 和幂等 Store；仅将外部模型
供应商替换为固定响应。

- 正例：首次 `CANDIDATE_CREATED`，重试 `CANDIDATE_REUSED`，参考解法通过。
- 负例：固定输出含 `restart_production / kubectl delete pod`，返回 `VALIDATION_REJECTED`，
  Store 仍为空。

## 4. 本地 HTTP 实测

启动参数显式开启 Recorded Replay，应用使用本地 H2 并成功迁移到 V174。

### `POST /api/v1/troubleshooting/sops/synthesis/preview`

```json
{
  "code": 200,
  "stage": "READY_FOR_MODEL",
  "matchCount": 4,
  "psId": "synthetic-ps-message-send-001",
  "contrastEvidencePresent": true,
  "rateDelta": 0.89,
  "fixture": true
}
```

### `POST /api/v1/troubleshooting/sops/synthesis/candidates`

本地库没有配置可用模型，因此这条真实 HTTP 请求应当、且实际确实 fail closed：

```json
{
  "code": 200,
  "stage": "MODEL_REJECTED",
  "candidateIsNull": true,
  "errors": ["MODEL_UNAVAILABLE"],
  "previewStage": "READY_FOR_MODEL",
  "contrastEvidencePresent": true,
  "fixture": true
}
```

这条结果同时证明：应用级 REST/RBAC/证据路由已连通；模型不可用时不会伪造 draft、
不会返回 candidate，也不会进入 approved Playbook。

## 5. 可复现命令

本仓必须显式使用 JDK 21：

```bash
JAVA_HOME=/Applications/ServBay/package/openjdk/21/21.0.10/zulu-21.jdk/Contents/Home \
  mvn -pl mateclaw-server \
  -Dtest='vip.mate.troubleshooting.synthesis.*Test,vip.mate.troubleshooting.evidence.*Synthesis*Test,vip.mate.troubleshooting.evidence.RecordedReplayAdapterTest,vip.mate.troubleshooting.persistence.MybatisPlaybookCandidateStoreTest,vip.mate.troubleshooting.persistence.TroubleshootingMigrationTest,vip.mate.troubleshooting.controller.SopSynthesisControllerTest' \
  test
```

### 自动化验证结果

- P1 定向集：56 tests，0 failures，0 errors。
- 排障领域全量：193 tests，0 failures，0 errors。
- `mateclaw-server` 整模块：4,270 tests，0 failures，0 errors，1 skipped，命令退出码 0。
  测试结束后仓库既有 Spring scheduler 未在 30 秒内完全退出，Surefire 强制结束
  fork JVM；未产生测试失败，但应作为独立的测试基础设施问题跟踪。
- `ApplicationContextSmokeTest`：1 test 通过，Flyway 验证 168 个迁移并将本地 H2
  迁移到 V174。
- `git diff --check`：通过。

## 6. 尚未宣称完成的事

- 未接入真实 Guance，未验收 measurement、字段、DQL、PS ID 与时延。
- 本地未配置真实模型，因此不声称已验证生产模型输出质量。
- 未实现 P2 影子 Challenger、P3 企微闭环、P4 Loop Controller 或 P5 正式双投影。
- Candidate 仍为校准期 `NOT_ELIGIBLE`，无接口可将本 P1 生成物直升 approved。
