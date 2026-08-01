# v0.14 · 企微普通消息 IntakeSession 与 RFC v4.3

- 发布日期：2026-07-29
- 图形语义：与 v0.13 一致；三张图、Draw.io 与 YAML 原样冻结。
- 触发原因：源码复核证明 `CardKind` 只处理模板卡片事件，不是企微普通 @ 消息的入站接缝。

## 本版校正

- 企微普通消息复用 `WeComChannelAdapter → ChannelMessageRouter`，由显式开关的
  `ChannelMessagePreRouteHandler` 接管；不新建 webhook/签名校验，接管后不进 Trigger/通用 Agent。
- 新增独立 `IntakeSession`：`RECEIVED → AWAITING_INPUT → READY`、确定性补问、
  `reportedAt/readyAt`、附件安全引用、source-message receipt 幂等、乱序和并发保护。
- 企微 `send_time` 经范围校验后作为事件时间；不可变 `reportedAt` 持久化为跨 Session 边界，
  A 已 READY、B 已开始后的 A 迟到回调只登记到 A，不污染 B。
- H2/MySQL/Kingbase V175–V177 从历史聚合的真实 `reportedAt` 回填多轮 Session，并将该边界收紧为非空；
  不用末条 `lastMessageAt` 冒充首条事件时间。
- Router 在 pre-route 接管前保存带 channelId/targetId 的 `ChannelSessionStore`，为后续原路回复保留真实通道。
- READY 时原子释放 active key；当前只诚实回复“Intake 已保存，等待接入只读调查”，
  不虚假声称调查已启动。
- `reporterRef` 在 Intake 阶段是不可信通道身份：可报障/补充，不得审核或推进受审计状态。
- 入库失败与“已入库但回复失败”分类，避免与真实持久化状态矛盾的提示。

## 安全与范围

生产写仍为 0，入站路径零 LLM。`READY → 异步只读调查 → Diagnosis → Web 深链`
与关闭后原路通知尚未完成，不在本版声称范围内。
