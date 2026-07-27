# P3 证据源适配器运行说明

> 状态（2026-07-27）：**工程链路已实现，903001 观测云绑定尚未在内网核实。**
> 因此 `fixtureMode` 仍为 `true`，默认数据源均关闭，不能把当前结果表述为“真实取证已验证”。

## 1. 已落地的链路

命中路保持确定性、零 LLM：

```text
SopEntry.evidenceRequests（平台无关意图）
  → TroubleshootingIntakeService（仅补齐缺失请求）
  → EvidenceSourceRouter（system + signalKind，主源/后备源）
  → GuanceEvidenceAdapter | RecordedReplayAdapter
  → EvidenceResult.observed（canonical 字段）
  → CriterionEvaluator（零 LLM）
```

P4 未命中路复用同一 Router，但只能从服务端受限会话进入：

```text
route miss
  → TroubleshootingAgentTriageService（开关/专用 Agent/直接绑定校验）
  → AgentService.chatWithToolAllowlist（最终只保留 collect_troubleshooting_evidence）
  → TroubleshootingEvidenceTool（活动会话 + 次数上限）
  → EvidenceSourceRouter → EvidenceSourceAdapter
  → 证据引用核验 → Diagnosis(LLM_FALLBACK)
```

专用 Agent 的配置、启用和回滚见 `agent-miss-path-runbook.md`。该路径默认关闭；它不会改变命中路的
零 LLM 合同。

- 调用方已提供且状态不是 `MISSING` 的证据会保留，不重复查询。
- 主源抛异常、超时、返回畸形响应或 `MISSING` 时，路由器尝试下一个显式后备源。
- 全部失败时返回 `EvidenceStatus.MISSING`；必需证据缺失会触发现有 abstain 逻辑，不输出恢复动作。
- 取证默认强制 `https`；仅可信隔离测试网可显式允许 `http`。模板值使用保守字符白名单，阻止告警载荷拼成任意 DQL。
- Guance 与 replay 共用代码内的 canonical schema；缺列、错类型、多 series 或无法判定最新时间点均按畸形响应降级。
- 没有注册任何生产写工具，命中路径仍然是确定性 Java，LLM 调用数为 0。
- P4 只注册一个只读取证工具；即使直接调用该工具，没有活动 triage 会话也只会返回 `MISSING`。

## 2. 数据源与配置

默认配置在 `mateclaw-server/src/main/resources/application.yml`：

- `routes.CSDP.{log_count,metric,trace}`：顺序为 `guance → recorded-replay`；
- `default-sources: []`：其他系统没有显式路由时不会猜数据源；
- Guance 与 replay 都默认 `enabled=false`；
- Guance 的三个查询模板是**未核实草案**，measurement、返回列和阈值都要经过 T2。

启用观测云前，在部署环境设置：

```bash
MATECLAW_TROUBLESHOOTING_GUANCE_ENABLED=true
MATECLAW_TROUBLESHOOTING_GUANCE_BASE_URL=https://<实际观测云地址>
MATECLAW_TROUBLESHOOTING_GUANCE_API_KEY=<通过密钥系统注入>
```

默认拒绝通过明文 HTTP 发送 API Key。只有可信且隔离、确实没有 TLS 的测试网才可临时设置
`MATECLAW_TROUBLESHOOTING_GUANCE_ALLOW_INSECURE_HTTP=true`，生产环境不得开启。

不要把 API Key 写入 YAML、日志、故障原文或 replay JSON。适配器按观测云 Open API 要求把它放入
`DF-API-KEY` 请求头，并调用 `POST /api/v1/df/query_data_v1`。参考：
[Open API 概览](https://docs.guance.com/en/open-api/)、
[DQL 查询接口](https://docs.guance.com/en/open-api/query-data/query-data-v1/)。

离线演练可单独开启脱敏回放：

```bash
MATECLAW_TROUBLESHOOTING_REPLAY_ENABLED=true
```

随仓样本只含合成的 `order-svc / 903001 / synthetic-trace-903001`，用于回归合同，不代表生产事实。

## 3. canonical 字段

| `signalKind` | `EvidenceResult.observed` 字段 |
|---|---|
| `log_count` | `count`, `trace_id` |
| `metric` | `reachable`, `connections_current`, `connections_available`, `slow_query_count`, `baseline_slow` |
| `trace` | `failed_hop`, `status`, `duration_ms` |

平台返回列与上述字段不同，在 `field-aliases` 中维护“源字段 → canonical 字段”；代码内共享 schema
是所有适配器使用证据前的失败闭合闸门。不要改 SOP 判据来迁就平台。

## 4. 状态检查

登录后以 workspace viewer 身份调用：

```http
GET /api/v1/troubleshooting/evidence/sources
```

状态语义：

- `DISABLED`：开关关闭；
- `DEGRADED`：配置不完整、回放文件不可读，或观测云尚无一次合法响应；
- `READY`：适配器可用或观测云至少返回过一次可归一响应；
- `verified=false`：当前始终诚实保留。`READY` 只表示技术可达，不表示 measurement、字段和阈值已经业务核实。

该接口不主动探测，不返回 Base URL、API Key 或 DQL 内容。

## 5. T2 内网验收清单

1. 用 903001 历史时间窗逐条执行 `log_count / metric / trace`，保存脱敏后的原始响应结构。
2. 核对 measurement、过滤 tag、返回列与 `field-aliases`，保证 canonical 字段都有值且类型正确。
3. 验证无数据、401/403、超时、5xx、响应结构变化都只生成 `MISSING`，HTTP 报障入口不返回 500。
4. 用 20–30 条历史故障标定连接占用、慢查询基线等阈值，比较自动结论与人工结论。
5. owner 审核绑定和阈值后，再设计 per-binding verification 状态；只有 T2/T3 完成后才讨论关闭 `fixtureMode`。

## 6. 回归命令

```bash
JAVA_HOME=<JDK21> mvn -pl mateclaw-server -am \
  -Dtest='vip.mate.troubleshooting.**.*Test' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

适配层重点测试：`EvidenceSourceRouterTest`、`GuanceEvidenceAdapterTest`、
`RecordedReplayAdapterTest`、`TroubleshootingIntakeServiceTest`。
