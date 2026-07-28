# MateClaw 智能排障架构版本库

这里保存已经发布过的架构蓝图。规则是：**只新增版本，不覆盖历史版本**。

## 发布规则

1. `../architecture-blueprint.html` 是现行 latest 别名，可以随新版本更新；它不是历史存档。
2. 修改现行蓝图、RFC 或图形之前，先把当前完整制品复制到 `vX.Y/`。
3. `vX.Y/` 一旦进入版本索引，不得覆盖、删除或原地修订；修正发布为新的版本号。
4. 每个版本至少保存：
   - `architecture-blueprint.html`
   - `diagrams/` 下三张 `.drawio` 和三张 `.svg`
   - `source/` 下三份 YAML 规范及生成 sidecar
   - 当版修改过的 RFC、架构评审或录音基线快照
5. 发布后更新 `index.html`，并校验 HTML、Draw.io XML、SVG XML、相对链接与浏览器视觉效果。
6. 流程图必须单独做 edge audit：箭头从节点边界出入、回路使用独立走廊、不穿节点、标签不压线。

## 已恢复与已发布版本

- **v0.7**：从本次任务会话的变更记录恢复；这是 v0.8 引入 Loop Engineering / 多 Agent 之前的上一版。
- **v0.8**：加入有界 Loop Engineering 与结构化多 Agent 反证；保留原始流程图连线，便于追溯。
- **v0.9**：不改变 v0.8 架构语义；建立版本库，并修复流程图的正交连线、箭头端点和回路走廊。
- **v0.10**：不改变 v0.9 架构语义；统一修复总体架构图、端到端流程图与跨角色泳道图的正交走廊、箭头端点、标签间距和长文本宽度。
- **v0.11**：图形与 v0.10 相同，改动在 RFC。第一性原理评价后升级为 v4.1：晋升资格分校准期/运行期（D5′）、
  北极星四时间戳（D14）、成功样本对照（D15）、Loop/多 Agent 标记 PENDING-EVIDENCE（D16）、红线收敛到 v4 §9。
- **v0.12**：图形不变。修正一处融合缺口——设计此前把企微当成需新建的入站通道，而平台自带
  `vip.mate.channel.wecom`。新增 RFC §7.4 与 D17（通道复用，不新建入站；诊断卡片不得复用
  tool-guard 的 `ApprovalNotice` 形状）；同版纳入已选定的两个投影合同。
- **v0.13**：图形与 RFC 语义不变；校准正式实现状态。P1 fixture 闭环和 Web 双投影已完成，
  P2 真实 Guance 仍待授权与样本验证。双投影直接复用 Diagnosis 内 canonical evidence，
  严格区分事件量与影响人数，并保留旧版处置台作为兼容入口。
- **v0.14**：图形不变，RFC 升级为 v4.3。校正企微普通 @ 消息的真实 Router pre-route
  入站接缝，完成 P3 T9 IntakeSession 首段的补问、幂等、乱序/并发、北极星时间戳、
  附件安全引用与诚实回复边界；异步调查、Web 深链和关闭通知继续标记未完成。
- **v0.15**：图形与 RFC 架构语义不变；校准 P3 T10 前半段实现状态。READY 与持久化调查任务
  同事务提交，租约 worker 复用既有只读调查链并以 Intake ID 幂等归属 Diagnosis；同一 Diagnosis 的
  纯文本 BusinessSummary 与正式工作台深链经 workspace-aware local leader 返回，平台 ACK 后完成；
  路由缓存可回源 DB，预算耗尽后持久恢复 Diagnosis/投递结果。关闭后通知和出站交互卡片继续标记未完成。

v0.2–v0.6 中存在用户已经否定的其他项目材料，因此不作为当前 MateClaw-only 架构版本导入。
原始下载文件仍留在用户 Downloads 中，不得把其中结论重新并入现行设计。
