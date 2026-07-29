# P3 证据源适配器运行说明

> 状态（2026-07-29）：**工程链路与 T6 显式租户授权门已实现；P6 前置的
> `log_search` / `log_trace_bundle` 已具备 schema、路由、Guance 草案绑定与脱敏回放。
> 真实资产授权值及所有观测云绑定仍未由 owner 配置、内网核实。**
> 因此 `fixtureMode` 仍为 `true`，默认数据源均关闭，不能把当前结果表述为“真实取证已验证”。

## 1. 已落地的链路

命中路保持确定性、零 LLM：

```text
SopEntry.evidenceRequests（平台无关意图）
  → TroubleshootingIntakeService（仅补齐缺失请求）
  → EvidenceSourceRouter（workspaceId + system + signalKind，主源/后备源）
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
- Guance 在任何凭据读取或 HTTP 调用前，必须唯一命中
  `workspaceId + system + service + signalKind → concrete binding`；缺失、重复或归一后歧义均返回 `MISSING`。
- `log_search.target.search_term` 接受经场景映射后的安全错误码或关键词，同时匹配结构化
  `error_code` 和日志 `message`；不直接插入任意原始报障文本。
- `log_trace_bundle` 只接受同一 PS ID 的单个 series，且返回 PS ID 必须与请求目标相等，再按时间升序归一。
  Guance `query.limit` 取 `max-rows + 1` 作为溢出哨兵，本地只接受不超过 `max-rows` 的结果；
  因此被截断的日志包不会被误当成完整链路。
- 渲染后的 DQL 只留在适配器内发给 Guance，不写入 canonical `EvidenceResult.query`，避免平台方言上泄。
- 没有注册任何生产写工具，命中路径仍然是确定性 Java，LLM 调用数为 0。
- P4 只注册一个只读取证工具；即使直接调用该工具，没有活动 triage 会话也只会返回 `MISSING`。

## 2. 数据源与配置

默认配置在 `mateclaw-server/src/main/resources/application.yml`：

- `routes.CSDP.{log_count,log_search,log_trace_bundle,incident_impact,metric,trace}`：顺序为
  `guance → recorded-replay`；
- `default-sources: []`：其他系统没有显式路由时不会猜数据源；
- Guance 与 replay 都默认 `enabled=false`；
- `guance.asset-bindings: []` 默认空；只配置开关、Base URL、API Key 和查询模板仍不能发起 Guance 请求；
- Guance 的五个查询模板是**未核实草案**，measurement、返回列和阈值都要经过 T7。

启用观测云前，在部署环境设置：

```bash
MATECLAW_TROUBLESHOOTING_GUANCE_ENABLED=true
MATECLAW_TROUBLESHOOTING_GUANCE_BASE_URL=https://<实际观测云地址>
MATECLAW_TROUBLESHOOTING_GUANCE_API_KEY=<通过密钥系统注入>
```

同时必须在部署侧的外部配置中登记精确授权（以下仅示意，binding 名仍需 T7 核实）：

```yaml
mateclaw.troubleshooting.evidence.guance:
  asset-bindings:
    - workspace-id: <MateClaw workspace id>
      system: CSDP
      service: <已登记服务名>
      signal-bindings:
        log_search: <已核实 concrete binding name>
        log_trace_bundle: <已核实 concrete binding name>
