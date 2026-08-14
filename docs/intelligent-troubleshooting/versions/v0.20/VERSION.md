# v0.20 · 证据资产授权补上场景维度

- 发布日期：2026-08-14
- 架构版本：RFC v4.6
- 图形语义：与 v0.18 相同；本版改的是资产授权键，不新增诊断流，也不改变三张图。

## 起因：一次真实告警落不了地

`客服数字化(WECHAT)-【URL慢请求】-事件`（2026-08-06 12:00，csdp-wechat，110 条）
无法被系统接管。人工只读排障已定位根因（外部 IT 网关 `upToCtiV2` 常态 7–17 秒，
偶发超时后重试作用在已成功的操作上），证据见
`../../incident-csdp-wechat-slow-request-2026-08-06.md`。

但把这套查法落成场景合同时撞到硬约束：Guance 授权键是
`(workspaceId, system, service, signalKind)` → 唯一合同，**场景不参与解析**。
`csdp-wechat` 的 `log_search` / `log_trace_bundle` / `contrast_sample`
已被 ITGW 904003 场景占满，其中 `log_trace_bundle` 正是定位根因的关键一步。

## 本版锁定的边界

- 授权键增加服务端拥有的 `scenarioKey` 维度：
  `(workspaceId, system, service, scenarioKey, signalKind)` → 唯一合同。
- `scenarioKey` 来自 Diagnosis 已冻结的 `playbookId + playbookVersion`，
  由编排层构造 `EvidenceRoutingScope` 透传；`EvidenceRequest` 不新增合同引用字段，
  模型与浏览器都不能指定合同，§3.3 边界不变。
- 全部 fail closed：场景未授权即 `UNAUTHORIZED`，`(scenarioKey, signalKind)` 歧义即
  `INVALID_BINDING`，不选优也不回退。
- **不提供通配符、默认场景或隐式回退**。旧配置必须显式声明所属场景，
  迁移完成前按未授权处理。
- 场景数与每场景合同数分别设上限，避免配置膨胀成事实上的开放查询面。
- T7 owner 验收指纹纳入 `scenarioKey`：一个场景验收通过不得让同资产其他场景免验收。

## 与 v0.19 的关系

v0.19 永久保留错误码录制证据规模化的决策。v0.20 不触碰晋升门与回放资格，
只修正一处建模疏漏：同一服务本就会因不同故障模式需要不同查询合同，
而原授权键无法表达这一点。

## 仍未完成

- 本版只发布设计（RFC §5.13 / **D20**）。scenario 维度的实现、旧配置迁移与
  慢请求场景合同均未完成。
- 该次排障结论由只读 DQL 得出，`upToCtiV2` 侧整改需 it-gw owner 确认，
  问题清单见 `../../incident-csdp-wechat-slow-request-2026-08-06-owner-questions.md`。
- T7 真实 Guance 资产、字段与阈值验收仍需内网窗口；v0.20 不替代该验收。
