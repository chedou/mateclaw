# csdp-wechat 慢请求（2026-08-06）——给各 owner 的问题清单

配套证据见 `incident-csdp-wechat-slow-request-2026-08-06.md`。
以下每个问题都附了可独立复核的观察结果，便于 owner 直接确认或否证。

---

## A. 给 it-gw / icare openapi owner

对象接口：`POST https://it-gw.sangfor.com/icare/api/sf-icare-openapi/openapi/case/workOrderPhase/channel/upToCtiV2`

**观察**：2026-08-06 00:00–12:00 期间，`csdp-wechat` 调用该接口约 71 次，
其中 28 次导致上游请求超过 5 秒，端到端中位数 12.96 秒。同期 `csdp-wechat`
其他下游路由的慢请求率为 0.05%–1.05%，该接口对应路由为 39.44%。

1. `upToCtiV2` 的服务端处理耗时基线是多少？是否认可「常态 7–17 秒」这一量级？
2. 该接口内部是否存在同步等待 CTI 系统的环节？这段耗时是否可异步化？
3. 是否有该接口的 SLA/超时承诺？若有，当前是否达标？
4. 2026-08-06 09:30:47、09:30:53、11:14:17、11:20:04 四个时刻该接口对 `csdp-wechat`
   完全无响应（客户端等 header 超时）。网关侧这几个时刻是否有对应的异常记录？
5. 同日 01:10:13–01:11:20 期间，`user/getUserDetailByCode` 出现 7 次同类无响应，
   是否与 `upToCtiV2` 同源（同一网关实例/同一次抖动）？
6. **幂等性**：当客户端超时重试时，网关返回「当前工单已经升级 cti，请勿重复请求」。
   这说明首次调用其实已成功。是否可以为重复提交返回**幂等成功**（携带原结果），
   而不是业务错误？这能直接消除下游的误判与补偿逻辑。

---

## B. 给 csdp-wechat owner

**观察**：慢请求集中在 `POST /scl/v1/partner/workorder/upgradesrv`
（经 `POST /openapi/v1/csdp-wechat-proxy/general-request` 代理进入），
控制器为 `PartnerWorkOrderController.WorkOrderPartnerUpgradeService`。

1. **客户端超时配置**：观测到首次调用在约 31 秒后才报
   `context deadline exceeded (while awaiting headers)`。
   而慢请求告警阈值是 5 秒。这个 31 秒是有意设置的吗？依据是什么？
   在网关正常耗时 7–17 秒的前提下，超时值定在何处才合理？
2. **重试策略**：重试发生在一个已经生效的升级操作上。
   代码是否把「当前工单已经升级 cti，请勿重复请求」识别为可接受的终态？
   目前看是当作 `err` 处理的（`ServiceUpgrade err`，同窗口 69 条）。
3. **重试次数与退避**：单条 trace 内可见 `attempt:1`、`attempt:2`。
   最大重试次数、退避间隔分别是多少？是否会与 31 秒超时叠加成分钟级耗时？
4. **同步阻塞**：升级 CTI 是否必须在 HTTP 请求生命周期内同步完成？
   能否改为「提交后立即返回 + 异步回调/轮询」，把外部网关延迟移出用户请求路径？
5. **异步补偿路径**：`SrvWorkOrderManualCreate upgrade goroutine` 同窗口有 69 条
   `ServiceUpgrade err`，全部是重复升级。该 goroutine 与同步路径是否会对同一工单
   并发发起升级？是否存在双重提交？
6. **其他慢接口**：`POST /scl/v1/wechat/csp/web_login` 出现一次 60.43 秒（本窗口最慢），
   `POST /scl/v1/wechat/oauth/qr_login/auth_info` 最大 26.18 秒。
   这两条不经过 partner 代理，属于独立问题，是否已知？

---

## C. 给可观测性/告警 owner

1. 当前「URL 慢请求」告警只给出服务名与总数，不含 URL 维度。
   监控器能否按 `container_name` 之外再补一个 URL 维度分组，
   让告警本身就能指出是哪个接口慢？这将大幅缩短定位时间。
2. 慢请求阈值 5 秒、检测窗口 12 小时、触发阈值 6 条——
   在 21483 次调用的量级下，6 条即告警是否过于灵敏？
   是否更适合改为慢请求**率**而非绝对条数？
3. `csdp-wechat` 日志中 `trace_id` 只存在于 `message` JSON 内，不是顶层可查询字段，
   导致按 trace 关联只能用全文检索。能否将其提升为索引字段？