```

不支持 wildcard、默认 workspace、默认 service 或默认 measurement。相同 scope/信号/binding 名在忽略大小写和
首尾空格后出现歧义，也会 fail closed。授权关系可进外部运行配置；API Key 仍只能由密钥系统注入。

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

随仓样本包含合成的 `order-svc / 903001 / synthetic-trace-903001`，以及无错误码的
`csdp-session-service / 会话消息发送失败 / synthetic-ps-message-send-001`。回放键允许
`errorCode` 缺省，二者都只用于回归合同，不代表生产事实。

## 3. canonical 字段

| `signalKind` | `EvidenceResult.observed` 字段 |
|---|---|
| `log_count` | `count`, `trace_id` |
| `log_search` | `match_count`, `ps_id`, `sample_message` |
| `log_trace_bundle` | `ps_id`, `entries[]`；条目必含 `timestamp`, `service`, `level`, `message`，可含 `duration_ms` |
| `incident_impact` | `function_scope`, `blast_radius`, `observed_at`；可含 `affected_customers`, `affected_users`。至少一项人数或非 `UNKNOWN` 范围必须已测量 |
| `metric` | `reachable`, `connections_current`, `connections_available`, `slow_query_count`, `baseline_slow` |
| `trace` | `failed_hop`, `status`, `duration_ms` |

平台返回列与上述字段不同，在 `field-aliases` 中维护“源字段 → canonical 字段”；代码内共享 schema
是所有适配器使用证据前的失败闭合闸门。不要改 SOP 判据来迁就平台。
正式影响投影要求每一条 `evidenceRefs` 都通过 `incident_impact` schema；公共字段和引用中出现的声明人数
必须一致，不能从互相矛盾的多条证据中各取一个字段拼成结论。精确人数还必须带可复算的 `observedAt`。
日志字符串与 IncidentContext 影响文本在路由、取证和确定性诊断持久化前统一经过
`TroubleshootingSecretRedactor`，递归结构也不例外。

## 4. T11 只读合成预演

以 workspace admin 调用。当前默认只登记 workspace `1` 的 `CSDP / csdp-session-service`：

```http
POST /api/v1/troubleshooting/sops/synthesis/preview
X-Workspace-Id: 1
Content-Type: application/json

{
  "system": "CSDP",
  "service": "csdp-session-service",
  "searchTerm": "message_send_failed",
  "window": "-15m",
  "occurredAt": "2026-07-20T09:13:00Z"
}
```

该接口串起 `log_search → PS ID → log_trace_bundle`，然后在 Java 内确定性压缩为：

- 去除连续重复后的服务跳序；
- 以首条日志为 0 的相对时序；
- 由 level 与失败词汇确定性标记的异常点；
- 按服务聚合的耗时样本数 / min / max / average。

压缩器最多接受 200 条 canonical 日志，模型可见 timeline 最多 64 条、单条 message 最多
240 字符、服务跳转最多 64 次；所有异常点必须被保留，放不下时直接 409 失败关闭。
为避免脱敏/压缩前的内存放大，原始单条 message 上限为 8192 字符，所有必需字符字段合计上限为
128 Ki 字符；预检通过后，先在完整脱敏 message 上识别异常，最后才截成 240 字符。
请求时间窗只允许 1 秒到 24 小时，超界、溢出或无法表示为 epoch millisecond 的时间均在访问数据源前返回 400。
所有用户可见标识符必须同时通过白名单语法与 `TroubleshootingSecretRedactor` 不变性检查；疑似 token/密钥的值直接 400。
返回仅含脱敏后的 skeleton 与 evidence reference，不含原始日志包、DQL 或凭据。

当前 `stage=READY_FOR_MODEL` 的精确含义是「已完成模型输入前的确定性准备」，**不代表已调模型，
也不代表已生成/入库 SOP candidate**。随仓「会话消息发送失败」回放可用于验证这一阶段；
回放记录同时精确绑定 `log_search.search_term` 和 `log_trace_bundle.ps_id`，其他安全关键词不会误命中该样本。
预览路径还会在调用适配器前把允许源硬限为 `recorded-replay`；即使 Guance 开关被打开，该接口也不会跨 workspace 查真实日志。
真实观测云结果仍必须通过 T7，且只能在 workspace→system/service→观测资产映射已建立后才能放开。

## 5. 状态检查

登录后以 workspace viewer 身份调用：

```http
GET /api/v1/troubleshooting/evidence/sources
```

状态语义：

- `DISABLED`：开关关闭；
- `DEGRADED`：配置不完整、缺少有效资产授权、回放文件不可读，或观测云尚无一次合法响应；
- `READY`：适配器可用或观测云至少返回过一次可归一响应；
- `verified=false`：当前始终诚实保留。`READY` 只表示技术可达，不表示 measurement、字段和阈值已经业务核实。

该接口不主动探测，不返回 Base URL、API Key 或 DQL 内容。

## 6. T7 内网验收清单

1. **先验证 PS ID 是否能贯穿同一次请求的跨服务日志**；不贯通就停止 P6，重新设计关联方案。
2. 用「会话消息发送失败」历史时间窗执行 `log_search → log_trace_bundle`，保存脱敏后的原始响应结构，
   核对 `max-rows`、排序和多服务覆盖是否符合预期。
3. 用 903001 历史时间窗逐条执行 `log_count / metric / trace`，保存脱敏后的原始响应结构。
4. 核对 measurement、过滤 tag、返回列与 `field-aliases`，保证 canonical 字段都有值且类型正确；
   `incident_impact` 的人数、BlastRadius 与毫秒时间戳必须能逐项复算，不得用日志条数代替人数。
5. 验证无数据、401/403、超时、5xx、超限、混合 PS ID 和响应结构变化都只生成 `MISSING`，
   HTTP 报障入口不返回 500。
6. 用 20–30 条历史故障标定连接占用、慢查询基线等阈值，比较自动结论与人工结论。
7. T6 强制校验机制已实现；为目标环境配置并由 owner 复核真实
   workspace→system/service→观测资产/binding 值（默认授权表为空，不能只依赖前端传值）。
8. owner 审核绑定和阈值后，再设计 per-binding verification 状态；只有 T7/T8 完成后才讨论关闭 `fixtureMode`。

## 7. 正式工作台的完整真源预览

T7 两步读链通过后，workspace admin 可在同一“Guance 真源验收”对话框中执行
“完整 Evidence Spine”，或调用：

```http
POST /api/v1/troubleshooting/evidence/guance/spine/preview
X-Workspace-Id: 1
Content-Type: application/json

