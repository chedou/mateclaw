# P3 证据源适配器运行说明

> 状态（2026-07-31）：**T6 显式租户授权门已实现；CSDP `csp-rpc-msg`
> 的 `log_search` / `log_trace_bundle` / `contrast_sample` 三份查询合同已在真实 Guance
> 环境运行，并首次产出 Guance-only `FULL_SPINE_OBSERVED`。**
> 本次只是一次不持久化的真源预览；还没有 owner `ACCEPTED` 记录，也没有进入 T8
> 历史样本台账。`fixtureMode` 仍为 `true`，默认数据源均关闭，不得把单条成功改写为
> “T7/T8 已通过”。CloudDial `synthetic_probe` 仍需独立完成真实返回验收。

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
- Guance 与 replay 共用代码内的 canonical schema；缺列、错类型、多 series 无法在单个查询结果内对齐或无法判定最新时间点均按畸形响应降级。
- Guance 在任何凭据读取或 HTTP 调用前，必须唯一命中
  `workspaceId + system + service + signalKind → concrete binding`；缺失、重复或归一后歧义均返回 `MISSING`。
- `log_search.target.search_term` 接受经场景映射后的安全错误码或关键词，同时匹配结构化
  `error_code` 和日志 `message`；不直接插入任意原始报障文本。
- `log_trace_bundle` 只接受一个行集 series；每行的 `message` 必须是同一条 Guance 日志的 JSON 原文，
  Adapter 只在内存中从该原子记录提取白名单字段并立即丢弃原文。跨 series 按行号拼接会把不同日志误配，
  因此一律 fail closed。所有行的 PS ID 必须与搜索阶段相等，再按规范字段稳定排序。
  Guance 请求的 `query.limit` 取 `max-rows + 1` 作为溢出哨兵，trace DQL 本身不得再写 `LIMIT`；
  本地只接受不超过 `max-rows` 的结果，因此第 `max-rows + 1` 条不会被服务端 DQL 提前隐藏。
- `contrast_sample` 是一个 signal contract、一次 Router/HTTP 源调用，但内部包含四个 DQL component：
  失败/成功 cohort 各有“样本总数”和“固定特征命中数”。失败终态固定查询 `failed AND sendmsg`，
  成功终态固定查询 `success AND sendmsg AND NOT failed`；四项都按 `@trace_id` 去重，并使用同一
  `timeRange` 和单桶 `window_span` rollup，禁止把普通 `NOT failed` 日志当成功样本或用同一状态条件自证差异。
- 渲染后的 DQL 只留在适配器内发给 Guance，不写入 canonical `EvidenceResult.query`，避免平台方言上泄。
- 没有注册任何生产写工具，命中路径仍然是确定性 Java，LLM 调用数为 0。
- P4 只注册一个只读取证工具；即使直接调用该工具，没有活动 triage 会话也只会返回 `MISSING`。

## 2. 数据源与配置

默认配置在 `mateclaw-server/src/main/resources/application.yml`：

- `routes.CSDP.{log_count,log_search,log_trace_bundle,incident_impact,metric,trace}`：顺序为
  `guance → recorded-replay`；
- `default-sources: []`：其他系统没有显式路由时不会猜数据源；
- Guance 与 replay 都默认 `enabled=false`；
- `guance.asset-bindings: []` 默认仍为空，没有默认 workspace 或默认资产授权；
- 试点配置单独放在 `mateclaw-server/src/main/resources/application-csp-clouddial-pilot.yml`，
  只有显式激活 `csp-clouddial-pilot` Profile 并提供 workspace ID 后才会加载；
- CSDP 日志竖线单独放在
  `mateclaw-server/src/main/resources/application-csdp-guance-evidence-pilot.yml`，只有显式激活
  `csdp-guance-evidence-pilot` 才会加载三份 Guance-only 合同；
