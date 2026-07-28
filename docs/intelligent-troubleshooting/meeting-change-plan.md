# 会议驱动的变更方案（据 2026-07 排障方案讨论会）

> ⚠️ **历史讨论，不能作为当前开发入口。** 现行产品事实见
> `recording-product-baseline.md`，现行概要设计见
> `../../rfcs/intelligent-troubleshooting-architecture-v4.md`。
> 本文只保留会议后的推演过程；实现与优先级一律以 v4 和 `TODO.md` 为准。
>
> 输入：会议纪要《利用观测云日志 + AI 自动生成 SOP》。
> 输出：这次会议要求我们**改什么**、改在哪个类上、与已锁决策 D1–D8 和四条红线的关系。
> 阅读顺序：本文 → `TODO.md`（新增 T11–T18）→ `HANDOFF.md`（决策与红线）→ `rfcs/intelligent-troubleshooting-design.md`。
>
> 本文只写变更，不重复已完成的部分。已完成部分见 `TODO.md` 顶部状态段。

---

## 一、会议要点还原（去掉口语噪声后的九条）

| # | 要点 | 出处（说话人/时间） |
|---|---|---|
| M1 | 友商比我们多一步：**执行 SOP**，调本机命令模拟人工操作把业务恢复了 | 1 @00:00 |
| M2 | 我们的差异化是**我们有观测云日志**，必须做得比"把代码丢给 AI"更强 | 1 @00:00 / 2 @00:59 |
| M3 | SOP 分两大类：**错误码类**（"傻瓜似的"，低价值）和**解决方案/场景类**（慢接口怎么查、系统挂了查哪个网元）；再加一个**通用类**（错误码→日志→全链路→模型分析） | 2 @02:24 / 1 @05:17 |
| M4 | **精华在第二小节**：按错误码拉日志 → 取该条日志的 **PS ID（trace id）** → 把该 PS ID 的**全部日志打包** → 交 AI 推出调用链、根因、**排查步骤**，即"自动生成 SOP" | 3 @07:35 / 2 @06:14 |
| M5 | 需要一层**工具**：观测云日志查询、接口耗时查询、K8S 节点存活查询、找代码 | 2 @02:24 |
| M6 | 入口走**企业微信**：群里 @「数字化服务平台智能小助手」，机器人索要截图/视频/客户 ID，分析完把建议回到群里；**服务经理是第一线用户**；闭环后自动 @ 原报障人 | 1 @13:53–15:31 / 1 @23:14 |
| M7 | **两类受众**：面向开发的**思考链默认隐藏、可展开**；面向业务的只要「问题描述 / 影响面（功能影响 + 影响人数）/ 解决方案」。且明确**"太花哨了"，展示层不是重点** | 1 @11:03 / 1 @21:01 |
| M8 | **能力边界要诚实**：bug 类 AI 只能**定位到代码位置**，给不了解决方案、恢复不了；数据类/业务类可以给解决方案。**问题必须打标签分类** | 3 @25:09 / 1 @25:22 / 1 @26:16 |
| M9 | **兜底判断（爆炸半径）**：先看有没有大面积报错——有则大概率网络/网元中断；只有单个客户报错、其他客户都正常，则大概率是客户自己的网络/浏览器，不是我们系统的功能问题。**路由顺序是先批量后单客户** | 1 @17:25–19:36 |
| M10 | 自动给代码加错误码：只写 log 语句、不改业务逻辑，需要**去重规则**和人工审核 | 3 @27:51 / 1 @28:17 |

---

## 二、先说冲突：M1「执行 SOP 自愈」**不采纳**

会议开场描述的友商能力是"调本机命令、模拟人的操作步骤把业务恢复"。这条**不进入我们的方案**，理由不是做不到，是它同时撞三条红线：

- **R1** 生产写工具一个都不注册；
- **R2** 平台 ToolGuard 的"批准"语义是**回放执行**被扣住的工具调用，与"永不自动执行"语义相反；
- **R3** 人工确认只把动作推到 `APPROVED_NOT_EXECUTED`，真正变更由有权限的人在 MateClaw 之外做完，回来调 `record-outcome` 登记。

`Diagnosis` 构造器里 `writeExecutionEnabled=true` 直接抛异常，`POST .../execute` 恒返回 409 —— 这是刻意的类型级/接口级封锁，不要为了对齐友商演示把它打开。

