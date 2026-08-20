# diagnosis-detail-trust-and-action

给**开发**用的变更说明（不是再讲产品愿景）。

## 一句话

把详情首屏改到：成色一眼可见、影响未知有保守动作、下一步对得上按钮；通道深链以回归为主；口测记录不齐不算完成。

## 先读

1. `proposal.md` — 现状对照表（哪段代码现在错在哪）
2. `design.md` — D1–D4 技术选择
3. `tasks.md` — 按序勾选
4. `specs/**/spec.md` — 场景级验收（写测用）

## 默认改这些文件

| 文件 | 干什么 |
|---|---|
| `mateclaw-ui/src/views/Troubleshooting/BusinessSummaryCard.vue` | 首屏成色条、影响未知文案、nextStep↔按钮 |
| `mateclaw-ui/src/views/Troubleshooting/diagnosisDetailPresentation.ts` | 成色 chips、未知影响、handoff、nextStep↔主按钮映射 |
| `mateclaw-ui/src/views/Troubleshooting/diagnosisPerspective.ts` | 视角英雄文案与根因表述（既有） |
| `mateclaw-server/.../DiagnosisExperienceProjectionService.java` | 仅当前端表达不了时补装配 |
| `mateclaw-server/.../TroubleshootingChannelSummaryRenderer.java` | 深链回归，有洞再补 |
| `docs/intelligent-troubleshooting/diagnosis-detail-misread-gate.md` | 口测记录 |

## 不要做

新详情页、默认展开判定链、新 IM UI、T7、为凑字段硬造 API。