- 该日志试点使用 `native-curl` transport 适配当前本机 TUN 网络。API Key 和请求体通过
  stdin 传给 curl，不进 argv、临时文件或子进程环境；启动参数 `-q` 禁用用户级 `.curlrc`，
  非零退出也不回显 stderr 或凭据；
- 失败/成功 24 小时 cohort 当前需要扫描较多日志，因此仅该试点将 HTTP 上限显式设为 45 秒；
  这不是隐藏延迟，Workspace owner 仍须在 T7 记录真实往返并决定是否接受；
- 该 Profile 增加 `routes.csp-deployment.synthetic_probe: [guance]`，不会回退到伪造的健康数据；
- `csp-prm-miniapp-synthetic-probe` 绑定使用 `D::http_dial_testing`，任务名为
  `客服数字化平台-首页-可用性监控`；它来自本次部署快照，真实返回列仍需 T7 核实；
- curl 中的 `maxPointCount/interval/align_time/slimit/disable_sampling/tz` 作为该 binding 的
  `query-options` 保存；它们不会改变其他日志、指标或调用链 binding 的报文；
- 除本次已运行的 CSDP SendMsg 三份合同外，其他 CSDP 查询模板仍是**未核实草案**，
  measurement、返回列和阈值都要经过 T7。

启用观测云前，在部署环境设置：

```bash
SPRING_PROFILES_ACTIVE=csp-clouddial-pilot
MATECLAW_TROUBLESHOOTING_CSP_WORKSPACE_ID=<运行该 SOP 的 MateClaw workspace id>
MATECLAW_TROUBLESHOOTING_GUANCE_ENABLED=true
MATECLAW_TROUBLESHOOTING_GUANCE_BASE_URL=https://<已批准的 Guance HTTPS 端点或 TLS 代理>
MATECLAW_TROUBLESHOOTING_GUANCE_API_KEY=<通过密钥系统注入>
```

如部署已有其他 Spring Profile，应将 `csp-clouddial-pilot` 追加到原列表，不要覆盖数据库等现有 Profile。
用户提供的 `http://df-openapi.prd.sangfor.com` 已记录在试点配置中。该端点使用明文 HTTP，系统默认会拒绝携 Key 访问；
仅在本地联调进程中、获得操作员本次明确授权后，才可临时设置
`MATECLAW_TROUBLESHOOTING_GUANCE_ALLOW_INSECURE_HTTP=true`。该例外不改变正式部署策略：生产进程不得开启，
后续仍应切换到 HTTPS 端点或受控 TLS 代理。

凭据生命周期由环境 owner 依组织安全政策管理；一旦怀疑存在未授权使用必须立即轮换。
无论是否轮换，运行时 Key 都只能从密钥系统或本地忽略的环境文件注入，不得写入仓库、日志或测试报告。

CSDP SendMsg 真源试点的本地启用参考（值仍由环境注入）：

```bash
SPRING_PROFILES_INCLUDE=csdp-guance-evidence-pilot
MATECLAW_TROUBLESHOOTING_CSDP_WORKSPACE_ID=1
MATECLAW_TROUBLESHOOTING_GUANCE_ENABLED=true
MATECLAW_TROUBLESHOOTING_GUANCE_BASE_URL=http://df-openapi.prd.sangfor.com
MATECLAW_TROUBLESHOOTING_GUANCE_API_KEY=<通过密钥系统或忽略文件注入>
MATECLAW_TROUBLESHOOTING_GUANCE_ALLOW_INSECURE_HTTP=true
```

YAML 中以 `@` 开头的 Guance 列名必须用 Spring Map 原样键语法，例如
`"[@trace_id]": ps_id`；仅写 `"@trace_id"` 会被 relaxed binding 归一为 `trace_id`，导致真源返回的字段无法进入
canonical row。

