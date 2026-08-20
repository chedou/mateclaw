## Purpose

定义二线详情如何把影响面变成升级决策信息，以及未定位结论的语义边界（供实现与单测对照）。

## ADDED Requirements

### Requirement: Unknown impact must include a conservative action
当业务投影的影响面无法用于升级决策（例如 `blastRadius=UNKNOWN` 且无客户/用户度量）时，二线首屏 MUST 同时展示：
1. 影响未知的明确声明；
2. 一条不依赖完整影响面的保守动作（继续观察、转交三线、或补问客户范围之一）。
仅显示「影响范围尚未确认」且无动作语义，视为不合格。

#### Scenario: Support view with unknown blast radius
- **WHEN** 二线视角打开详情，且 `impact.blastRadius` 为 `UNKNOWN`，且无可用影响度量
- **THEN** 首屏 MUST 显示影响未知声明，并且 MUST 显示至少一条保守动作文案或可点入口

#### Scenario: Support view with known impact
- **WHEN** 二线视角打开详情，且影响面已确认（非 UNKNOWN 或有度量）
- **THEN** 首屏 MUST 展示影响范围/度量，并给出是否升级三线的判断文案

### Requirement: Conclusion semantics stay honest
详情 MUST 保持以下语义边界（文案与状态标签均适用）：
- `EXCLUDED` = 平台侧排除 / 未见异常，不是根因已找到
- `HYPOTHESIS` = 尚未找到根因；已确认的直接失败点只能进「已经知道」
- `INSUFFICIENT_EVIDENCE` = 弃权，不得命名根因
- `UNEVALUATED` 判据/步骤不得表述为已排除

#### Scenario: EXCLUDED copy rejects root-cause wording
- **WHEN** `conclusionType=EXCLUDED`
- **THEN** 首屏状态/标题 MUST NOT 使用「根因已找到 / 已定位」语义

#### Scenario: HYPOTHESIS keeps gap visible
- **WHEN** `conclusionType=HYPOTHESIS`
- **THEN** 三线首屏 MUST 同时暴露已知事实与根因缺口，不得把假设写成已确认根因
