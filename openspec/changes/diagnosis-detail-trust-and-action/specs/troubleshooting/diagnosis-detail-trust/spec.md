## Purpose

定义首屏必须暴露的证据成色信号，避免开发把成色继续藏在折叠区里。

## ADDED Requirements

### Requirement: Provenance is visible before evidence unfold
在展开「完整证据与技术记录」或「查看判断依据」之前，二线与三线首屏 MUST 可见结构化成色，至少覆盖：
- `evidenceBasis=OBSERVED` → 只读数据源观测
- `evidenceBasis=REPORTED` → 告警上报（非上游根因证明）
- `evidenceBasis=RECORDED_REPLAY` 或 `fixtureMode=true` → 录制/回放，非现场真源
- `rehearsal=true` → 演练，不计入正式验收目标

成色 MUST 来自投影/诊断字段；前端 MUST NOT 用中文前缀猜测。

#### Scenario: First screen shows REPORTED without opening folds
- **WHEN** `evidenceBasis=REPORTED` 且用户未展开判断依据/开发证据
- **THEN** 首屏 MUST 仍能看到告警上报类成色标识

#### Scenario: Rehearsal is visible without scrolling to actions
- **WHEN** `rehearsal=true`
- **THEN** 成色/演练标识 MUST 出现在结论英雄区附近（首屏），不得仅出现在页面底部小字

### Requirement: Replay or fixture cannot share bare live-located wording
当 `fixtureMode=true` 或 `evidenceBasis=RECORDED_REPLAY` 时，即使 `conclusionType=LOCATED`，首屏 MUST 带非现场真源修饰；MUST NOT 与已验证现场观测的 LOCATED 使用完全相同的无修饰文案组合。

#### Scenario: Fixture located is labeled
- **WHEN** `fixtureMode=true` 且 `conclusionType=LOCATED`
- **THEN** 首屏 MUST 同时暴露定位结论与非现场真源/回放标识
