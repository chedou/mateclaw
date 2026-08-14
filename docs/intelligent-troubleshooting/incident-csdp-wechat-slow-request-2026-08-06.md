# csdp-wechat「URL 慢请求」排障报告（2026-08-06）

> 本报告由只读探查得出，未对任何系统执行写操作。
> 所有外部 URL 中的 token、客户名称、puid、工单号均已脱敏。

## 1. 告警原文

```
客服数字化(WECHAT)-【URL慢请求】-事件
■【紧急】2026-08-06 12:00:00 (r/0009b2)
集群：sz3-s-k8s
服务：csdp-wechat
数量：110
说明：异常事件
```

## 2. 告警的权威定义

从观测云监控器 `df_meta` 取得触发该告警的 DQL，这是「URL 慢请求」在本服务上的唯一权威定义：

```
L::`csdp-wechat`:(count(`*`) as slow_count) {
  `message` = queryString("level:warn") AND `message` = queryString("code:1010")
} BY `cluster_name_k8s`, `container_name`
```

- 检测窗口 12 小时，阈值 6，故本报告的复核窗口取 `2026-08-06 00:00:00 ~ 12:00:00 (+08:00)`。
- `code:1010` 是应用层慢请求告警码，由 `[Controller-Finish]` 在请求结束时打出，慢请求阈值为 **5 秒**（观测到的最小值恰为 5.00s）。
- 用该 DQL 在同一窗口复核，返回 **110**，与告警数量完全一致，说明查法可复现。

## 3. 逐层收敛过程

### 3.1 排除基础设施因素

110 条按 Pod 分布：

| Pod | 条数 |
| --- | --- |
| csdp-wechat-845f6bb9f9-bfv4s | 41 |
| csdp-wechat-845f6bb9f9-qd8ks | 37 |
| csdp-wechat-845f6bb9f9-s2l44 | 32 |

三个副本均摊，不存在单 Pod 或单节点异常，问题在应用逻辑或其下游，而非调度与资源。

### 3.2 按入口 URL 分布

| 入口 URL | 条数 | 中位 | 最大 |
| --- | --- | --- | --- |
| POST /openapi/v1/csdp-wechat-proxy/general-request | 63 | 9.15s | 35.74s |
| POST /scl/v1/external/partner_user_info | 10 | 5.61s | 8.92s |
| POST /scl/v1/wechat/csp/web_login | 9 | 6.65s | 60.43s |
| POST /scl/v1/wechat/oauth/qr_login/auth_info | 7 | 8.58s | 26.18s |
| POST /scl/v1/external/partner_info | 5 | 5.79s | 13.74s |
| POST /scl/v1/external/get_question_detail_by_srv_conv_id | 4 | 10.31s | 14.60s |
| 其余 10 个接口各 1–2 条 | 12 | — | 11.45s |

整体耗时：`min=5.00s p50=7.57s p95=32.04s max=60.43s`。
按小时：00时=6、01时=4、08时=7、09时=32、10时=23、11时=38。

**57% 集中在同一个 openapi 代理入口**，这是首要线索。

### 3.3 代理入口整体是健康的

同窗口该代理共被调用 **21483** 次，慢请求 63 次，**慢请求率仅 0.29%**。
所以这不是整体性能劣化，而是长尾；必须找出长尾里的具体下游。

### 3.4 关联到真实下游后，异常极其突出

代理日志会打出 `ProxyController: Handling Partner route via config: <路由> -> <控制器>`。
用 63 条慢请求的 `trace_id` 反查该行（62 条匹配成功，1 条未找到路由日志），
再与同窗口各路由的总调用量对照：

| 下游路由 | 总调用 | 慢 | 慢率 |
| --- | --- | --- | --- |
| **POST /scl/v1/partner/workorder/upgradesrv** | **71** | **28** | **39.44%** |
| POST /scl/v1/partner/workorder/close | 286 | 3 | 1.05% |
| POST /scl/v1/partner/mdm/search | 1153 | 11 | 0.95% |
| POST /scl/v1/partner/workorder/list | 4115 | 8 | 0.19% |
| POST /scl/v1/partner/csp/refresh_token | 6619 | 3 | 0.05% |
| POST /scl/v1/partner/workorder/detail | 4093 | 2 | 0.05% |

工单升级接口的慢率比同期其他路由高 **40 到 800 倍**。
其 28 条慢请求耗时中位数 12.96s，远在 5s 阈值之上——是稳定慢，不是偶发抖动。

耗时呈三段聚集：

- 6.8–14.0s（16 条）：下游正常返回，但本来就慢
- 15.1–16.8s（9 条）：紧密聚集在 16.5s 附近，疑似固定重试边界
- 30.5–35.7s（3 条）：客户端硬超时后重试

## 4. 根因

沿最慢一条（35.74s，`trace_id=8683912777332236942`）逐条读日志，完整过程为：

