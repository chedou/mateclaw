# 诊断详情页 · 唯一现行设计

> 现行原型：**`console-detail.html`**（唯一）。此前的 `console-rca.html` /
> `console-diagnosis-detail.html` / `console-disposition*.html` / `console-prototype*.html`
> 全部降为**历史存档**，只用于回顾演进，不再作为实现依据。
> 架构依据：`rfcs/intelligent-troubleshooting-architecture-v2.md` §8（L5 输出分层）。

---

## 1. 之前错在哪

**问题一：把审计投影当成了主视图。**
现在 Vue 实现（`views/Troubleshooting/index.vue`）的第一屏是：

```
routeMode  fixtureMode  rehearsal  writeExecutionEnabled=false     ← 三到四个 pill
⚠ 能力边界 · 契约自曝的 warnings                                   ← 告警条
判定链（完整展开）
建议动作：approvalStatus PENDING / executionStatus BLOCKED         ← 裸枚举
```

页面上最显眼的位置，放的是**只有排障系统自己关心的东西**。服务经理需要的
「问题是什么 / 影响谁 / 怎么办」一个都没有，开发需要的判定链被淹没在契约字段里。
会议对这类页面的评价是「太花哨了」「PSD 他肯定看不懂」。

**问题二：原型和实现是两套东西。**
被认可的方向是「在详情页看到单次事件里的阶段，帮开发快速定位根因」，
原型（`console-rca.html`）按这个做了漏斗 + 阶段脊柱 + 证据抽屉；
但 Vue 实现是照着契约字段平铺的，两者信息层级完全不同。

**问题三：原型自己也长胖了。**
漏斗、阶段脊柱、证据抽屉、相似案例、交接卡片四五套结构叠在一起，
每一层单看都成立，合起来就是「复杂」。

---

## 2. 现在的结构：三层深度，默认只展开第一层

| 层 | 受众 | 内容 | 默认 |
|---|---|---|---|
| ① **结论** | 服务经理 / 二线 / 业务 | 一句话结论 + 故障类别 + 置信度；**问题描述 · 影响面 · 怎么办** 三栏；三个处置按钮 | **展开** |
| ② **判定链** | 开发 | 三步：取到什么证据 → 判据怎么算的（带代入算式、三态）→ 规则怎么裁决的（带反事实） | **折叠** |
| ③ **运行细节** | 审计 | routeMode / SOP / 契约版本 / timeline / warnings | **折叠** |

整页一栏、无侧边栏、无嵌套抽屉。展开靠原生 `<details>`，没有自造的折叠状态机。

**为什么这样分**：同一份 `Diagnosis` 对三种人是三种不同的正确形态（架构 v2 的 L5）。
把三者并列在一屏，等于对三种人都不可用。

---

## 3. 关键设计决定

**结论行必须先说清"这是哪一类故障"。** 因为系统对不同类别的承诺不同（v2 §7）：

- `CODE_BUG` → 第三栏标题是「定位结果」，给代码位置，**并显式写明"不给解决方案、无法自动恢复"**；
- `EXTERNAL_CLIENT` → 第三栏标题是「排除结论」，**显式写明这是"排除"不是"定位"**；
- `DATA_FIX` / `BUSINESS_OPERATION` / `INFRASTRUCTURE` → 才是「解决方案」。

能力边界写在结论旁边，不是藏在 warnings 里。**承诺边界属于结论的一部分。**

**影响面用数字，不用形容词。** 「12 个客户 / 148 名用户 / MULTI_CUSTOMER」比「影响较大」有用得多，
且这三个值直接来自 `IncidentImpact`，不是文案。爆炸半径徽标同时是分诊依据的可视化——
单客户为中性灰、批量为危险色，看一眼就知道走的哪条路由。

**判据三态必须视觉可分**，这是全页最重要的一处区分：

| 状态 | 含义 | 视觉 |
|---|---|---|
| `SATISFIED` | 判据成立，支持该假设 | 危险色实底 |
| `EXCLUDED` | 判据求值为假，假设**真的被排除了** | 成功色实底 |
| `UNEVALUATED` | 证据缺失，假设**从未被检验** | 灰色**虚线**边框 |

后两者在操作者心智里是相反的意思，混显是诊断安全事故。虚线边框传达"这条没闭合"。

**机器产出的数据用等宽字体，人写的叙述用无衬线。** 代入算式 `2000 ÷ (2000 + 0) = 1 > 0.95`、
字段名、queryId、枚举值一律等宽；结论、影响面、规则解释一律无衬线。读者一眼就知道哪些是
系统算出来的、哪些是给人读的。

**页面上没有任何执行生产变更的入口。** 三个按钮是「确认结论 / 转派 / 关闭并沉淀知识」，
批准动作放在展开后的动作区且文案写明「只推进状态机，系统不执行」。

---

## 4. 与契约的对应

| 页面元素 | 契约来源 |
|---|---|
| 故障类别徽标 | `Diagnosis.faultClass`（v1.4 新增） |
| 置信度 | `Diagnosis.confidence`（枚举 HIGH/MEDIUM/LOW，**不是浮点数**） |
| 影响面三个数字 | `IncidentImpact.{functionScope, affectedCustomers, affectedUsers, radius}`（v1.4 新增） |
| 判定链三步 | `DiagnosisDerivation.{criteria, rules}` + `CriterionRenderer` 的 `expression()` / `substitution()` |
| 三态 | `CriterionOutcome.{SATISFIED, EXCLUDED, UNEVALUATED}` |
| 反事实说明 | `RuleEvaluation.{unsatisfiedByExclusion, unsatisfiedByGap, undefinedSignals}` |
| 运行细节 | `Diagnosis.{routeMode, fixtureMode, contractVersion, timeline, warnings}` |

`faultClass` 与 `IncidentImpact` 属于契约 v1.4（尚未实现，见 TODO T13/T14）。
**前端先按可选字段渲染**：字段缺失时第三栏回落为现有的 `recommendedActions`，
影响面回落为 `incident.impact` 字符串，不阻塞本次页面重构。

---

## 5. 落地范围

1. `console-detail.html` —— 新增，唯一现行原型（含三个真实形态的样例：命中 / 无错误码场景 / 单客户排除）。
2. `mateclaw-ui/src/views/Troubleshooting/index.vue` —— 详情区按三层重排。
3. `mateclaw-ui/src/views/Troubleshooting/DerivationChain.vue` —— 收敛为「三步」结构，去掉多余分层。
4. `index.html` 设计目录 —— 标注唯一现行 + 历史存档。

**明确不做**：不再新增详情页原型版本。会议已叫停展示层扩张。
