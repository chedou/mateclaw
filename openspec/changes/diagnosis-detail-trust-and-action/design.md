## Context

See `proposal.md` 现状表。关键代码锚点：

- 投影合同：`DiagnosisExperienceProjection.BusinessSummary`（已含 `impact` / `nextStep` / `fixtureMode` / `evidenceBasis`）
- 装配：`DiagnosisExperienceProjectionService`
- 详情 UI：`BusinessSummaryCard.vue`（成色在折叠内；影响未知文案弱；nextStep 与按钮弱绑定）
- 通道：`TroubleshootingChannelSummaryRenderer`（深链已实现，以回归为主）

## Goals / Non-Goals

**Goals:**
- 开发打开 tasks 就能改文件、写单测、对照场景验收。
- 默认不扩 API：先用尽现有 typed 字段。

**Non-Goals:**
- 新详情架构、IM 卡片、T7、影响面数据采集大工程（若口测仍失败再单开变更补 intake 影响字段）。

## Decisions

### D1. 成色做首屏条，不改枚举
- **做**：在 `BusinessSummaryCard` 结论英雄区增加 `ProvenanceStrip`，输入：`evidenceBasis`、`fixtureMode`、`rehearsal`。
- **不做**：新增 EvidenceBasis 值；不把成色只放在 details 折叠。
- **备选**：后端再加 `provenanceLabel` 字段 — 仅当前端无法稳定映射时再加。

### D2. 影响未知文案前端可先落地
- **做**：`showImpact===false` 时改用固定保守模板（声明未知 + 建议转交/补问），二线「是否升级」给出保守默认（例如：影响未确认 → 倾向转交或由值班策略文案给出）。
- **若不够**：再在 `DiagnosisExperienceProjectionService.nextStep` / impact 旁路补 `NextStep` 字段内容。
- **备选**：扩展 ImpactView — 推迟，除非单测证明缺字段。

### D3. nextStep 绑定用「意图映射表」而非新动作框架
- **做**：在 UI 层建立 `nextStep.title` / `conclusionType` / `status` → 主按钮高亮或阻断文案的映射；按钮仍走现有 emit。
- **不做**：新的 Action DSL。
- **通道**：跑 `TroubleshootingChannelSummaryRendererTest`；若生产 `workbench-base-url` 空导致相对链接，修配置/渲染，不改产品形状。

### D4. 口测记录是完成门，不是可选项
- **做**：`docs/intelligent-troubleshooting/diagnosis-detail-misread-gate.md` 模板 + 两次结果。
- **失败修复**：只改文案/首屏信息架构，tasks 5.x 重开。

## Risks / Trade-offs

- [影响数据本身为空] → 先靠保守文案过二线 10 秒门；仍失败再开 intake 影响采集变更
- [nextStep 文案多样导致映射不全] → 映射表 + 默认「转交/说明阻断」兜底，单测锁关键结论类型
- [成色条增加噪音] → 单行 chip，不新增卡片；口测失败则缩短文案

## Migration Plan

1. UI/投影小步提交；无需 DB migration
2. 演练单目视 → 正式单目视 → 口测记录
3. 回滚：还原 UI/投影提交即可

## Open Questions

- 二线「影响未知」时，默认保守策略文案最终用「建议升级」还是「先补问范围」——以实现时口测为准，映射进 formalProjection 常量即可。