**而且会议自己已经推翻了这条路**：说话人1 @25:22 明确"你现在只能做到一个事情，是定位到问题，解决方案那种给不了"，说话人3 @25:11 "怎么让它恢复呢？恢复不了"。所以我们的差异化写死为 **M2 + M4**：靠自有观测云日志做**自动生成 SOP + 快速定位**，不靠自动执行。这一条要在对外汇报里主动讲清楚，而不是被问到才承认。

---

## 三、会议诉求 × 现状 对照表

| 会议诉求 | 现状 | 判定 |
|---|---|---|
| M3 场景类 SOP（慢接口 / 系统挂网元） | `SopEntry.errorCode` 是必填，`routingKey() = system:errorCode`；无错误码的场景**只能落未命中路**（LLM 分诊） | ❗**架构冲突** → C1 |
| M4 日志→SOP 自动生成 | 完全没有。SOP 只能人工经 `POST /sops` 注册 | ➕**新建** → C3（**本轮最高优先**） |
| M5 工具层（日志/耗时/K8S/代码） | `EvidenceSourceRouter` + `GuanceEvidenceAdapter` 已就位，但 `CanonicalEvidenceSchema` 只认 `log_count` / `metric` / `trace` 三种 signalKind | 🔧**扩展既有底座** → C2 |
| M9 爆炸半径 / 兜底路由 | `IncidentContext.impact` 只是一个 `String`，默认值"待确认"；没有任何批量-单客户判别 | 🔧**契约升级** → C4 |
| M8 故障分类打标签 | `Diagnosis` 无 `faultClass`；对 bug 类和数据类给的是同一种"建议动作" | 🔧**契约升级** → C5 |
| M6 企微接入 + 服务经理闭环 | 只有飞书**入站**卡片（`ts.` 前缀）；出站未做；无企微 | ➕**新建** → C6 |
| M7 双受众渲染 | `DiagnosisDerivation` 投影（判据 + 代入算式 + 三态 + 反事实）与 `DerivationChain.vue` 已经把"思考链"做完了 | ✅**基本具备**，只差业务视图 + 默认折叠 → C7（小改） |
| M10 自动注入错误码 | 无 | ⛔**划到域外** → C8 |
| M1 执行 SOP 自愈 | 恒 409 | ⛔**不采纳**（见 §二） |

---

## 四、架构变更 C1–C8

### C1 · SOP 从"错误码一类"扩为"三类"，路由键泛化（附 D1 修订）

**问题。** 会议明确说错误码类 SOP 是"傻瓜似的、没什么价值"，真正值钱的是场景类。但我们今天的确定性入口 `TroubleshootingIntakeService` 只认 `(system, errorCode)`，`SopEntry.errorCode` 必填 —— **价值最高的那一类反而被强制丢进未命中的 LLM 路**。这是这次会议暴露的最大架构缺口。

**改法。**

1. 新增 `SopKind { ERROR_CODE, SCENARIO, GENERIC }`，`SopEntry` 增加 `kind` 与 `scenarioKey`，`errorCode` 从"必填"改为"ERROR_CODE 类必填"。
2. 路由键升级为 sealed `RouteKey`：
   - `code:{system}:{errorCode}`（现状，保持不变，零 LLM）
   - `scenario:{system}:{scenarioKey}` —— `slow_interface` / `system_down` / `node_unreachable` 等**枚举值**
   - `generic:{system}` —— 兜底 playbook（错误码→日志→全链路→模型分析）
   `SopEntry.routingKey()` 改为返回 `RouteKey.toStorageKey()`；`mate_troubleshooting_sop.route_key` 列不用改结构（V174 只加 `kind` / `scenario_key` 两列 + 回填）。
3. **分诊两段式**：先用**确定性场景匹配器**（症状关键词表 + service + 已取到的指标特征）匹配 `scenarioKey`；匹配不到，再交一个**受限分诊模型**，其输出被强制约束在 `scenarioKey` 枚举内，选不中就照旧落未命中路。

**这是对 D1 的一次显式修订，必须记进 `HANDOFF.md`：**

> **D1′**：「零 LLM」的边界从"整条命中路径"收敛到"**判定链**"。
> 分诊（选哪条 SOP）允许用模型，但模型**只能选路、不能下结论**，且输出必须落在枚举的 `scenarioKey` 上；
> 一旦命中，取证 → 判据求值 → 规则裁决 → 结论，全程仍是确定性 Java，一次模型调用都没有。
> 安全性质因此不变：**结论永远由可复算的判据得出，不由模型自由发挥。**

