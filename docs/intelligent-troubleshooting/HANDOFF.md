# HANDOFF —— IT 智能排障系统 on MateClaw（会话记忆）

> 供后续 AI / 工程师直接接续。**本文件 + `rfcs/intelligent-troubleshooting-design.md` 两份读完即可上手。**
> 状态：架构已逐条源码核对通过并落 RFC；**P0 领域内核 + P1 接入与身份已于 2026-07-25 完成，P2 尚未开始**。
> 工作仓库：**webonne/mateclaw**（旧仓库 webonne/MetaClaw 已归档为只读参考，见 §6 指针；
> 本文件的 MetaClaw 时期原版保留在本仓库 git 历史与 webonne/MetaClaw 远端）。

---

## 1. 一句话

把故障处理从「人工翻系统 + 经验判断」升级为「告警/工单驱动 · 智能路由 · 自动取证 · 人机协同诊断 · 知识闭环」。
首个域：CSDP 工单/客服链路。落法：**MateClaw-server 内的确定性领域模块 `vip.mate.troubleshooting`**。

## 2. 八个已锁定决策（D1–D8，全部在 mateclaw 源码上核对兑现）

| # | 决策 | mateclaw 兑现（详见 RFC 对应节） |
|---|---|---|
| D1 | 确定性/AI 边界 =「(system,error_code) 命中？」 | 命中路=领域引擎零 LLM；Workflow 每步调 LLM 故不可承载（RFC §1/§3） |
| D2 | 知识库可演进（candidate→approved→deprecated） | 领域表自建审核生命周期；Wiki 是 LLM 管道、永不做权威（RFC §8） |
| D3 | API-first 故障上下文 Web 台 | 领域 Web 台 + IM/Web 双确认（RFC §5） |
| D4 | 自建 orchestrator + 工具走 MCP/ToolCallback | 领域 service 自跑循环；adapter 兼 `@Tool`（RFC §3/§6） |
| D5 | 影子 + 回归集 + 放权阶梯（写永不自动） | FeatureFlag(fail-closed)×per-system 档位 + 影子回归毕业（RFC §10） |
| D6 | 知识运营（沉淀嵌入流程、专家才评审） | `manage:troubleshooting` capability 门控审核（RFC §9） |
| D7 | 与平台产品一体、运行时隔离 | 单 JAR 内兄弟包、逻辑不寄生 Workflow（RFC §2） |
| D8 | 证据源开放适配（OAL，观测云首适配器） | 一份 `EvidenceSourceAdapter` 两个调用方 + Router + 归一（RFC §6） |

**信任工程五约束**（沿用）：①确定性优先（LLM 不生成恢复动作）②强制引用证据 ③结构化输出+校验闸门
④置信度校准+abstain ⑤上下文预算。

## 3. 四条红线（每个 PR 自检，源码依据见 RFC §5/§12）

1. **生产写工具一个都不注册**（ToolGuard 批准=回放执行，语义与我们相反）。
2. **人工确认只推进领域状态机、执行 0 个工具**（≠ ToolGuard 批准）。
3. **写操作永远外部人工 + `record-outcome` 登记**；平台不连生产写执行器。
4. **未命中路 agent 锁死只读**（`AgentToolBinding` 白名单 + ToolGuard BLOCK）＋命中路零 LLM。

## 4. 当前阶段矛盾分析（毛选方法论 · 2026-07 刷新）

**矛盾清单**（P1 完成后已转化，2026-07-25 二次刷新）：
- [已能接入并产出诊断] vs [产出后无法处置与闭环]（P1 打通了「报障→诊断→读取」，
  但确认/转派/批准/登记/关闭这条人机协同链和工作台都还没有）
- [知识质量天花板]（只读可自动化 30/146≈21%、3 路由键冲突、103 处字符丢失）vs [自动化雄心]
- [Java 重实现工作量] vs [Python MVP 已验证资产]（38 测试 + 7 subtests，可同构直译）
- [单点竖切验证]（903001 需内网联调）vs [面上铺开]（146 码、多系统）
- [信任建立]（影子期慢积累）vs [见效压力]

**⭐ 主要矛盾**：[能接入并产出诊断] vs [产出后无法处置与闭环]。P1 已让用户能在 MateClaw
发起一次诊断并读到结果，入口与身份不再是瓶颈；当前系统性质由「诊断出来了却没人能对它做什么」规定
——确认、转派、批准、外部结果登记、关闭沉淀这条链没有 REST 与界面，知识候选 Outbox 因此永远空转，
D2 知识演进和 D5 影子回归都拿不到真实数据。
**性质**：非对抗性（工程演进矛盾）→ 分阶段实施 + 集中兵力解决。
**矛盾的主要方面**：在「交付与闭环缺位」一侧——状态机在代码里完备且有 6 项测试，但外部无从驱动它。
**应对**：集中兵力主攻 P2（生命周期 REST + 工作台 + 列表查询），把 903001 fixture 竖切从
「报障→诊断」延到「关闭→知识候选」；数据侧 blocker 走 owner 裁决流程并行推进（不占工程主力）。
**⚠️ 需监控（矛盾转化）**：P0–P2 跑通后，[知识质量 vs 自动化范围] 将上升为主要矛盾——它是全过程的
根本天花板（前一阶段已确立：**系统天花板 = 知识质量，不是技术**，故不承诺"上线即全自动"）；若内网
联调窗口先到，临时优先核实 903001 真实取证。

