# Guance Skills 融合说明

> 状态：合同已接入统一 Evidence Spine；`error_log_scan` 已通过真实
> Guance 15 分钟聚合烟测，其余两份新合同仍待真实字段验收。

## 1. 融合结论

外部 `skills.zip` 不作为第二套 Agent/脚本运行时安装。它只作为“观测云有哪些查询能力”的输入，
经安全收敛后进入 MateClaw 现有链路：

```text
已审核排障方案（Playbook）
  → EvidenceRequest
  → EvidenceSourceRouter
  → GuanceEvidenceAdapter
  → server-owned DQL 合同
  → CanonicalEvidenceSchema
  → 判据 / 结论 / 弃权
```

压缩包内的 Python 脚本、自由 DQL、明文 API Key、原始日志打印和“依次猜测数据源”逻辑均未复制进系统。

## 2. 能力映射

| 外部 Skill | MateClaw 证据维度 | 状态 | 规范输出 |
|---|---|---|---|
| `guanceyun-log-query` | `log_search` | 已有并复用 | `match_count`, `ps_id`, `sample_message` |
| `guanceyun-log-query` | `log_trace_bundle` | 已有并复用 | 同一 `ps_id` 的有界日志条目 |
| `guanceyun-log-query` | `contrast_sample` | 已有并复用 | 成功/失败样本计数与稳定差异特征 |
| `guanceyun-error-logs` | `error_log_scan` | 本轮新增 | ERROR 数量、受影响链路数、最新关联 ID |
| `guanceyun-error-logs` | `monitor_event_scan` | 本轮新增 | 告警数量、最新级别、最新规则名 |
| `guanceyun-k8s-pod-info` | `k8s_workload_health` | 本轮新增 | Pod/容器计数、异常容器数、CPU/内存高水位 |
| `guanceyun-k8s-pod-info` | `k8s_pod_status` | 通用方法库补充 | 按服务聚合 Pod running / 非 running |
| `guanceyun-k8s-pod-info` | `k8s_node_status` | 通用方法库补充 | 按服务反查 Node/主机数与资源水位 |
| `guanceyun-k8s-pod-info` | `host_status` | 通用方法库补充 | 按服务反查落地主机数与 CPU/内存水位 |
| `guanceyun-dial-testing` | `synthetic_probe` | 已有并复用 | 状态码、目标 URL、拨测任务名 |

这些维度会自动出现在“智能排障 → 取证查询目录”的系统/模块、查询合同、路由与绑定、联调与验收视图中。

## 3. 当前服务端绑定

`application-csdp-guance-evidence-pilot.yml` 在原有三段 SendMsg Evidence Spine 上新增：

- `CSDP / csdp-session-service / error_log_scan`
- `CSDP / csdp-session-service / monitor_event_scan`
- `CSDP / csdp-session-service / k8s_workload_health`

其中监控事件合同必须由已审核证据请求提供精确 `monitor_checker`，禁止以模块身份执行全站告警扫描。
K8s 合同只接受已审核证据请求提供的 `deployment` 和 `namespace`。这些参数都必须是安全资源标识；
包含空格、引号或查询表达式的值会在发起 HTTP 前被拒绝。系统不会在 `docker_containers`、
`kubelet_pod` 等数据源之间运行时猜测，也不会跨 Namespace 自动扩大查询范围。

拨测能力继续由 `application-csp-clouddial-pilot.yml` 的 `synthetic_probe` 合同提供，
没有新建第二条“拓扑拨测结果”链路。

## 4. 安全边界的实现落点

本集成完整遵循 [RFC v4 §9](../../rfcs/intelligent-troubleshooting-architecture-v4.md#9-安全与信任)，
本文不定义或重述另一套安全红线。三份新合同的特有收窄点只有：

- `error_log_scan` 的 canonical 输出只有错误数、受影响链路数和最新关联 ID；
- `monitor_event_scan` 必须精确匹配已审核 `monitor_checker`，且只输出数量、最新级别和规则名；
- `k8s_workload_health` 只允许已审核的 `deployment + namespace`，四个 component 必须各自产生唯一聚合行；
- 上述合同必填字段缺失、字段关系不成立、值域越界或结果多行时，
  本地规范化层均保守返回 `MISSING`。

## 5. 如何在排障场景中使用

1. 在已审核 Playbook 中声明所需 `EvidenceRequest`，不能由浏览器临时拼 DQL；
2. 对监控事件合同在 `target` 中固定 `monitor_checker`；对 K8s 合同固定 `deployment` 与 `namespace`；
   这些值都来自已审核 Playbook，不是浏览器临时参数；
3. 在“取证查询目录 → 路由与绑定”核对 `系统 + 证据维度` 是否路由到 `guance`；
4. 在“联调与验收”确认端点、运行时凭据、精确资产绑定和 owner acceptance；
5. 由排障事件执行统一 EvidencePlan，使用判据计算异常，缺证据时保守弃权。

示意请求（这是 Playbook 内部合同，不是前端自由输入）：

```json
{
  "requestId": "EV-K8S-WORKLOAD",
  "signalKind": "k8s_workload_health",
  "purpose": "核对目标工作负载状态与资源高水位",
  "target": {
    "deployment": "csdp-wechat",
    "namespace": "csdp"
  },
  "window": "-15m",
  "required": true
}
```

## 6. 真实验收状态

2026-08-04 已使用本地运行时凭据调用真实 Guance，`error_log_scan` 在当前 15 分钟
窗口内于 1 秒内完成 HTTP 请求、返回解析和 canonical 归一；过程未输出凭据、DQL 或原始日志。
同一合同的 24 小时窗口触发上游约 30 秒超时，因此只能证明短窗口真源合同可用，
不能据此宣称全日扫描的延迟已达标。

投产前仍需 owner 分别核对：

1. `csp-rpc-msg` 的 24 小时聚合性能与 owner acceptance；
2. `E::monitor` 的 `df_status`、`df_monitor_checker_name` 及聚合返回形状；
3. `O::docker_containers` 的 Deployment/Namespace/state 字段与对象聚合能力；
4. `M::docker_containers` 的 CPU/内存百分比单位和单窗口聚合形状；
5. 每个合同的真实空数据语义、耗时、采样设置和 owner acceptance 指纹。

任一项未通过时，目录会保留阻断或“待真实验证”事实；不得用 Recorded Replay 冒充真源成功。
