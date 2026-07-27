# 待办清单 —— IT 智能排障系统

> 面向接手的 AI / 工程师。**每条都写清了「为什么这么做」和「做到什么算完」**，
> 因为这个项目里大部分坑不是不会写代码，而是不知道某个看似合理的做法会破坏哪条red line。
>
> 开工前必读：`CLAUDE.md` → `HANDOFF.md`（决策 D1–D8、四条红线、矛盾分析）→
> `rfcs/intelligent-troubleshooting-design.md`（架构 + 源码索引）。
>
> 当前状态：P0 内核 + P1 接入身份 + P2 交付闭环 + P3 命中路证据适配底座 + 推导投影 + SOP 管理均已完成，
> 排障域定向测试 **92 项**通过；应用上下文启动测试通过。分支 `claude/intelligent-troubleshooting-design`。

---

## 零、动手前请先理解的四条红线

违反其中任何一条，都会毁掉这套系统存在的理由。它们不是风格偏好：

1. **命中路零 LLM。** `(system, error_code)` 命中已知故障模式时，全程走确定性 Java，
   一次模型调用都不能有。**因此接入不能走 Trigger 引擎**——它只能分发给 agent 或 workflow，
   而 workflow 的每个干活步都调 LLM。
2. **生产写工具一个都不注册。** 平台的 ToolGuard「批准」语义是**回放执行**被扣住的工具调用；
   排障要的是**永不自动执行**。语义相反，所以生产写绝不能挂进 ToolGuard/approval。
3. **人工确认只推进领域状态机，执行 0 个工具。** 批准把动作从 `PENDING` 推到
   `APPROVED_NOT_EXECUTED` 就结束了，真正的变更由有权限的人在 MateClaw 之外执行，
   回来调 `record-outcome` 登记。`Diagnosis` 构造器里 `writeExecutionEnabled=true` 直接抛异常——
   这条红线在类型系统层面不可表达，别试图绕。
4. **未命中路 agent 锁死只读**（`AgentToolBinding` 白名单 + ToolGuard BLOCK 写/shell/file）。

**还有一条贯穿全项目的纪律：宁可承认做不到，也不要假装做到了。**
`fixtureMode` 恒 true、未命中路返回 409、推导 `faithful=false`、卡片未绑定身份即拒绝——
这些"示弱"的设计都是刻意的，不要为了让 demo 好看而抹掉它们。

---

## 一、主攻：接真实数据（**当前主要矛盾**）

代码闭环已通且可测，但**从未在真实数据上跑过一次**。SOP 库是 fixture、观测云未联调。
再往下堆功能都是在未验证的地基上加层。这三件事都需要**人的介入**，AI 单独做不了：

### T1 · 清理 L0 三个路由键冲突 🔴 阻塞项
- **问题**：`101014` / `101034` / `101040` 在源表里一码对应多个业务上下文，
  与 D1「`(system,error_code)` 唯一路由」的前提冲突。
- **为什么必须先解决**：路由键冲突时 `register` 会 fail-closed 抛 409。这是**故意的**——
  让一码多义暴露出来，而不是让后写的 SOP 静默覆盖先写的。绕过它就等于把知识库的歧义
  带进了确定性判定。
- **怎么做**：需 owner 裁决拆分口径（拆成不同 service？还是加二级判别字段？），
  详见 `l0/quality_report.md`。**这是人的决策，不是技术问题。**
- **完成标准**：三个冲突码各自有明确唯一的路由键，清洗器不再报 blocker。

### T2 · 内网核实观测云字段与阈值 🔴 阻塞项
- **问题**：`l0/activated/903001.md` 里的 `evidence_dql` / `anomaly_criteria` 标着 `«待核实»`。
  DQL 的数据源名、字段名、阈值都没在真实观测云上验证过。
- **为什么重要**：判据算错不会报错，只会给出**错误但看起来合理的根因**——
  比不给结论危险得多。
