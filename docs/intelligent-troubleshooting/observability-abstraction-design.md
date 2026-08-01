# D8 · 证据源开放适配 —— Observability Abstraction Layer (OAL)

> 记忆文档（供后续 AI / 工程师直接接续）。目的：让证据源**开放、可插拔**——观测云只是首个适配器，
> 后续可接入 Zabbix、Prometheus、日志平台等，每个平台做好适配。
>
> 已锁定决策：**D-A** 绑定放注册表（SOP 存意图）· **D-B** 按 system+signal 路由 · **D-C** 先归一 903001 用到的信号。

---

## 一、第一性原理

一次证据 = **对某个「语义信号」的一次观测**。语义信号（"Mongo 连接数""error_code=903001 的日志计数"
"某 trace 的失败跳"）是**平台无关**的；观测云 / Zabbix / Prometheus / 日志平台只是**回答它的不同方式**。

当前耦合点：`SopEntry.evidence_queries[].query` 直接写了 DQL 字符串、`namespace` 是 `L::/M::`——把
"要观测什么"和"在观测云上怎么查"焊死了。开放化 = 把三件事拆开：

| 关注点 | 归属 | 变化频率 |
|---|---|---|
| 要观测什么（意图） | SOP / 知识库 | 随 SOP |
| 在平台 P 上怎么查（查询构造） | 每平台绑定 + 适配器 | 每平台一次 |
| P 的响应怎么归一（规范化） | 每平台适配器 | 每平台一次 |

---

## 二、三层抽象

```
SopEntry.evidence_requests[]        ← 平台无关「意图」(EvidenceRequest)
        │
        ▼
EvidenceSourceRouter                ← 按 (system, service, signal_kind) 选平台适配器（主+备）
        │
        ▼
EvidenceSourceAdapter (每平台一个)  ← 造查询 → 执行 → 归一
   guance / zabbix / prometheus / loki / recorded / fixture
        │
        ▼
EvidenceResult{ observed: 规范化字段 }  ← anomaly_criteria 规则引擎跑在这上（已平台无关）
```

**规范化证据模型是地基**：只要各适配器把响应归一到**同名 canonical 字段**，同一条 `anomaly_criteria`
规则、`diagnosis_rules`、回归集在观测云和 Zabbix 上都零改动成立。现有规则引擎读的就是 `observed` dict，
我们只是把"谁来填这个 dict"变成可插拔。

---

## 三、契约草案（实现参考，非最终签名）

### 3.1 EvidenceRequest（进 SopEntry，平台无关）
```python
class EvidenceRequest(BaseModel):
    request_id: str
    signal_kind: str            # "log_count" | "metric" | "trace" | "dialtest" | ...
    target: dict[str, Any]      # 规范化目标字段，见 §4；如 {service, error_code, host, metric, trace_id}
    window: str | None = None   # 相对时间窗，如 "-15m"；适配器翻译成各平台格式
    required: bool = True
```
> `SopEntry.evidence_queries: list[EvidenceQuery]` → `evidence_requests: list[EvidenceRequest]`。
> 旧 `EvidenceQuery` 的 `namespace`/`query`（DQL 串）移出 SOP，进平台绑定（§5）。

### 3.2 EvidenceSourceAdapter（每平台一个，Protocol）
```python
class EvidenceSourceAdapter(Protocol):
    platform: str                                  # "guance" | "zabbix" | "recorded" | ...
    def supports(self, signal_kind: str) -> bool: ...
    def health(self) -> SourceHealth: ...          # ready/degraded + detail，供 /readyz 汇总
    def collect(self, request: EvidenceRequest, incident: IncidentContext) -> EvidenceResult: ...
```
- 内部三步：① 用绑定注册表把 `request` 翻成平台查询；② 执行；③ **归一**成 canonical `observed` + `EvidenceResult`。
- **fail-closed**：任何异常/超时 → `EvidenceResult(status=MISSING, source="{platform}:unavailable")`，不抛 500（沿用现有 orchestrator 降级）。
- `EvidenceCollector`（现有 Protocol）就是本接口的前身；`FixtureEvidenceCollector` / `RecordedReplayAdapter` 都是它的实现。

### 3.3 EvidenceSourceRouter（选源 + 多源聚合）
```python
class EvidenceSourceRouter:
    # 配置：(system, signal_kind) -> [primary_adapter, *fallbacks]
    def collect(self, request, incident) -> EvidenceResult:
        # 按配置选适配器；primary MISSING 且有 fallback 则尝试；全 MISSING 则 required 触发降级
```
- 一个系统可多平台混用：日志→Loki、指标→Zabbix、链路→Guance。
- `verification_status` 从"一条 DQL 是否核实"升级为"**某平台某绑定是否核实**"——每平台各自毕业。

---

## 四、归一化词汇表（D-C：903001 + P6 日志学习前置）

> 原则：小而精，别一次定全。各适配器 `collect` 后必须把响应归一到下列 `observed` 字段名。

| signal_kind | canonical `observed` 字段 | 用于的判据（现 903001 fixture） |
|---|---|---|
| `log_count` | `count`, `trace_id` | `numeric_gte(count, 1)` → 确认发生 |
| `log_search` | `match_count`, `ps_id`, `sample_message` | 场景关键词取样并抽取 PS ID；真实字段待 T7 |
| `log_trace_bundle` | `ps_id`, `entries[]`（时间/服务/级别/消息，耗时可选） | 同一 PS ID 的有界全链路日志包；供后续确定性压缩 |
| `metric`（mongo 可用性/连接） | `reachable`(bool), `connections_current`, `connections_available`, `slow_query_count`, `baseline_slow` | `missing_or_lte` 可用性 / `ratio_of_sum_gt(current,available)` 连接打满 / `multiple_gt(slow,baseline)` 慢查询 |
| `trace` | `failed_hop`(str), `status`, `duration_ms` | `contains_and_in(status,...)` 定位 DB 失败跳 |

