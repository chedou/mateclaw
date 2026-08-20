## Why

详情主线已有，但开发落地时缺「对照现状改哪里」。本变更把产品缺口钉到**现有投影字段与具体文件**，避免再写一轮空泛体验文案。

## What Changes

### 现状（代码事实）

| 缺口 | 现状 | 开发要改成 |
|---|---|---|
| 成色信号 | `evidenceBasis` 只在「查看判断依据」折叠内；`rehearsal` 仅 decision-panel 小字；`fixtureMode` 首屏几乎无硬标识 | 二线/三线**首屏**固定成色条（读 `evidenceBasis` + `rehearsal` + `fixtureMode`） |
| 影响面 | `showImpact=false` 时只显示「影响范围尚未确认」 | 未知时必须附带**保守动作文案**（观察/转交/补问），不得空停 |
| 下一步 | `business.nextStep` 与生命周期按钮各自为政；文案常是「请某某复核」无可点对齐 | `nextStep` 与主按钮/阻断说明绑定；无权限写清缺什么 |
| 未定位语义 | 投影大体正确，但仍有误读风险 | 锁死 EXCLUDED≠定位、HYPOTHESIS≠根因（文案 + 单测） |
| 企微深链 | `TroubleshootingChannelSummaryRenderer` **已有** `/troubleshooting?diagnosisId=` | **验收**为主；仅修回归缺口，不新建通道 UI |
| 误读门 | TODO 里有，无强制完成物 | 产出口测记录 md，作为变更完成条件 |

### 明确不做

- 不加统计条 / 第二套详情 / 默认展开判定链
- 不改 T7 / Guance binding / 生产写执行
- 不新建企微独立卡片 UI
- 不新建「detail readiness」API（除非审计证明缺 typed 字段；默认改装配与展示）

## Capabilities

### New Capabilities

- `troubleshooting/diagnosis-detail-decision`: 二线影响未知时的保守动作；未定位结论语义锁
- `troubleshooting/diagnosis-detail-actionability`: nextStep↔动作绑定；通道深链验收
- `troubleshooting/diagnosis-detail-trust`: 首屏成色硬信号（OBSERVED / REPORTED / RECORDED_REPLAY + rehearsal/fixture）
- `troubleshooting/diagnosis-detail-acceptance`: 10 秒口测记录作为完成门

### Modified Capabilities

- （无）

## Impact

**优先改（按概率）：**

1. `mateclaw-ui/.../BusinessSummaryCard.vue` — 首屏成色、影响未知文案、nextStep 与按钮对齐
2. `mateclaw-ui/.../formalProjection.ts`（及 perspective/explanation helpers）— 文案策略集中处
3. `mateclaw-server/.../DiagnosisExperienceProjectionService.java` — 仅当前端无法从现有字段表达保守动作/成色时补投影文案
4. `mateclaw-server/.../TroubleshootingChannelSummaryRenderer.java` — 深链回归测试；有缺口再改
5. `docs/intelligent-troubleshooting/` — 口测记录

**测试锚点：**

- `DiagnosisExperienceProjectionServiceTest`
- `BusinessSummaryCard` / `formalProjection` / `plainLanguageLabels` 相关前端测
- `TroubleshootingChannelSummaryRendererTest`