| 时刻 | 事件 |
| --- | --- |
| 09:30:22 | 请求进入代理，路由到 `PartnerWorkOrderController.WorkOrderPartnerUpgradeService` |
| 09:30:22 | AKSK 签名校验通过、Redis 命中、Partner session 建立（均为毫秒级） |
| 09:30:22 | 同步调用外部 IT 网关 `https://it-gw.sangfor.com/.../case/workOrderPhase/channel/upToCtiV2`（token 已脱敏） |
| 09:30:53 | 第一次调用报 `context deadline exceeded (Client.Timeout exceeded while awaiting headers)`，**白等约 31 秒** |
| 09:30:58 | 重试第二次，HTTP 200 返回 |
| 09:30:58 | 业务层报错：`当前工单已经升级cti，请勿重复请求` |
| 09:30:58 | `[Controller-Finish]` 记 code=1010，总耗时 35.74s |

**根因：`csdp-wechat` 的合作伙伴工单升级链路同步等待外部 IT 网关 `upToCtiV2`，而该接口常态需要 7–17 秒返回。**

关键修正（避免误判）：全窗口客户端硬超时只有 **21 次**，且集中在几个瞬间——
01:10–01:11 的 `user/getUserDetailByCode`（7 次），以及 09:30、11:14、11:20 的 `upToCtiV2`。
因此**硬超时只解释了最慢的 3 条**（30.5s / 33.9s / 35.7s）；
其余 25 条并未超时，是网关正常返回但耗时本就 7–17 秒。
**慢是常态，超时是偶发叠加**，两者不可混为一谈。

## 5. 比延迟更严重的问题：重试作用在已成功的操作上

超时那次的调用，网关侧其实**已经执行成功**——所以重试才会收到
「当前工单已经升级 cti，请勿重复请求」。

同窗口异步补偿路径 `SrvWorkOrderManualCreate upgrade goroutine` 有 **69 条**
`ServiceUpgrade err` 日志，全部是同类重复升级报错，且在单条 trace 内可观察到
`attempt:1`、`attempt:2` 连续重试。

这是数据一致性风险，不只是性能问题。

> 未采信的数据：关键词 `请勿重复请求` 在窗口内出现 341 次，但单次请求会在多行日志中重复出现该短语，
> 该计数不能等同于 341 次重复操作，故不作为结论。
> 另外 `attempt:N` 被观测云 `queryString` 当作字段解析，无法据此统计各重试层级的次数。

## 6. 责任方划分

| 项 | 责任方 | 问题 |
| --- | --- | --- |
| `upToCtiV2` 常态 7–17 秒 | it-gw / icare openapi | 为何该接口基线延迟如此之高 |
| 偶发完全无响应 | it-gw / icare openapi | 01:10 与 09:30/11:14/11:20 的两次短时不可用 |
| 客户端超时设为约 31 秒 | csdp-wechat | 比 5 秒慢请求阈值高一个数量级，必然产生慢请求告警 |
| 重试未做幂等识别 | csdp-wechat | 未把「已升级 cti」判定为成功，导致重复升级与误报 |
| 升级调用同步阻塞请求 | csdp-wechat | 是否可改为异步提交 + 回调/轮询 |

## 7. 复核方式

本报告全部结论均可用观测云只读 DQL 复现，窗口固定为
`2026-08-06 00:00:00 ~ 12:00:00 (+08:00)`，索引 `L::csdp-wechat`。
关键查询：

- 慢请求总数（复现告警的 110）：见 §2 的监控器 DQL
- 按 Pod 分布：上述 DQL 追加 `BY \`pod_name\``
- 某路由总调用量：`count(*) { message = queryString("\"Handling Partner route via config: POST <路由>\"") }`
- 单条 trace 全过程：`(message) { message = queryString("<trace_id>") }`
- 客户端超时：`count(*) { message = queryString("\"context deadline exceeded\"") }`

## 8. 对 MateClaw 系统本身的影响

该告警**当前无法被系统自动接管**：它没有错误码，也没有任何 approved Playbook 声明
「URL 慢请求」这一症状，兜底的 open discovery Agent 在本 Workspace 被显式关闭，
因此提交后按预期 fail closed（409）。

要让这类告警自动命中，需要为 `csdp-wechat` 落一套慢请求场景合同。但现行
Guance 授权键是 `(workspaceId, system, service, signalKind)` → 唯一合同，**场景不参与解析**
（见 `GuanceEvidenceAdapter.authorizedBinding`），而 `csdp-wechat` 的
`log_search` / `log_trace_bundle` / `contrast_sample` 已全部被 ITGW 904003 场景占用。
其中 `log_trace_bundle` 正是本次定位根因的关键一步。

因此需要先扩展授权模型，为资产绑定增加 scenario 维度，方能容纳同一服务的第二套场景。
该项属于架构变更，另行按 RFC 流程推进。