**验收。** 慢接口、系统挂两个 playbook 以 `candidate` 入库并跑通；一条无错误码的报障能被确定性匹配器路由到 `scenario:` 键，且详情页的判定链与错误码类长得一样（同一套 `DiagnosisDerivation`）。

---

### C2 · 工具层：在既有取证底座上扩 signalKind（**不要新建第二条工具路径**）

会议列的四个工具，全部落在 `EvidenceSourceAdapter` 这个已经存在的边界后面。关键约束：**命中路（`EvidenceSourceRouter` 直调）和未命中路（Agent 经 `TroubleshootingEvidenceTool` 调）必须共用同一个 router**。这样新增能力两边同时生效，而未命中路 Agent 的工具白名单依然只有 `collect_troubleshooting_evidence` 一个函数 —— **R4（Agent 锁死只读）不被稀释**。给 Agent 再挂一个工具就是在拆这条红线，不要做。

| 新 signalKind | 用途（会议对应） | 落点 |
|---|---|---|
| `log_search` | 按错误码/关键词拉日志样本与计数 | `GuanceEvidenceAdapter` |
| `log_trace_bundle` | **按 PS ID 拉全链路日志包**（M4 的核心） | `GuanceEvidenceAdapter` |
| `interface_latency_rank` | 近 N 分钟慢接口 Top-N | `GuanceEvidenceAdapter` |
| `k8s_workload_health` | pod / 节点存活 | 新 `K8sEvidenceAdapter`（只读 API） |
| `blast_radius_probe` | 同一错误在窗口内的**客户维度分布**（M9） | `GuanceEvidenceAdapter` |
| `code_lookup` | 按类名/方法/日志文案检索代码片段 | 新 `CodeSearchAdapter` |

**两个必须做到、否则会静默出错的点：**

- `CanonicalEvidenceSchema` 现在只声明了 `log_count` / `metric` / `trace` 的字段与类型。**新 signalKind 必须同步声明 schema**，否则适配器返回的观测值会被判成 `MISSING`，判据变成 `UNEVALUATED`，最终表现为"什么都查不出来"却没有任何报错。
- `code_lookup` 不是可观测性源，边界要单列：**只读、限定仓库白名单、返回片段必须过 `TroubleshootingSecretRedactor`**。源表里出现过真实 Bearer/JWT 的教训不能重演。

---

### C3 · 日志 → SOP 自动生成流水线（**本轮最高优先，会议的"精华"**）

**流水线（`SopSynthesisService`）：**

```
1. 取样    log_search（错误码 或 场景关键词）→ 命中日志样本，抽出 PS ID
2. 拉包    log_trace_bundle（PS ID）→ 该次请求的全部跨服务日志
3. 压缩    确定性地压成调用链骨架：服务跳序、时序、异常点、耗时分布   ← 不经模型
4. 归纳    模型读压缩后的骨架，产出 SopDraft：假设 / 判据 / 取证请求 / 建议动作
5. 入库    强制以 status=candidate 走既有 POST /sops
6. 回放    影子模式：用历史 incident 回放这份 candidate，比对与人工结论是否一致
```

**四条不可协商的约束：**

1. **第 3 步必须在模型之前。** 全链路日志包直接喂模型会 token 爆炸，且会让模型去干"检索"而不是"归纳"。先确定性压缩，模型只负责归纳。
2. **入库只能是 `candidate`。** D2 的 `candidate → approved → deprecated` 单向审核流程**不允许被自动生成绕过**。自动生成的价值是"把人从写 SOP 里解放出来"，不是"把人从审核里踢出去"。
3. **整包日志入模型前必须过 `TroubleshootingSecretRedactor`。**
4. **生成的 `RecommendedAction` 不得带任何执行语义**，`writeExecutionEnabled` 仍恒 false。

**会议指定的首个验收案例（照做，不要换）：**

> 说话人1 @09:37：拿上周那个 **「会话消息发送失败」**——**没有错误码**——让它根据现有日志生成，**生成的操作步骤要和人工当时的解法一致**。

工程侧再加一条客观门槛：这份 candidate 在影子回放里，对已有历史 incident 要能复现人工结论，不一致的样本必须逐条解释，**不允许调 prompt 调到看起来对为止**。

---

### C4 · 爆炸半径成为一等公民 + 兜底路由（契约 v1.4）