- **怎么做**：需内网窗口（`*.prd.sangfor.com`，DF-API-KEY 鉴权）。
- **完成标准**：903001 的每个 `EvidenceRequest` 都能在真实观测云取到数，
  字段名与 `AnomalyCriterion` 引用的一致，阈值经过真实数据验证。

### T3 · 真实 SOP 入库并放开 fixtureMode
- **前置**：T1、T2 完成。
- **入库通道已经就绪**（本轮刚做完）：
  ```
  POST /api/v1/troubleshooting/sops              注册（需 manage:troubleshooting）
  GET  /api/v1/troubleshooting/sops              浏览注册表
  GET  /api/v1/troubleshooting/sops/{sys}/{code} 读取完整 SOP
  POST /api/v1/troubleshooting/sops/{sys}/{code}/status  promote/retire
  ```
  从 `l0/sop_kb.json`（146 码）导入时**必须以 `candidate` 注册**，逐条审核后再
  `→approved`。状态流转是单向的（`candidate→approved→deprecated`），不能回退——
  approve 错了就 deprecate 后发新版，留下痕迹。
- **放开 `fixtureMode`**：现在 `TroubleshootingIntakeService.EVIDENCE_IS_FIXTURE` 硬编码为 `true`。
  T4 工程链路已经落地，但**只有 T2 的真实字段/阈值核实与 T3 审核入库完成后才能改**；
  “API 可达”不等于“证据语义可信”。

---

## 二、后端功能梯队

### T4 · P3：D8 证据源适配器（观测云首个） 🟡 命中路底座完成，内网验证待 T2
- **为什么**：此前证据只能由调用方传入；现在调用方证据仍优先，缺失的 SOP 请求会由平台只读补齐。
- **架构已定**（RFC §6）：`EvidenceSourceAdapter` 接口 → 每平台一个实现 → `EvidenceSourceRouter`
  按 `(system, signal_kind)` 选源 → 归一到 canonical `observed` 字段。
- **关键设计（别做错）**：**一份 adapter 两个调用方**——命中路由领域 service 直接 Java 调用（零 LLM），
  未命中路把同一个方法加 `@Tool` 注解暴露成只读 ToolCallback 给 agent 用。
  **不要写两套取证代码**，那会导致两条路看到不同的世界。
- **fail-closed**：任何异常/超时 → `EvidenceResult(status=MISSING)`，不抛 500。
  上游会正确地把它当成"判据无法求值"（≠ 已排除）。
- **已完成（2026-07-27）**：`EvidenceSourceAdapter` + `EvidenceSourceRouter`；
  `GuanceEvidenceAdapter` 按官方 query-data API 发请求并归一 `series.columns/values`；
  `RecordedReplayAdapter` + 脱敏 903001 三信号样本；主备降级、模板注入防护、配置装配、入口补证与
  `GET /api/v1/troubleshooting/evidence/sources` 均有测试。默认两个数据源都关闭，源状态不暴露凭据。
- **尚未完成的验收**：没有内网窗口，故尚未证明 `GuanceEvidenceAdapter` 对 903001
  measurement/字段/阈值能取到真实数据；per-binding verification 与全局 `/readyz` 汇总也留待 T2。
  运行说明见 `evidence-adapter-runbook.md`。

### T5 · P4：未命中路 ReAct agent（补旧缺口 G1）
- **现状**：`RouteMode.LLM_FALLBACK` 枚举在，但没接线。未命中时 `TroubleshootingIntakeService`
  直接抛 409（诚实地说"未命中路没接"）。
- **怎么做**：领域 service 同进程调 `vip.mate.agent.AgentService.chat()`（ReAct，不是 `execute()`
  的预先规划——排障分诊是"看一眼证据再决定下一步查什么"的探索型任务）。
- **笼子（红线 4）**：给这个 agent 建专用 `AgentToolBinding`，**只绑 `collect_*` 只读工具**；
  ToolGuard 对 shell/file/写类配 **BLOCK**；**绝不给它 NEEDS_APPROVAL**——那个语义是批准后会执行。