**持久战三阶段映射**（底线不随阶段转移：写操作永远人工）：
- 战略防御 = S0 影子：纪律为王、不承诺自动化、积累回归数据；
- 战略相持 = S1–S2：建议卡 + 只读自动取证，知识候选闭环开始造血（146 码逐批审核毕业）；
- 战略反攻 = S3：半自动 + 多系统接入 + 平台化复用。

**群众路线**：知识候选从一线关闭沉淀中来，审核毕业后回到一线工作台/卡片中去（D6 贡献者收益、覆盖率进 KPI）。
**批评与自我批评**：每 PR 四条红线自检；影子回归一致率是客观批评者。
**实事求是**：一切结论逐源码核对（已做，RFC §12）；内网核实 903001 DQL 前不宣称"取证已验证"。
**星火燎原**：903001 竖切 = 根据地；先 1 个码全闭环 → 18 个 P0/P1 → 146。

## 5. 刷新后的代办（集中兵力重排；细目见 RFC §13）

**已完成（2026-07-25）**：
- **P0** 领域骨架 + record 契约 + 6 类 sealed 规则引擎 + `DeterministicDiagnosisService`
  命中路端到端编排 + 人工控制状态机 + MyBatis-Plus/Flyway `V172` 三方言 +
  携带 `workspace_id` 的事务 Outbox/poller + 五分钟幂等；`vip.mate.troubleshooting.**.*Test` 共 33 项通过。
- **P1 接入与身份**：`TroubleshootingIntakeService`（路由→确定性诊断→持久化）+
  `TroubleshootingController`（`POST /api/v1/troubleshooting/incidents` 接入、
  `GET /diagnoses/{id}` 读取、`POST .../actions/{id}/execute` 恒 409）+
  三个 capability 挂进 `RoleCapabilities`（viewer→view / member→operate / admin→manage）。
  排障域测试增至 **40 项**，连同 workspace 域回归共 153 项通过。
  三条诚实性约束已固化为测试：**未注册 SOP → 409 知识缺口**（不编造诊断）、
  **无 error_code / SYMPTOM → 409**（未命中路未接线，不假装能处理）、
  **`fixtureMode` 恒 true**（P3 取证适配器未到位前，调用方不得声称证据已核实）。
  webhook 鉴权**不需要新过滤器**：`JwtAuthFilter` 已按 `mc_` 前缀识别 PAT，告警源用受限 PAT 即成为正常主体。

**主攻（顺序执行，单点突破）**：
1. **P2** Web 工作台 + 生命周期 REST（confirm / transfer / approve / record-outcome / close）
   + 诊断列表查询 + `ts.` 飞书 card kind + R1/R2/R3 回归测试。
   **验收 = 903001 fixture 竖切在 mateclaw 端到端跑通，测试对齐 Python MVP 38 项。**

**钳制/并行（不占主力，多为需内网/人力项）**：
- L0 数据 blocker：3 个路由键一码多义（101014/101034/101040）owner 裁决 + 103 处字符丢失回源表恢复
  （清洗器 fail-closed，阻断未解决前拒绝覆盖 canonical KB，见 `l0/quality_report.md`）；
- 内网联调窗口准备（观测云 `*.prd.sangfor.com`，DF-API-KEY 鉴权）→ 核实 `l0/activated/903001.md` 的
  `«待核实»` 字段；
- 903001 模式复制到其他高频码（901002/2000001/801008…backlog 见 `l0/inventory_report.md`）。

**后续梯队**：P3 D8 适配器（Guance 首个 + RecordedReplay 回归）→ P4 未命中 ReAct agent（只读笼，补旧 G1）
→ P5 放权阶梯 + 知识运营（覆盖率/可自动化率纳入考核）。

## 5.5 前端/页面设计（本轮已收敛方向 · 尚是 HTML 原型，未落 Vue）

**产物入口**：`docs/intelligent-troubleshooting/index.html`（设计门户，汇报用；串起下列所有原型 + 设计主线叙事）。

**产品定位锁定（关键，别再跑偏）**：这套系统的页面**主角是「帮开发从现象快速定位到根因」**——不是运维审批流转台。用户明确纠正过两次：
1. "阶段"指的是**单次事件从症状到根因的定位过程**，不是处置流程（接入/批准/关闭）的人工流转；
2. 详情页要能看到这条**根因定位链**，服务于开发快速定位。

