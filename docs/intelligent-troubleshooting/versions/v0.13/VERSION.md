# v0.13 · 正式双投影与证据事实吸收

- 发布日期：2026-07-29
- 图形语义：与 v0.12 一致，三张图、Draw.io 与 YAML/sidecar 原样冻结；本次只校准蓝图状态与投影合同。
- 触发原因：正式 `/troubleshooting` 已完成双投影纵切，v0.12 蓝图仍把 P1 和 Demo 选型写成进行中。

## 本版校准

- P1 无错误码 fixture 闭环已完成；P2 真实 Guance 授权、字段核实与历史样本仍未完成，不能把回放当生产成功。
- 正式工作台读取真实 Diagnosis/Projection API；原工作台保留在 `/troubleshooting/legacy`。
- 一份 Diagnosis 继续生成 `BusinessSummary` 与 `DeveloperEvidenceView`，没有新增第二份事实。
- 投影可直接消费既有 `log_count`、`trace`、`log_trace_bundle` 与 `contrast_sample`：
  事件量不冒充客户/用户数；标量 trace 只显示部分异常 hop；完整 bundle 与成功样本对照确定性复算。
- 聚合 Long→String 精度保护只接受 canonical 十进制整数表示；宽松数值强转继续 fail closed。

## 安全与范围

无变化。生产写仍为 0；页面按钮只推进领域状态；fixture 标记继续显式显示；Loop Controller、
多 Agent Challenger、新接口和新表仍受 `PENDING-EVIDENCE` 约束，未在本版实现。
