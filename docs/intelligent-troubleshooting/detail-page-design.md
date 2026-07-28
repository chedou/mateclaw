# 智能排障详情 · 信息结构基线

> 状态：待用户从 A/B/C Prototype 中选择后吸收
>
> 架构依据：`rfcs/intelligent-troubleshooting-architecture-v4.md` §7
>
> 录音依据：`recording-product-baseline.md` F5、F7、F8

## 1. 设计目标

同一份 Diagnosis 面向两类人：

- 服务经理先看“问题、影响、结论/下一步、当前状态”；
- 开发按需展开“PS ID 调用链、异常点、证据引用、判据、能力边界”。

页面不能展示模型私有思维链，只展示可复算的证据链和判据。Recorded Replay、MODEL_PROPOSED、置信上限、
candidate/approved 等权威差异必须可见，不能靠颜色暗示后让用户猜。

## 2. 当前三套原型

开发环境：

- A：`/prototype/troubleshooting?variant=A`，服务经理摘要优先；
- B：`/prototype/troubleshooting?variant=B`，开发证据优先；
- C：`/prototype/troubleshooting?variant=C`，企微补问和原路闭环优先。

推荐组合是 **A 做默认页，B 做 A 的开发证据展开层，C 用来定义企微交互**。这是当前建议，不替用户做最终选择。

## 3. 稳定信息层级

| 层 | 受众 | 内容 | 默认 |
|---|---|---|---|
| ① 业务摘要 | 服务经理/业务 | 问题、功能/人数影响、结论类型、下一步、状态 | 展开 |
| ② 开发证据 | 开发 | PS ID、服务跳序、异常点、证据引用、判据、代码位置 | 折叠 |
| ③ 知识草稿 | 开发/知识审核人 | PlaybookDraft 步骤、ReferenceSolution 差异、validation errors | 有 draft 时展示 |
| ④ 运行与审计 | 运维/审计 | investigationMode、routeAuthority、fixtureMode、版本、timeline | 折叠 |

## 4. 四个不能混显的语义

1. `LOCATED`：证据支持已定位；
2. `EXCLUDED`：只排除了某个范围或假设，不等于找到根因；
3. `HYPOTHESIS`：当前最强假设，仍需人确认；
4. `INSUFFICIENT_EVIDENCE`：证据不足，必须 abstain 或补证据。

判据里的 `EXCLUDED` 与 `UNEVALUATED` 同样不能混淆：前者是反证，后者是没测到。

## 5. 影响面展示

`IncidentImpact` 应展示：

- `functionScope`；
- `affectedCustomers?` / `affectedUsers?`；
- `blastRadius`；
- `evidenceRefs[]` 与 `observedAt?`。

人数缺失就显示“未知/待测”，不能用 `0`。精确人数没有证据引用时，不得显示成已确认事实。

## 6. 处置与安全

- 页面没有执行生产变更的按钮。
- “批准”文案必须写清“只推进状态，系统不执行”。
- 模型提议场景必须显示 `MODEL_PROPOSED`，置信最高 MEDIUM。
- Draft 必须显示 `CANDIDATE` 和“不能直接进入 approved Playbook”。
- fixture 必须显示 `Recorded Replay · 非真实观测云`。
- 前端只排版 `BusinessSummary` / `DeveloperEvidenceView`，不自行推断结论或影响。

## 7. 吸收顺序

1. 用户选择 A/B/C 或组合；
2. 后端先稳定 BusinessSummary / DeveloperEvidenceView 合同；
3. 将胜出结构吸收到正式 `/troubleshooting`，保留真实权限和生命周期；
4. 为真实数据、空态、证据源失败、fixture、模型失败和 candidate 校验失败补 Vitest/浏览器测试；
5. 删除 dev-only Prototype 路由和 throwaway 组件。

在步骤 1 之前，不继续新增正式详情页版本。