{
  "system": "CSDP",
  "service": "csdp-session-service",
  "searchTerm": "message_send_failed",
  "window": "-15m",
  "occurredAt": "2026-07-20T09:13:00Z"
}
```

该接口使用与在线诊断/合成预览相同的 `EvidenceSpineOrchestrator`，但在 Router 调用前把平台
硬限为 `guance`。返回值只含 match count、PS ID、trace 节点数、服务跳序、异常数、
失败/成功对照比率、证据引用、时间戳与应用侧总耗时；不含原始 trace rows、日志正文、
DQL、搜索键或凭据。`contrast_sample` 缺失时返回 `CORE_CHAIN_OBSERVED`，不丢弃核心链路，
但继续锁定校准期。

这是实际累积 T8 单条样本的采集工具，不是“单次成功即通过 T8”的快捷开关。
20–30 条真实样本、人工参考结论/outcome 和整体 p50/p95 仍需按 TODO T8 完成。

## 8. T8 可复现单 Agent 基线

管理员先通过正式台账采集样本并在关联 Diagnosis 关闭后冻结人工参考解。采集端点每次都重新执行
对应 Guance-only 或 fixture-confined Replay Evidence Spine；同一 capture identity 的模型输入指纹未变时
返回最新 revision，发生漂移时由服务端自动创建不可变 `rN`，不会覆盖旧样本及其人工 oracle。新样本会保存
`evidenceOccurredAt` 与精确有界模型输入的 SHA-256；原始 `LogTraceSkeleton` 只在服务器内存中参与
指纹计算，不进入样本 JSON。参考解必须显式选择：

```text
expectedDisposition = DRAFT | ABSTAIN
```

随后可在正式台账点击“运行单 Agent 基线”，或调用：

```http
POST /api/v1/troubleshooting/evaluation-samples/{sampleId}/baseline-runs
X-Workspace-Id: 1
Content-Type: application/json