**设计主线（四次迭代收敛，index.html 有可视化）**：
- v1 `console-disposition.html`——信息陈列（三栏工作台）；
- v2 `console-disposition-v2.html`——决策中心（Diagnosis 提为常驻主角、流程降为进度带、置信阈值参照、批准前强制复核）；
- v3 `console-disposition-v3.html`——注意力自适应（按 不确定性×影响 三种姿态：高影响确认/自动驾驶/调查工作台；补影响面、活体状态、拆解式置信、异议一等公民）；
- **现行 `console-rca.html`——根因定位视图**（答案先行 + 收敛漏斗「全平台→系统→服务→依赖→根因」+ 五阶段定位链 现象/范围定位/取证/判定推理/根因，证据与 DQL 可展开重放；命中路 2 秒定位，未命中路 agent 探索到半路弃权、把开发放到"跑起来的起点"）。

**另一现行屏**：`console-overview.html`——值班总览看板（所有故障按处置阶段铺开、系统自动列褪背景/等人列高亮、卡片显示阶段滞留时长、主动喊瓶颈；点卡片下钻到定位视图）。

**视觉基线（已定，后续 Vue 实现照此）**：冷调中性 + 单一信号蓝 `#2f5cf5`；语义色只在有意义处（红=现象/绿=根因/琥珀=弃权）；**字体双角色有含义**——机器吐出的数据（错误码/指标/DQL/时间戳/置信度）用等宽，人读叙述用无衬线；统一描边 SVG 图标（不用 emoji）；避开"左侧色条+圆角卡"套路；支持浅/深双主题。

**页面上必须守的红线（对齐 §3）**：无"执行"按钮；批准=推进状态机、不执行；写恢复动作显示为"转派+外部登记结果"；agent 步骤标只读；结论强制挂证据引用。

**下一步（UI 线，与后端 P0 并行、非阻塞）**：
- [ ] 现行两屏（`console-rca` 根因定位 + `console-overview` 总览）经用户/一线值班验证信息架构后，落成 **Vue 3 + Element Plus** 组件，进 `mateclaw-ui/src/views/Troubleshooting/`，路由 `meta.requiredCapability='view:troubleshooting'`（对齐 §9 capability）。
- [ ] 定位链的**阶段划分**（现象/范围定位/取证/判定/根因）需与一线实际排障心智核对，可能微调。
- [ ] 证据的"▷重放 DQL"要接 D8 真实适配器（P3）后才有真数据；当前是 fixture 演示。
- [ ] 原型里的"影响面/活体状态/在场签收"等维度是否全部进 MVP，按放权阶段裁剪。

## 6. 指针与安全口径

- **新架构（唯一现行设计）**：`rfcs/intelligent-troubleshooting-design.md`（§1–§13 + §14 实施战略；
  每条结论有源码位置索引）。
- **P0/P1 实现入口**：`mateclaw-server/src/main/java/vip/mate/troubleshooting/`（`controller/` 已落 P1 接入）；迁移为三方言
  `V172__troubleshooting_domain.sql`；测试入口 `mateclaw-server/src/test/java/vip/mate/troubleshooting/`。
- **前端设计门户**：`docs/intelligent-troubleshooting/index.html`（汇报入口，串起现行原型 + 演进；详见 §5.5）。
  现行原型 `console-rca.html`（主推·根因定位）、`console-overview.html`（总览看板）；
  迭代过程 `console-disposition{,-v2,-v3}.html`。
- **MetaClaw 时期历史资产**（架构结论已被 RFC 吸收/取代）：蓝图 v0.3 `architecture-blueprint.html`、
  走读复核 `architecture-review.md`（含 G1–G7 缺口表）、D7/D8 设计稿、旧原型
  `console-prototype{,-b}.html` / `console-workbench.html`、`executive-summary.html`。
  **`l0/` 数据资产仍现行有效**（sop_kb.json 146 码已脱敏、inventory/quality 报告、清洗闸门脚本、903001 取证草案）。
- **Python MVP 参考实现**（规则引擎/状态机/Outbox/38 测试的同构翻译源）：在 **webonne/MetaClaw** 仓库
  `zhinengpaizhang-dev` 分支的 `metaclaw_troubleshooting/` + `tests/`。本地克隆已剔除，远端仍在、只读参考。
- **安全**：源表《故障与措施》xlsx **含真实 Bearer/JWT token、内网 IP、人名，从未入库**（在用户本地）。
  `l0/sop_kb.json` 已脱敏（Bearer/JWT→`<BEARER_TOKEN>`，查询/JSON token→`<TOKEN>`，IP/人名保留）。
  若把源表纳入版本管理，务必先脱敏 token；webonne/MetaClaw 的旧 Git 历史快照可能保留修复前 token，
  如确认属有效凭证应立即轮换；未经明确授权不擅自改写 Git 历史。
- **纪律**：当前本地分支 `intelligent-troubleshooting-design`（原 PR 分支已合并并删除）；以用户当前明确选择的分支为准；
  不擅自开 PR；改 RFC 保持 § 编号连续；沿用仓库现有提交说明约定；不冒用未参与本轮工作的
  Co-Authored-By 身份。
- **方法论 skills**：已迁至本仓库 `.claude/skills/`（矛盾分析/集中兵力/持久战/群众路线/批评与自我批评等，
  `/<name>` 调用）。