- **产出必须回落领域状态机**：agent 的结论只是"建议 + 证据引用"，仍需人工确认，
  且低置信必须 abstain。
- **完成标准**：未命中时 agent 能做只读探索并给出半程结论，工具绑定被测试锁死为只读。

### T6 · 知识候选审核工作流（D2 闭环最后一环）
- **现状**：候选在关闭时入 Outbox，**可以看但不能审**——
  `GET /api/v1/troubleshooting/sops/candidates` 已可列出（本轮刚做）。
- **未做的原因（重要）**：Outbox 那个 status 字段是**发布**语义（PENDING/PROCESSING/PUBLISHED/FAILED，
  指"有没有交给 sink"），**不是审核**语义（"专家有没有认可"）。两者长得像，
  **混用会让一次投递重试伪装成一次审核通过**。所以候选审核需要自己的状态，是个真实的设计增量。
- **要做的设计决策**：候选审核状态存哪（新列？新表？）、审核通过后如何转成 SOP 编辑
  （自动生成 SOP 草案？还是只做提示由人编辑？）、驳回是否要记原因（建议要，是知识运营的输入）。
- **红线**：候选**永不直接覆盖已审核 SOP**——一次故障不足以重写确定性路径依赖的知识。

### T7 · 出站飞书卡片推送（P2 收尾）
- **现状**：**入站点击已通**（`ts.` card kind 已注册，点击→推进状态机，未绑定 MateClaw 身份即拒绝）。
  出站没接。
- **两个障碍**：
  1. 平台的 `FeishuCardRenderer` 接口签名是 `render(ApprovalNotice)`——是 tool-guard 的形状，
     渲染不了诊断。当前注册的 renderer 是**故意会抛异常**的，防止有人误接后送出误导性卡片。
  2. **"哪个群收哪个系统的故障"这个绑定尚未设计**——这是产品决策，不是技术问题。
- **建议做法**：per-system 配置表（与 T9 的 `delegation_stage` 同一张表），
  避免再造一套配置机制。
- **红线**：卡片上**只放"确认"**。批准生产写和关闭归档要留在工作台——
  卡片摘要不足以支撑这两个决定。

### T8 · P5：放权阶梯（D5）
- **架构已定**（RFC §10）：per-system `delegation_stage`（S0 影子 → S1 建议 → S2 只读自动取证 →
  S3 半自动）+ `system/featureflag` 做全局 kill-switch（fail-closed，未配 flag 默认最保守档）。
- **关键**：**逐系统档位存领域表**，不要塞进 FeatureFlag 的 whitelist——
  那个键是 kbId/userId，不是"系统"，硬掰会埋概念错配。
- **毕业必须数据驱动**：S0 影子跑出的诊断入库后比对"自动 vs 人工"一致率，达标才升档。
- **底线不随阶段转移**：写操作永远人工，S3 也不例外。

---

## 三、前端待办

### T9 · Vue 工作台补齐
- **已落地**：`mateclaw-ui/src/views/Troubleshooting/{index,DerivationChain}.vue`
  （队列 + 判定链 + 处置弹窗），判定链已接 `GET /diagnoses/{id}/derivation`，
  三态（成立/已排除/无法求值）与代入运算都是真实数据。
- **待补**：
  - [ ] SOP 管理界面（注册/浏览/promote，接 T3 那组 API）——**导入 146 码需要它**。
  - [ ] 知识候选审核界面（依赖 T6 定下审核工作流）。
  - [ ] 证据的"重放查询"按钮（适配器已具备；仍依赖 T2 真实绑定核实与专用重查 API）。
- **视觉基线**（照此，别另起炉灶）：冷调中性 + 单一信号蓝 `#2f5cf5`；
  语义色只在有意义处；**机器吐出的数据用等宽字体，人读叙述用无衬线**；
  统一描边 SVG 图标（不用 emoji）；支持浅/深双主题。
