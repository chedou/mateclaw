# v0.15 · READY 异步调查与原路业务摘要

- 发布日期：2026-07-29
- 架构版本：RFC v4.3；本版只校准实现状态，不新增架构决定。
- 图形语义：与 v0.14 一致；三张图、Draw.io、YAML 与生成 sidecar 原样冻结。
- 触发原因：P3 T10 前半段已经把 `READY → Diagnosis → BusinessSummary + Web 深链`
  落到平台现有通道接缝，需要把现行文档从“未完成”校准为可验证事实。

## 本版实现校准

- Intake 首次进入 READY 时，与 PENDING 调查任务在同一数据库事务提交；回调线程立即返回，
  不等待完整取证或模型。
- 数据库租约 worker 使用 120 秒租约、最多 5 次常规处理；启动时补齐历史 READY 缺失任务。常规预算
  耗尽后进入持久终态投递并持续退避，先按 Intake 恢复已存在 Diagnosis；存在则继续投递摘要，确实
  不存在才返回明确的 fail-closed 文本。
- H2/MySQL/Kingbase V178 新增 Intake 调查任务，并以
  `(workspace_id, source_intake_session_id)` 唯一约束保证一个 Intake 只创建或复用一个 Diagnosis。
- 三方言 V179 将 raw `conversationRef` 与精确 `deliveryConversationId` 分离，并增加持久终态投递计数。
- worker 复用既有 `TroubleshootingIntakeService`：确定性 Playbook 命中仍为零 LLM；未命中仍受
  原有只读、显式启用、唯一模型和 fail-closed Agent 边界约束。
- 结果只投影同一 Diagnosis 的 `BusinessSummary`，经
  `ChannelSessionStore → ChannelManager.sendToWorkspaceConversation → proactiveSend` 原路发送纯文本，
  并附 `/troubleshooting?diagnosisId=...` 正式工作台深链。只有 workspace/type/enabled 匹配且本节点
  持有 active leader Adapter 时才认领；精确缓存 miss 回源 DB，follower 不消耗任务。
- 通道不发送 `DeveloperEvidenceView`、原始日志或 DQL；fixture 与能力边界不截断。
- 通知采用 at-least-once 交付：企微平台 ACK 后才完成；发送后、完成标记前进程崩溃可能产生重复消息，
  但不会重复创建 Diagnosis，也不会把已存在 Diagnosis 误报为调查失败。

## 安全与未完成范围

生产写仍为 0；未启用任何真实企微渠道配置。关闭且 outcome 已登记后的原路 @ 通知、
出站交互卡片、真实 Guance 字段核实、资产映射与影子样本仍未完成，不在本版声称范围内。