**现状硬伤。** `IncidentContext.impact` 是一个 `String`，默认值 `"待确认"`。会议要求业务视图给「功能影响 + 影响人数」，还要按 M9 做路由决策——一个自由文本字段承载不了任何一件。

**改法。**

```java
record IncidentImpact(String functionScope, int affectedCustomers,
                      int affectedUsers, BlastRadius radius, String note)
enum BlastRadius { SINGLE_CUSTOMER, MULTI_CUSTOMER, SYSTEM_WIDE, UNKNOWN }
```

**路由顺序照会议原话实现（先批量、后单客户）：**

1. 先跑 `blast_radius_probe`；
2. `SYSTEM_WIDE` / `MULTI_CUSTOMER` → 直接进网元/基础设施 playbook（`scenario:system_down`）；
3. `SINGLE_CUSTOMER` 且同窗口内其他客户正常 → 输出**排除法结论**：「系统侧未见异常，疑似客户端网络或浏览器」。

**这条结论的诚实约束（很重要）：**

- confidence **不得高于 MEDIUM**，且必须显式标注这是**排除**而非**定位**；
- 它对应的判据结果是 `CriterionOutcome.EXCLUDED`（真的求值为假），**绝不能**和 `UNEVALUATED`（证据缺失、根本没验过）混在一起显示 —— 这个区分我们已经在 `CriterionOutcome` 里做出来了，正好用上；
- 会议自己承认**浏览器兼容性问题分析不出来**（1 @16:17「你分析不出来」／3 @16:37「这没办法了」）。所以我们只做到"系统侧无异常"，**不假装能定位客户端问题**，写进不做清单。

---

### C5 · 故障分类打标签，按类兑现不同承诺（契约 v1.4）

会议 M8 的结论很清楚：**bug 类只能定位，数据/业务类才能给解决方案。**

```java
enum FaultClass { CODE_BUG, DATA_FIX, BUSINESS_OPERATION, EXTERNAL_CLIENT, INFRASTRUCTURE }
```

`Diagnosis` 增加 `faultClass`，并让它**改变输出形态**，不只是一个展示标签：

| FaultClass | 系统承诺 | 输出 |
|---|---|---|
| `CODE_BUG` | **只定位** | 代码位置（类/方法）+ 片段 + 证据链；**不给解决方案、不给恢复动作** |
| `DATA_FIX` / `BUSINESS_OPERATION` | 可给解决方案 | 建议动作（仍是建议；执行在 MateClaw 之外，`record-outcome` 回登） |
| `EXTERNAL_CLIENT` | 只给排除结论 | 见 C4，confidence ≤ MEDIUM |
| `INFRASTRUCTURE` | 定位到网元/节点 | 受影响组件 + 影响面 |

分类本身由 SOP 声明（场景类 SOP 天然知道自己属于哪类），自动生成的 candidate 必须带上 `faultClass`，审核时人要核对这一栏。

---

### C6 · 企微接入与服务经理闭环

**入口。** 企业微信群机器人「数字化服务平台智能小助手」，@ 触发。

**必须走领域 webhook（PAT 鉴权），不能走 Trigger 引擎。** `TriggerEntity.targetType` 只有 `agent` | `workflow`，而 workflow 的每个干活步都调 LLM（`AgentStepExecutor`）——走 Trigger 等于把命中路的零 LLM 保证扔掉。这条在 `HANDOFF.md` 里已经是定论，企微接入不例外。

**补充信息采集（会议 M6 的核心交互）。** 新增诊断状态 `AWAITING_INPUT`：识别到信息不足（缺客户 ID / 截图 / 视频）→ 机器人回帖索要 → 补齐后继续。`IncidentCompleteness` 枚举已经存在，直接复用。附件**只存引用与元数据**；**视频不做内容解析**（明确不做，别答应）。

**闭环。** 服务经理点「闭环」→ 复用现有 `close` + `record-outcome`；出站消息 @ 原报障人 → 复用 T7 的出站通道。**会议顺带把 T7 卡住的产品决策给了**：接收方就是"报障那条会话所在的群 + @ 原报障人"，不需要再设计"哪个系统推哪个群"的映射。

**身份 fail-closed。** 企微 userid → `ExternalIdentityEntity{provider="wecom"}` → userId；**未绑定即拒绝**，与飞书 `CardOperatorResolver` 同一规则。匿名的 open_id 不可问责，不能拿来推进状态机。

---

### C7 · 双受众渲染（小改，并冻结展示层）

