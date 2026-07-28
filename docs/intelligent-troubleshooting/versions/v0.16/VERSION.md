# v0.16 · 关闭结果原路通知与正式页闭环

- 发布日期：2026-07-29
- 架构版本：RFC v4.3；本版只校准实现状态，不新增架构决定。
- 图形语义：与 v0.15 一致；三张图、Draw.io、YAML 与生成 sidecar 原样冻结。
- 触发原因：P3 T10 已把 `CLOSED + ClosureRecord → 纯文本最终结果 → 原路 @ 报障人`
  落到平台现有通道接缝，正式工作台也已展示同一闭环事实。

## 本版实现校准

- Diagnosis 关闭更新与 V180 通知状态在同一事务边界提交；只有带
  `source_intake_session_id` 的记录进入 `PENDING`，直接 Web/API Diagnosis 保持 `NOT_APPLICABLE`。
- H2/MySQL/Kingbase V180 为 Diagnosis 新增通知状态、尝试数、租约持有者/过期时间、下次尝试、
  脱敏错误和完成时间，并为到期扫描建索引。
- 120 秒租约 worker 在认领前先核对 workspace/type/enabled/local leader；精确路由不可用时不烧任务。
  失败持久化退避且无硬重试上限，关闭不因 IM 短暂故障而回滚。
- 企微信群由持久化 `ChannelSession.targetId/senderId` 区分群聊/单聊；群聊必须由当前 Adapter 持有
  入站 reply context。重启后没有 `req_id` 时任务保持未认领，等待群内新消息恢复回复槽，绝不回落
  平台禁止的 `aibot_send_msg`。
- 发送前重读权威 Diagnosis，把同一聚合的 `BusinessSummary + ClosureRecord` 组合为纯文本：
  最终 outcome、原诊断类型/置信、问题、处置摘要、恢复验证、能力边界、fixture 标记和正式页深链。
- `DeliveryOptions` 携带 mention 意图；企微只对通过字符集与长度校验的 reporter ID 生成 `<@userid>`，
  `all` 与非法值被丢弃且日志只记数量；原正文中的 `<@...>` 被转义，不能伪造身份。
- 结案摘要进入 Diagnosis 前限制 500 字并拒绝凭据、DQL、原始日志、控制字符和 mention 标记；
  对存量旧记录，通道渲染继续脱敏并施加 1800 字硬预算，保留正式页深链。
- 通知采用 at-least-once 交付：企微平台 ACK 后才完成；发送后、完成标记前崩溃可能产生重复通知，
  但不会回滚已关闭 Diagnosis。
- 正式 `/troubleshooting` 从 `Diagnosis.closure` 展示“最终处置结果”；`/troubleshooting/legacy`
  保持不变。

## 安全与未完成范围

生产写仍为 0；未启用任何真实企微渠道配置。出站交互卡片、真实 Guance 字段核实、
资产映射与影子样本仍未完成，不在本版声称范围内。

## 验证

- 后端相关全量 `340` 测试，0 failure / 0 error / 0 skipped；其中 V180 调度条件、重试时间、
  租约抢占与 worker 所有权由真实 H2 mapper SQL/CAS 验证；
- 前端 `14` 个测试文件 / `115` 测试全通过，`vue-tsc --noEmit` 与直接 Vite 生产构建通过；
- H2 真实迁移至 V180；正式页、已关闭 Diagnosis 深链、旧版页与后端 health 均返回 200；
- 应用内浏览器可见“最终处置结果 / 已恢复 / 人工验证时间”，控制台 0 error。