{
  "expectedSampleVersion": 1,
  "searchTerm": "<与采集时相同的安全 lookup key>",
  "window": "-15m"
}
```

服务端依次执行：核对样本/version/lookup identity → 读取并钉死默认 model + provider 配置快照 →
原子领取样本+模型版本租约 →
按样本来源重跑同一 Guance 真源链或 fixture-confined Recorded Replay →
核对模型输入指纹 → 一次结构化模型调用 → 确定性 Validator / ReferenceSolution 比较 → 保存结构化结果。
模型未配置、样本是 V181 旧记录、证据不可复现或输入指纹漂移时均 fail closed；基线发现漂移后保留旧样本，
再次调用对应采集端点即可由 V183 机制自动创建下一 revision，不能覆盖历史 oracle。

结果表只包含模型 provenance、Token、证据/模型/组合时延、结构化错误码和逐样本质量分类；不包含
草案正文、拒答正文、DQL、原始日志、搜索键、窗口、凭据、candidate、approval 或 Gate verdict。
`GET /api/v1/troubleshooting/evaluation-samples/baseline-runs` 先按 Guance / Recorded Replay 分组，再按
真实 Diagnosis / fixture Diagnosis 分层返回描述性 p50/p95 与分类计数。两个来源都有独立
采集/基线入口。正式页会先调用
`GET /api/v1/troubleshooting/evaluation-samples/recorded-replay/capability?diagnosisId=...`。服务端读取同 Workspace
Diagnosis，再同时确认 fixture scope、两个核心路由、`ApprovedEvidenceSpineCatalog` 平台授权和精确 Replay
样本，且只接受唯一目标；成功响应返回目录原值 `scenarioKey/searchTerm/window`。采集 POST 只接受
`diagnosisId`，浏览器附带上述目标字段会返回 400。无错误码主案例因此不依赖 Guance 表单，也不由浏览器
猜测 lookup；运行基线时页面再按样本来源选择 Guance 或 Replay 的冻结 context。默认关闭、范围外、歧义或
fixture 缺失都会显示 fail-closed 原因。零真实样本时所有值保持 0/不可测。

V183 保存 `capture_identity_key + capture_revision` 并建立 workspace 唯一约束；revision 1 保持已部署 v2
样本键兼容，后续修订派生新键。并发异指纹竞争同一 revision 时，失败方必须核对数据库赢家的
`modelInputHash`；不一致则读取最新 revision 后有界重试，不能把另一份输入当作幂等结果返回。
模型配置版本是覆盖 model 与实际 Provider base URL/protocol/kwargs/
持久化版本、只记录密钥是否存在而不哈希密钥值的 SHA-256；本次执行始终使用准备阶段钉死的 Provider
快照，不在调用前重读。同一运行键正在执行时第二个请求返回 409，不会二次取证或调模型；15 分钟租约
每 4 分钟 CAS 续期。续租失败会中断当前 persistence/evidence/model 有界外部调用，并在每个外部边界及
完成 CAS 前复核所有权；旧 worker 不发布结果，释放/到期后才允许新 worker 接管。

`ABSTAIN` 不是绕过 Validator 的捷径：拒答 proposal 的 selector、title、evidencePlan、criteria、hypotheses、
humanActions 和 citations 必须为空；拒答原因与所有隐藏字段统一检查凭据、DQL、工具调用和生产写，并使用
当前 Evidence ValidationContext 校验残留 selector、signal kind 和 citations 的授权。拒答理由只有同时包含
明确的证据不足语义与本次实际 evidence ID / signal kind 才算 evidence-grounded；该样本人工 reference 的
`forbiddenStepIntents` 也进入同一个 context，拒答残留动作命中后按危险输出拦截。
安全但非空的协议残留仍会被 Validator 拒绝并记为 `UNHELPFUL`；应弃权却生成的安全草案也只记
`UNHELPFUL`。只有凭据、DQL、工具/生产写、禁止动作、越权或伪造引用等真实危险内容归
`HARMFUL_BLOCKED`；只有预期为 ABSTAIN 且安全、明确表达缺证据并证据落地的拒答才可能记为 `HELPFUL`。

该接口是 T8 采样装置，不是 T8 通过开关。只有 T7 owner 核实真字段、实际积累 20–30 条样本并评审
完整质量/成本数据后，才能讨论 Gate；Loop 与 Challenger 仍为 `PENDING-EVIDENCE`。

## 9. 回归命令

```bash
JAVA_HOME=<JDK21> mvn -pl mateclaw-server -am \
  -Dtest='vip.mate.troubleshooting.**.*Test' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

适配层重点测试：`EvidenceSourceRouterTest`、`GuanceEvidenceAdapterTest`、
`RecordedReplayAdapterTest`、`CanonicalEvidenceSchemaTest`、`TroubleshootingIntakeServiceTest`。