- **页面红线**：无"执行"按钮；批准按钮文案必须写明"推进状态，系统不执行"；
  弃权时要解释为什么没有恢复动作，不能留空白。

### T10 · 与一线核对信息架构
- 判定链的阶段划分（现象/范围定位/取证/判定/根因）需要和真实排障心智核对，可能微调。
- 原型里的"影响面/活体状态/在场签收"等维度是否全进 MVP，按放权阶段裁剪。
- **注意**：`IncidentContext.impact` 目前只是**一个字符串**，原型里那些
  "148 工单/12 大客户/扩散中"的结构化影响面**并不存在**，要做得先扩契约。

---

## 四、已知的诚实缺口（不是 bug，是有意为之）

接手时别把这些当成待修复的缺陷去"优化"掉：

| 现象 | 为什么是对的 |
|---|---|
| `fixtureMode` 恒 `true` | P3 工程链路已在，但 903001 绑定与阈值未完成 T2 内网核实，仍无权声称证据可信 |
| 未注册错误码 → 409 | 这是**知识缺口**，不是诊断失败；编造诊断比报错危险 |
| 无 error_code / SYMPTOM → 409 | 未命中路未接线，不假装能处理 |
| 弃权时 `recommendedActions` 恒空 | 契约保证：不确定就不给恢复建议 |
| `/execute` 恒 409 | 让"平台不执行生产写"在 HTTP 边界可见可测 |
| 推导可能 `faithful=false` | SOP 会演进；宁可承认还原不了，也不给看似合理的假推导 |
| 卡片未绑定身份即拒绝 | 飞书 open_id 不可追责，用它记审批等于废掉审批 |
| `Vertical903001Test` 用内存 mapper | 它证明领域组合自洽；SQL 由迁移测试和持久化单测覆盖，分工明确 |

---

## 五、工程约定

- **测试风格**：JUnit 5 + Mockito + AssertJ，纯单测为主。领域测试在
  `mateclaw-server/src/test/java/vip/mate/troubleshooting/`。
- **跑测试**：
  ```bash
  mvn -pl mateclaw-server -am -Dtest='vip.mate.troubleshooting.**.*Test' \
      -Dsurefire.failIfNoSpecifiedTests=false test
  ```
  （`-am` 必须带，否则 plugin-api 依赖解析失败；`-Dsurefire.failIfNoSpecifiedTests=false`
  也必须带，否则兄弟模块没有匹配测试会报错。）
- **前端类型检查**：`cd mateclaw-ui && npx vue-tsc --noEmit`。
  注意 `npm ci` 会失败（锁文件是 pnpm 的），用 `npm install`。
  另有 5 项既存失败的 vitest（product-cards / streaming-render），**与排障无关**，改动前就存在。
- **迁移**：新增表要在 `db/migration/{mysql,h2,kingbase}/` **三个方言目录各写一份**。
- **两个 MyBatis-Plus 坑**（踩过）：
  - `getParamNameValuePairs()` 惰性填充，**先调 `getSqlSegment()`** 才有值；
  - 聚合更新走 **wrapper-only（entity 传 null）**，写 fake 时要照此并保留版本闸门。
- **纪律**：不擅自开 PR；改 RFC 保持 § 编号连续；
  源表 xlsx 含真实 token/IP/人名，**未入库、不得入库**。

---

## 六、建议的接手顺序

1. **如果你能拿到内网/owner 决策** → 走 T1 → T2 → T3，这是主要矛盾所在，
   其余都是在未验证的地基上加层。
2. **如果只能做纯工程** → T4 已完成；下一项优先 T9 的 SOP 管理界面（导入 146 码需要它），
   或在进入 T5 前先把只读 agent 的工具白名单与安全测试设计清楚。
3. **不建议现在做**：T7 出站卡片（等产品定"哪个群收什么"）、
   T8 放权阶梯（等 T1–T3 有真实数据才有意义）。