其他场景仍必须在部署侧的外部配置中登记精确授权（以下仅示意，binding 名仍需 T7 核实）：

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
`MATECLAW_TROUBLESHOOTING_GUANCE_ALLOW_INSECURE_HTTP=true`；本次 `.prd` 主机的本地联调属于操作员显式批准的
一次性例外，不得写入仓库配置、不得继承到生产进程，联调结束后应关闭。生产环境不得开启。

不要把 API Key 写入 YAML、日志、故障原文或 replay JSON。适配器按观测云 Open API 要求把它放入
`DF-API-KEY` 请求头，并调用 `POST /api/v1/df/query_data_v1`。参考：
[Open API 概览](https://docs.guance.com/en/open-api/)、
[DQL 查询接口](https://docs.guance.com/en/open-api/query-data/query-data-v1/)。

离线演练可单独开启脱敏回放：

```bash
MATECLAW_TROUBLESHOOTING_REPLAY_ENABLED=true
```

随仓 catalog 包含合成的 `order-svc / 903001 / synthetic-trace-903001`、无错误码的
`csdp-session-service / 会话消息发送失败 / synthetic-ps-message-send-001`，以及 2026-07-31
脱敏聚合快照 `csp-rpc-msg / IM1010`（失败 2/2、成功 0/14047）。回放键允许
`errorCode` 缺省；三者都只用于回归合同，`IM1010` 的历史来源也不把回放变成在线生产事实。

### 2.1 部署拓扑拨测场景与 Tool 触发入口

管理员可在正式 `/troubleshooting` 工作台的“发起排障”中选择“部署拓扑拨测分析”，绑定一个尚未关闭的
Diagnosis，再从 Workspace 共享拓扑图库选择已导入资产，或导入新的
`chain-board.runtime-topology-snapshot` JSON。正式场景接口为：

```http
POST /api/v1/troubleshooting/diagnoses/{diagnosisId}/topology-probe-runs
X-Workspace-Id: <当前 MateClaw workspace id>
Content-Type: application/json

{"topologyId": "<Workspace 拓扑资产 ID>"}
```

历史运行通过同一路径 `GET` 查询，并按 Workspace 与 Diagnosis 双重隔离。原有
`/api/v1/troubleshooting/sops/deployment-topology/**/analyze` 只保留为兼容与资产预览入口，不代表新的
诊断主链。

服务端对输入执行 512 KiB、100 节点、300 链路、最多 32 个可执行拨测的上限与秘密字段检查，逐个解析节点。只有同时包含
`url` 与 `guance_url` 的节点会生成 `synthetic_probe` 请求；`guance_url` 只提供拨测任务身份和最多 24 小时的
窗口，不能控制 Guance API 主机或 DQL。每个请求均通过唯一的 `EvidenceSourceRouter`，并把允许源硬限制为
`guance`，不会回退 Recorded Replay。批量执行最多 8 路并发、共享 25 秒总预算；超时节点降级为
`UNAVAILABLE`，已完成节点的证据仍返回。当前样例识别 21 个节点、27 条链路、1 个可执行拨测。

响应只投影节点覆盖、HTTP 状态、canonical 目标/任务身份、证据引用和与失败节点相邻的拓扑提示；相邻链路
不等于已证明的故障 hop 或根因。`topology_synthetic_probe` Tool 不调用模型；正式 Diagnosis 场景只持久化
脱敏安全投影及证据引用，不返回或落库 API Key、DQL、原始响应。兼容预览入口仍不持久化结果。两条入口都不会
把未配置拨测的节点描述为健康。一次成功运行仍只是当前部署快照的只读观测，不代表 T7/T8 已通过。
该能力是 `deployment_topology_probe` 场景 Playbook 的首个只读 Tool，不替代错误码、其他场景 Playbook、
开放探索或证据能力；Guance CloudDial 只是首个 Adapter。通用 Tool Registry 仍在更多真实 Tool 合同稳定后
按统一 SPI 演进，本次不提前伪造空注册平台。

## 3. canonical 字段

| `signalKind` | `EvidenceResult.observed` 字段 |
|---|---|
| `synthetic_probe` | `status_code`, `target_url`, `probe_name` |
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
8. per-binding verification 已由 V184 实现；只有 Workspace owner 可在正式工作台逐项确认后提交，服务端会再次执行
   Guance-only 两步读链，并把验收绑定到端点、路由、查询模板、行数预算和字段映射的 SHA-256 指纹。
   配置变化后旧记录自动 `STALE`。当前默认环境没有真实 `ACCEPTED` 记录；只有 T7/T8 真实证据完成后
   才讨论关闭 `fixtureMode`。

正式工作台会先读取：

```http
GET /api/v1/troubleshooting/evidence/guance/acceptance?system=CSDP&service=csdp-session-service
X-Workspace-Id: 1
```

Workspace owner 完成上面清单后提交（普通 admin 无权代为验收）：

```http
POST /api/v1/troubleshooting/evidence/guance/acceptance
X-Workspace-Id: 1
Content-Type: application/json

{
  "system": "CSDP",
  "service": "csdp-session-service",
  "searchTerm": "message_send_failed",
  "window": "-15m",
  "occurredAt": "2026-07-20T09:13:00Z",
  "checklist": {
    "measurementAndFieldsVerified": true,
    "indexVerified": true,
    "psIdJoinVerified": true,
    "timestampUnitVerified": true,
    "timeWindowVerified": true,
    "dqlLatencyReviewed": true,
    "legacyRouteConflictReviewed": true
  }
}
```

请求不能提交 binding fingerprint、验证计数、PS ID、actor 或验收状态；这些都由服务端重新计算。
V184 只保存配置指纹、结构计数、PS ID 哈希、应用侧耗时和审计主体，不保存搜索键、PS ID 原文、
DQL、凭据或日志。Guance T8 采集与基线复跑都在任何 Router 调用前要求当前指纹为 `ACCEPTED`；
Recorded Replay 仍走独立 fixture capability，不会被真源验收状态伪装或放开。

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

### 7.1 首次真实竖线记录（2026-07-31）

本地显式激活 `csdp-guance-evidence-pilot` 后，已用同一
`EvidenceSpineOrchestrator` 在真实 Guance 环境运行：

- `log_search`：在一个 24 小时聚合桶内返回 `match_count / ps_id / sample_message`，观测到 `matchCount=2`；
- `log_trace_bundle`：用搜索阶段的同一 PS ID 查得 3 条原子日志记录；Adapter 在内存中从每条 JSON
  记录提取 `trace_id / level / msg`，canonical `service` 取自服务端已授权的 `csp-rpc-msg` binding；
  JSON 的 `source` 是源码位置，不冒充服务名，也没有做跨 series 序号拼接；
- `contrast_sample`：失败 cohort 与带显式 `success` 终态标记的成功 cohort 都非空，四项都按 PS ID
  去重；固定特征在失败 cohort 的命中率严格高于成功 cohort。两个 cohort 各自使用一个 24 小时单桶聚合，
  最终脱敏预览快照为失败 `2/2`、成功 `0/14047`；这组数值已作为带日期的录制回放快照冻结，
  只用于可复算回归，不是对实时样本总量的永久阈值。
  `success` 标记的业务语义仍须 owner 在 T7 正式确认；
- 预览返回 `FULL_SPINE_OBSERVED`，`sourceRequestCount=3`，三个步骤均为
  `CANONICAL_RESULT_OBSERVED`，且没有 Recorded Replay 回退。

早期使用同一状态条件构造对照的结果已经废弃，不作为成功样本证明。当前记录没有保存完整 PS ID、
原始日志、DQL 或 API Key。修正后的运行只证明“三次真源取证 + 确定性压缩 + 独立 cohort 对照投影”
可执行；失败样本仍很小，不能外推为通用判据。仍需 Workspace owner 核对索引、时间窗与 DQL 延迟并提交
T7 acceptance；随后才能将真实样本进入 T8 台账。

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
