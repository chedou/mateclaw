# v0.12 · 通道复用（与 MateClaw 现有能力对齐）

- 发布日期：2026-07-28
- 图形语义：与 v0.10/v0.11 一致，三张图与 YAML 源原样冻结转入本版本；本次改动只在 RFC 与文档层。
- 触发原因：用户追问「这部分设计有融合当前 MateClaw 吗」，逐包读源码后发现一处真实缺口。

## 本版修正

设计此前把企业微信当成**需要新建**的入站通道，而 MateClaw 早已自带完整实现：

```
vip.mate.channel.wecom.WeComChannelAdapter        proactiveSend + 交互卡片
vip.mate.channel.wecom.cards.WeComCardDispatcher  多 kind 注册表，不相交前缀
vip.mate.channel.ChannelMessage / ChannelSessionStore
```

而排障域**已经在飞书上正确做过一次**（注册 `ts.` card kind）。全部 `docs/` 与 `rfcs/` 此前
0 处提到这套设施，风险是 P3 造出第二条入站路径。

- 新增 RFC **§7.4 通道复用** 与 **D17**：通道一律复用现有 `ChannelAdapter` / `CardKind`，不新建入站。
- §7.1 入口、§10 兼容迁移表、§12 P3 同步改为「扩现有通道」。
- 记录一个真实约束：`WeComCardRenderer` / `FeishuCardRenderer` 签名都是 `render(ApprovalNotice)`
  ——tool-guard 的形状，且语义相反（批准=回放执行 vs 确认=只推进状态）。
  **严禁把 `BusinessSummary` 适配成 `ApprovalNotice`**；出站交互卡片需先泛化平台接缝（单独评审），
  在此之前 IM 出站只发纯文本摘要。入站不受影响，可先行。
- `projection-contracts.md` 增 §5「投影怎么被通道消费」。

## 同版纳入的产品决定

- 信息结构已选定：集中兵力做**服务经理摘要 + 开发证据台**两个投影；企微协同流随 P3 暂缓。
- 两个投影的类型化合同（`BusinessSummary` / `DeveloperEvidenceView` / `NorthStarTimings`）已固定，
  首次纳入版本快照。

## 安全边界

无变化。ERROR_CODE 命中路仍零 LLM，生产写工具仍为 0，人工处置与知识批准权未下放给 Agent。
新增的 D17 只收紧、不放松：它禁止新建旁路入站，并禁止诊断复用工具批准的卡片语义。