- **业务视图（默认）**：问题描述 / 影响面（功能影响 + 影响人数，来自 C4）/ 解决方案或定位结论（按 C5 的 `faultClass` 决定措辞）。
- **开发视图**：判定链默认**折叠**，一键展开。

这块**改动量很小**——`DiagnosisDerivation` 和 `DerivationChain.vue` 已经把最难的部分（代入算式、三态、反事实）做完了，只需加一个 `BusinessSummary` 投影 + 前端默认折叠。

**同时执行会议的减法（1 @11:03「我感觉现在做这个东西太花哨了」）：冻结展示层新特性。** 已有的多套原型页（`console-*.html`）不再加新版本，只保留用于汇报。任何"再做一版页面"的提议在本轮排期外。

---

### C8 · 自动注入错误码 —— 划到本域之外

会议共识是"只写 log 语句、不改业务逻辑、要去重规则、要人工审核"。技术上成立，但**它是研发侧的代码变更流程，不是排障域的职责**。本仓只提供两件事：

1. 错误码注册表的**只读查询**接口（供分配时去重）；
2. 从诊断闭环反推的 **`missing_error_code_hint`**（"这个位置该有个码"）作为知识候选产出，由人带去代码仓改。

**明确不做：不在本仓自动改代码、不自动提 PR。** 理由是 R1/R3 的同一条精神——本系统不做生产变更，而代码变更是最强的生产变更。

---

## 五、排期与验收

| 阶段 | 内容 | 验收 |
|---|---|---|
| **P6**（最高优先） | C2 的 `log_search` + `log_trace_bundle` + C3 合成流水线 MVP | **「会话消息发送失败」（无错误码）跑通**，生成步骤与人工解法一致（由会议指定的人判定）；影子回放不一致样本逐条有解释 |
| **P7** | C1 三类 SOP + D1′ + 慢接口 / 系统挂两个 playbook | 无错误码报障能被确定性匹配到 `scenario:` 键，判定链与错误码类同构 |
| **P8** | C4 爆炸半径 + C5 分类标签（同改契约 → 合并为 v1.4，一次迁移 V174） | 单客户/批量两条路由各有回归用例；`EXCLUDED` 与 `UNEVALUATED` 在 UI 上可区分 |
| **P9** | C6 企微接入 + 闭环通知；C7 双受众渲染 | 群内 @ → 索要信息 → 出结论 → 服务经理闭环 → 自动 @ 原报障人，全链路演练一次 |
| **不排期** | C8 的代码改写部分；M1 自愈执行 | —— |

P6 排在 C1 之前是有意的：**先证明"我们能从日志自动生成 SOP"这件差异化的事**（M2），再回头把场景类 SOP 的路由结构补齐。反过来做的话，会先花两周搭结构，却还没有任何一条自动生成的 SOP 可以放进去。

---

## 六、风险与仍需人介入的事

| 风险 | 说明 | 处置 |
|---|---|---|
| PS ID 未全链路贯通 | M4 整条流水线的前提是同一次请求的日志能靠 PS ID 串起来。**没验证过。** | 并入 T2 的内网核实窗口，**先验这一条**，不通则 P6 方案要改 |
| 模型生成"看起来合理但错"的判据 | 这是 T2 已经点名的最危险失败模式：判据算错不报错，只给出错误但合理的根因 | candidate 必审 + 影子回放双闸；不允许调 prompt 调到看起来对为止 |
| 全链路日志含敏感信息 | 源表里出现过真实 Bearer/JWT | 入模型前 `TroubleshootingSecretRedactor` 全量过；`code_lookup` 返回片段同样过滤 |
| D1′ 放松了零 LLM 的边界 | 分诊引入模型，是**这次会议带来的唯一一次安全边界让步** | 明确写入 HANDOFF；模型输出受限于枚举，选不中就落未命中路；判定链本身仍零 LLM |
| 汇报口径 | 友商演示"自愈"，我们没有 | 主动讲：我们的差异化是自动生成 SOP + 快速定位（M2/M4），自愈不做且**会议自己已论证做不到**（M8） |

**会议带来的一个意外解绑**：T1（L0 三个路由键冲突）此前被当成"阻塞一切"。按 M3，场景类 SOP 根本不用错误码 —— 所以 T1 现在**只阻塞错误码类 SOP**，不再阻塞 P6/P7 的主攻方向。T2（观测云字段与阈值核实）仍是硬阻塞，而且随着 C2 新增 signalKind，核实范围要扩大。
