## Purpose

定义详情「下一步」与可执行入口的绑定关系，以及通道深链回到同一 Diagnosis 的验收标准。

## ADDED Requirements

### Requirement: NextStep text binds to a primary control
当详情展示 `business.nextStep` 时，页面 MUST 满足其一：
1. 存在与下一步意图一致的可点击主控件（确认定位 / 转交 / 登记结果 / 评估 / 打开指定取证入口）；或
2. 明确展示「当前用户无法执行」的阻断原因（缺权限、需外部系统、需特定角色）。
不允许只渲染 `nextStep.detail` 长文而无可点入口且无阻断说明。

#### Scenario: READY_FOR_HUMAN located case
- **WHEN** 三线视角、`status=READY_FOR_HUMAN`、`conclusionType=LOCATED`，且用户有 operate 权限
- **THEN** 下一步区域 MUST 暴露「复核后确认定位」类主按钮，或其文案与 `nextStep` 意图一致的等价主控件

#### Scenario: User lacks permission
- **WHEN** 下一步需要 operate/manage 权限但当前用户没有
- **THEN** 详情 MUST 说明缺少的权限或应转交的角色，不得显示可点击的假动作

### Requirement: Channel deep link targets the same diagnosis
通道纯文本摘要若包含工作台链接，MUST 指向同一 `diagnosisId` 的 Web 详情（现有约定：`/troubleshooting?diagnosisId=<id>` 或带 `view=detail` 的等价形式）。打开后加载的 Diagnosis MUST 与摘要对应同一 id。

#### Scenario: Renderer emits diagnosis deep link
- **WHEN** `TroubleshootingChannelSummaryRenderer`（或等价通道渲染）输出某 Diagnosis 的业务摘要
- **THEN** 文本 MUST 包含该 diagnosisId 的工作台深链

#### Scenario: Deep link opens one diagnosis
- **WHEN** 用户打开该深链
- **THEN** 工作台 MUST 选中并展示同一 `diagnosisId`；切换二线/三线视角 MUST NOT 改变 Diagnosis 事实