> `target` 的规范化字段（跨 signal 复用）：`service`、`error_code`、`host`、`metric`、`trace_id`、`window`。

---

## 五、绑定注册表（D-A：SOP 存意图，绑定按平台维护一次）

```yaml
# bindings/guance.yaml —— 观测云平台绑定（一个 signal_kind 一条模板）
platform: guance
bindings:
  log_count:
    query: "L::{{source}}:(error_code,trace_id) {service='{{service}}',error_code='{{error_code}}'} [{{window}}]"
    normalize: { count: "$.result.length", trace_id: "$.result[0].trace_id" }
  metric:
    query: "M::mongodb:(connections_current,connections_available,slow_query_count,baseline_slow) {host='{{host}}'} [{{window}}]"
    normalize: { connections_current: "$.series.connections_current", baseline_slow: "$.series.baseline_slow", ... , reachable: "$.series != null" }
```
```yaml
# bindings/zabbix.yaml —— 同样的 signal_kind，换平台绑定；SOP 完全不动
platform: zabbix
bindings:
  metric:
    api: "item.get"
    params: { host: "{{host}}", keys: ["mongodb.connections[current]", ...] }
    normalize: { connections_current: "$[0].lastvalue", ... }
```
**加 Zabbix = 加一个 `bindings/zabbix.yaml` + 一个 ZabbixAdapter；不动任何 SOP、不动任何 anomaly_criteria。**

---

## 六、源路由配置（D-B）

```yaml
# routing.yaml —— 按 system + signal 选平台（主+备）
routes:
  - match: { system: "CSDP" }
    sources: { log_count: [guance], metric: [guance], trace: [guance] }
  - match: { system: "X" }
    sources: { log_count: [loki], metric: [zabbix, prometheus], trace: [guance] }
default:
  sources: { "*": [recorded, fixture] }   # 离线/开发态兜底
```

---

## 七、与现有代码的接缝（改动可控）

1. `models.py`：`EvidenceQuery` → 拆为 `EvidenceRequest`（进 `SopEntry.evidence_requests`）；平台查询移入绑定。
2. `ports.py`：`EvidenceCollector` 泛化为 `EvidenceSourceAdapter`（加 `platform` / `supports` / `health`）。
3. 新增 `EvidenceSourceRouter` + 绑定/路由配置加载；`orchestrator._collect_evidence` 改为调 Router（fail-closed 降级不变）。
4. `fixtures.py` 的 903001：`evidence_queries`(DQL) → `evidence_requests`(意图) + `bindings/guance.yaml` 里的对应模板；`FixtureEvidenceCollector` 变 `FixtureAdapter`。
5. `RecordedReplayAdapter`：吃"真实平台响应录制"，与上一轮的录制/回放一致，供回归集离线跑。

---

## 八、这如何强化上一轮计划（录制/回放 + 回归）

- 回归集跑在**归一化证据**上、**与来源无关**；一次真实 Guance 或 Zabbix 响应录制下来，既核实了该平台绑定、
  又成了跨平台可复用的回归资产。多平台不是额外负担，而是让"证明一次、到处复用"成立。

## 九、实施状态（2026-07-27）

- [x] 落 `EvidenceRequest` / `EvidenceSourceAdapter` / `EvidenceSourceRouter` 契约（Java）。
- [x] 903001 SOP 运行态使用平台无关 `EvidenceRequest`；Guance 草案绑定移入应用配置。
- [x] 写 `RecordedReplayAdapter` + 一份脱敏 903001 样本，并锁定精确键匹配与失败降级测试。
- [x] 写 `GuanceEvidenceAdapter`，按官方 query-data API 请求并归一响应；**内网字段/阈值仍待 T7 核实**。
- [x] P6 前置：增加 `log_search` / `log_trace_bundle`，日志包校验请求/返回 PS ID、按时间排序、
  以溢出哨兵限行，DQL 留在适配器内；增加「会话消息发送失败」无错误码脱敏回放。
- [x] T11 前三步：从回放/Guance 统一 Router 执行 `log_search → log_trace_bundle`，模型之前用
  `DeterministicLogTraceCompressor` 压成有界调用链骨架；原始字符与 24h 时间窗均有硬上限，回放键精确绑定
  search term / PS ID；只读 preview 不暴露原始日志/DQL，不调模型、不入库。该路径当前只允许显式登记的
  fixture workspace/service，并在 Router 调用前硬限 `recorded-replay`；T6 授权机制已实现，但默认映射为空，
  真实 Guance 放行仍依赖 owner 配置并复核具体 workspace 资产映射。
- [x] `GET /api/v1/troubleshooting/evidence/sources` 汇总源级 `health`，不暴露凭据和查询。
- [x] P2 T6：`workspaceId` 贯穿唯一 Router/Adapter 脊柱；Guance 以精确
  workspace/system/service/信号→concrete binding 映射授权，默认空且在 transport 前 fail closed。
- [ ] 用真实观测云完成 per-binding `verification_status`，并按需汇入全局 `/readyz`。
- [ ] 增加 Zabbix 或其他第二平台，验证新增适配器确实不改 SOP 与判据。

运行和验收步骤见 `evidence-adapter-runbook.md`。
