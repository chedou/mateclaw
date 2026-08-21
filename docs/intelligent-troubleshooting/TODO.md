# 实施台账 · IT 智能排障系统

> 更新时间：2026-08-21（通用正式调查与场景级授权解耦检查点）
>
> 唯一现行产品事实：`recording-product-baseline.md`
>
> **开放调查兜底 P0**：`open-discovery-p0-checklist.md` —— 夜间未知告警走受限 miss-path 的启用清单与就绪 API。
>
> **投产清单：`production-readiness.md`** —— 第一条真实报障进来之前必须为真的事。
> **本文件定位**：保留实现决策、验证证据和历史待办，**不再作为活跃排期入口**；当前优先级只看
> `production-readiness.md` 和下方“待办速览”，避免从历史段落里重新捞任务。
>
> 唯一现行架构：`rfcs/intelligent-troubleshooting-architecture-v4.md`
>
> 架构评审：`architecture-review-v4.md`，结论 **APPROVED FOR P1 IMPLEMENTATION**
>
> 第一性原理评价与修订：`architecture-critique-v4.md`（用户已认可，现行为 **v4.5** / 蓝图 v0.19）
>
> 已选定的投影合同：`projection-contracts.md`（服务经理 + 开发两个受众；企微独立 UI 投影原型暂缓，通道 P3 T9 与 T10 纯文本闭环已落地）
>
> **通道复用（D17）**：企微/飞书一律扩平台现有 `ChannelAdapter`；普通消息走
> `ChannelMessageRouter` pre-route，模板卡片事件才走 `CardKind`，不新建入站——
> 平台自带 `vip.mate.channel.wecom`，详见 v4 §7.4。

---

## 当前推广检查点（2026-08-13）

- [x] 通用正式 `OPEN_DISCOVERY` 已与 D20 解耦：未知场景只依赖结构化系统/服务、当前试点版本、
  T7 批次和 Guance owner 验收；只运行有界只读规划器，不回退开放式 Agent。
- [x] 通用正式调查在真源 I/O 后重新校验试点与验收身份，并在 V220 审计冻结
  pilot/acceptance/binding 指纹；旧审计保持空值，不回填。
- [x] 诊断完成后保留会话上下文；四类追问只读安全投影。补充材料由 V221 追加
  不含原文/内容指纹的不可变待验证回执，原 Diagnosis 不覆盖；只有“结束排障”退出。
- [ ] 运行态仍需完成 T7 `20 / 20`、保存真实试点范围、执行当前 owner 验收并部署 V221。
  这些完成前，代码会按设计拒绝正式调查；D20 只作为后续场景级增强任务继续推进。

- 已打通“粘贴原始告警 → 识别明确错误码 → 精确匹配唯一已审核排障方法 → 只读取证 → 对话返回原因”。
  真实登录态下用 `csdp-wechat / 904003` 告警生成演练单
  `diag-156cfe707066424cad311e7d8c6b67aa`，冻结已审核 `csdp:904003` Playbook v2，观测云只读证据返回
  `LOCATED / HIGH`，候选定位为“ITGW 内容安全策略拦截请求”。这仍是演练验证，不计入 T7 正式目标。
- 已补齐日常用户的第一条真实入口：`排障工作台 → 第一次使用？ → 开始演练 → 粘贴告警`，
  最终仍复用同一个 Incident Intake 与 Diagnosis 主流程，没有新建第二套建单 API。
- 已用 `CSDP / csdp-wechat / 904003` 完成一次真实 Guance 只读演练：冻结已审核 Playbook v2，
  三次取证正常返回，形成待人工复核的候选定位；该记录是演练，不计入正式 T7 目标。
- 详情已把“人工复核、平台外处置、结果登记”拆成三个大白话动作，并按当前权限提示下一步；
  MateClaw 仍不执行修复、放行、回滚等生产写操作。
- 汇报 HTML 已增加受控试点路径：先固定二线使用者、三线复核人和系统/Guance owner，再按周复盘
  真实告警、人的采纳耗时与最终处置结果。当前正式 Owner 录制仍为 **`0 / 20`**，不得宣传为已投产。
- 已关闭排障单现可从详情直接进入同一个“诊断效果评估”工作区；页面按“选定排障单 → 保存脱敏
  真源样本 → 登记真实处置结果 → 冻结人工标准答案与耗时”四步引导，不新建第二套台账。
- 人工基线已从前端表单贯通到不可变样本：时间戳实测与处置人估算分开统计；Recorded Replay 和
  fixture 只参与“准不准”回归，不能登记人工耗时，也不会混入真实效果对照。当前仍没有正式效果
  样本，T7 Owner 录制仍为 **`0 / 20`**。
- “诊断效果评估”新增试点接力队列：最近最多读取 100 张排障单，但只纳入建单时已冻结到当前试点版本的正式单，并按持久化事实给出“待登记
  结果、待采集真源样本、待填人工标准答案、待跑影子基线、可进入周复盘”等唯一下一步；点击仍回到
  同一张 Diagnosis。演练、Recorded Replay 和 fixture 被排除，不会用队列数量冒充真实效果或 T7。
- 试点范围和三类负责人现在可在同一评估工作区配置：范围是精确的 `system / service`，二线闭环人、
  三线复核人和数据取证人必须是 3 名不同的当前 Workspace 成员。每次保存都新增不可变版本，不覆盖旧声明。
  未配置、计划停用、人员失效时队列明确停用，不再把最近 100 张全量正式单冒充试点范围。
- 排障列表顶部现提供团队统一起点：按“固定范围与人 → 完成正式排障 → 补齐效果证据”显示当前负责人和
  唯一下一动作；所有查看者可见，配置和评估写入仍只允许管理员。
- 统一入口的“配置试点”现在会一键进入并展开设置，不再要求管理员进入评估页后再点一次。页面已实测当前
  Workspace 只有 **`1 / 3`** 名成员；补齐成员与角色是保存试点声明的前置条件，可与 Owner 登记真实查法
  并行推进。本轮没有新增人员或保存计划。
- 成员补齐接力已闭环：只有具备 `manage:settings` 的账号可以进入成员管理；成员页保留智能排障试点来源，
  显示当前总人数、可推进排障人数和可维护评估人数，并能安全返回仍展开的试点设置。其他排障管理员只会
  看到联系 Workspace 管理员的提示，不会被带到无权页面。
- 试点就绪现在校验“人能不能完成职责”，不再只数人头：二线闭环人至少为 `member`，三线复核人和数据取证
  负责人至少为 `admin`；前端按同一最低角色禁用不合格选项，服务端保存时再次拒绝。人员被降权后，既有计划
  会投影为不可继续，避免队列把下一步交给一个实际无权操作的人。
- Workspace 成员接口会明确返回关联账号是否仍然可用；停用、已删除或旧接口未提供状态的成员不会被计入
  “3 + 2”门槛，也不能被选为试点负责人，避免页面先报就绪、保存时才失败。
- 成员页会把重叠的人数与角色门槛拆成可直接执行的最短步骤，例如当前工作区明确提示“新增 1 名管理员、
  新增 1 名二线成员”；对应按钮会预选正确角色，避免连续按默认成员添加后仍卡在管理员不足。
- 添加成员先明确选择“加入已有账号”或“新建账号并加入”。已有账号模式不接收密码、找不到时也不会
  隐式创建；只有全局管理员主动选择新建账号并填写初始密码时才会创建，账号与成员写入处于同一事务，
  降低推广时重复账号、误改密码和孤儿账号风险。
- 试点设置会从最近最多 100 张排障单中，按真实、非演练 Diagnosis 汇总可直接保存的稳定 `system / service` 范围，
  显示每个范围已有多少张正式单；点击只填入表单，仍需管理员明确保存新版本。演练记录不参与候选，手工录入
  仍保留，因此平台既不猜系统标识，也不会因为历史记录不足而卡死配置。
- 试点范围候选和试点效果分母已经彻底分开：历史正式单只能帮助管理员选择 `system / service`；V202 为新建
  Diagnosis 冻结当时有效的 `pilotPlanVersion`，旧记录保持 `NULL`，不回填、不追溯。接力队列与列表提示只消费
  当前计划版本，因此修改范围会开启新批次，不会把历史单或上一批次突然算成新试点成绩。
- Owner 的“标准查登记”不再把 15 个字段平铺成一张技术表。每条重点故障按“确认这是什么故障 → 确认在
  观测云怎么查 → 确认平台怎么判断”三步显示独立进度，并可直接跳到下一条未完成。字段、校验、导入导出和
  `PREPARED_NOT_EXECUTABLE` 边界均未放宽；进度按正式字段规则计算，非法值或跨条目重复查法仍留在待修正
  循环。当前仍是 **`0 / 20`**，必须由 Owner 使用真实告警逐条核实。
- 展开的试点设置顶部现在直接列出两件可并行准备的事：“补齐试点成员”和“登记真实查法”。两张卡分别读取
  当前成员门槛和固定的 Owner 准备边界，并提供安全返回入口；不会把 Owner 草稿数、历史单或演练记录投影成
  正式 T7 完成数，也不会自动新增成员、保存试点计划或写入查询事实。
- [x] 进度投影和最终规范化共用同一份 15 字段目录（section / 唯一性 / 查询身份 / 校验函数只声明一次）。
- **下一步未完成项**：工作区管理员先准备 3 名能操作排障的真实成员，其中至少 2 名为管理员或所有者，再从该统一入口保存首个真实试点声明；随后用第一张已关闭的真实、非演练 Diagnosis
  按接力队列完成 Guance 样本、人工标准答案、可追溯人工基线与影子运行；随后按同一口径连续积累，
  不用一次技术跑通替代效果证明。

### 无错误码告警竖线补完（2026-08-14）

`96644552` 让症状路由能找到已审核 Playbook，但**接缝没通**：路由与确定性引擎各自有测试，
中间那道"没有错误码就拒绝"的守卫无人覆盖，于是监控告警走到自己的 Playbook 面前被引擎挡回。
本轮补完三处，`csdp:scenario:url_slow_request` 现可一轮出结论：

- `IncidentContext.withResolvedRoute` 由服务端把命中的 `scenario:<key>` 盖到无错误码的告警上，
  确定性诊断因此仍然指名它是被哪条精确路由判定的；已上报的错误码不可被覆盖。
- 晋升时不再丢 `symptomTriggers`。三处 `SopEntry` 构造调用曾使用 15 参兼容构造器静默丢弃该字段，
  结果是审阅者批准了一份声称回答「URL慢请求」的合同，而生效版本什么都不回答。
- 无错误码时按 `service` 在已生效 Playbook 唯一反查 `system`，歧义 fail closed；
  reducer 相应放宽为"有 service 即可接受服务端反查结果"。
- 回归测试补在**接缝**上，不是各自一侧：`anAlertRoutedBySymptomReachesTheDeterministicEngineNamingItsRoute`
  与 `promotionKeepsTheSymptomsTheApprovedScenarioClaims`。
- 端到端脚本 `scripts/troubleshooting-url-slow-request-demo.sh`（配 `.env.demo.local`）自动完成
  注册 → 回放证明 → 审阅 → 批准，再粘原文核验六道闸门。
- **边界**：证据来自 2026-08-06 真实数据的**脱敏回放**，`fixtureMode=true`，不是观测云真源。
  切真源仍需 D20 场景维度授权与 T7 owner 验收，本轮不构成 T7/T8 通过。

通道/对话业务摘要已改为：先说根因和对照数字，再列每一步由谁做；不再把
`LOCATED · MEDIUM` 枚举名和「intake 只保存文本影响描述」这种内部存储说明抛给服务经理。
`BusinessSummary` 新增可空 `rootCause` / `keyEvidence`；弃权不得命名因。

### 4 位业务码 1009（2026-08-14）

今天 13:06 的告警 `异常：客户-搜索用户名超限制【1009】` 原先两处独立缺口：解析器只认
5 位以上且必须带「失败|错误」，知识库也没有 `csdp:1009`。本轮两处都补上：

- 解析器收下 4 位括号码，触发词加上 `超限制|超时|拦截|限流|拒绝`。仍拒绝
  `异常：下游返回用户ID【123456】` 这种没有失败/限制措辞的数字。CSDP 知识目录里已有
  `1004`/`1008`，5 位下限是抄 903001 形状时的失误。
- 已审核查法走**录制回放**，不是观测云。失败计数取自告警「数量：4」，对照 4/4 vs 0/4
  是判据形状夹具。`csdp-wechat` 的真源 `log_search` 仍绑 904003 合同，打开观测云会
  串证。Playbook `manual-csdp-search-username-limit-1009-v1` / selector `csdp:1009`。
- 端到端脚本 `scripts/troubleshooting-csdp-1009-demo.sh`。
- **边界**：`fixtureMode=true`，不构成 T7/T8 通过。

---

## 待办速览（2026-08-13）

按"挡不挡住别人"排序，不按工作量。完整条目见对应小节。

| # | 事项 | 卡在什么上 | 位置 |
|---|---|---|---|
| 1 | **填满 20–30 条录制目标** | **唯一的关键路径，且离线就能做、不用等窗口。** 目录 `guance-recording-targets.json` 目前 `targets: []`，即 `0 / 20`。2026-08-08 跑通的 CTI 持久化真源单例证明场景可运行，但没有进入 server-owned 批次目录，也没有 owner acceptance，不能拿 `1` 冒充 `1 / 20`。要懂 Guance schema 的 owner 填；预检明写「不能自造查询映射」——编出来的映射会一路过闸门，然后在真实故障上给出看起来合理的错误答案 | 投产清单 A1 |
| 2 | **一次内网窗口**：配 Guance 端点 + owner 验收 | 依赖 1。进窗口前先跑 `scripts/troubleshooting-t7-preflight.sh`，七格逐条 | 投产清单 B |
| ~~3~~ | ~~前端两颗回归钉子~~ | **已完成（2026-08-03）**：vitest 接上 `@vitejs/plugin-vue`，仓库第一次能真正渲染组件来测。`cited` 三态各自渲染成不同的话（把 `null` 并进 `false` 验证过会红），读不到时只说读不到、不猜；另一条钉住 provenance 面板确实被父组件 import 并放进模板、父组件也确实挂在正式工作台上 | 前端 |
| 4 | 梯子的上一级：真实案例 → 结论规则可被证明 | **2026-08-03 现场核对后重新定性**：线上已有 12 条结案候选，**全部落在 `csdp:903001`——一条已有已审核 Playbook 的 selector 上**。它们不是在提议新知识，是在佐证既有知识。原先那句 `POSITIVE_REPLAY_REQUIRED` 指错了对象（候选身上没有可回放的 Playbook，`evidenceIds` 只有 id、没有 signalKind 与 target），已改为 `NO_ROUTEABLE_PLAYBOOK_PROJECTED` 并说明真实用途。**下一步的形态需要拍板**，见下 | §7 |
| 5 | T8 历史样本 20–30 条 + 性能基线 | 依赖 1、2 | §5 T8 |
| 6 | T0.8 剩余 145 条错误码录制种子导入 | 先用 T7 窗口灌 20–30 条真实种子再定后续批次 | §3.5 |
| 7 | T10.5 最终弃读 `RouteMode` | 读取迁移已完成；待 P4 真场景同批产生 `RULE_MATCHED / MODEL_PROPOSED` 后收尾 | §6.5 |
| 8 | P4 场景 Playbook / P5 知识治理 | 依赖 T8 的真实时延与质量数据 | §7 §8 |

**P2.5 生产运行时去 Demo 化（2026-08-06）**：Demo Seeder、Demo 配置、录制模型
响应及其 profile 已从 `src/main` 移入 `src/test`，不再进入生产 Jar。Recorded Replay
仍是正式只读证据适配器；八闸门与学习环 CI 只加载含明确自动配置入口的专用 fixture Jar，继续验证原有 HTTP
合同。这一刀去掉的是自动造数和假模型，不是回放取证能力，也没有放宽任何 fail-closed 闸门。

**P2.6 前端领域边界瘦身（2026-08-06）**：排障类型与 HTTP client 已从全局
`mateclaw-ui/src/api/index.ts` 独立为 `api/troubleshooting.ts`，仍复用同一个认证、Workspace 和错误处理
transport，并从原入口兼容导出；现有调用方不需要改名。正式工作台的紧凑排障队列也已拆为
`DiagnosisQueuePanel.vue`，自行负责筛选、排障单号、状态呈现和移动端布局，主工作台只保留页面编排与
Diagnosis 选择。这一轮没有改路由、请求字段、业务投影或安全判断。

**P2.7 排障入口组件化（2026-08-06）**：历史案例入库、通用事件、会话消息失败和部署拓扑场景
四个入口弹窗已从 `FormalWorkbench.vue` 拆成独立组件，共用表单布局但不复制请求、权限或业务判断。
工作台继续持有表单状态并执行原有 service 调用，子组件只呈现输入和发出提交事件；正式工作台另有挂载
守卫，避免组件存在但入口失联。`FormalWorkbench.vue` 从 1427 行降至约 1180 行，Guance 验收弹窗因
仍与验收会话状态高度耦合，留待下一批单独拆分。

**P2.8 Guance 验收会话边界（2026-08-06）**：T7 两步读链、完整 Evidence Spine、owner 核实清单
及批次状态已抽为 `GuanceValidationDialog.vue`；弹窗只展示服务端安全投影并发出验证命令，不直接持有
HTTP client。`useGuanceValidationDialog.ts` 只管理一次弹窗的规范化请求快照、来源、加载态和结果清空，
真实 Router 调用、Workspace 权限、录制批次门与 T7/T8 fail-closed 判断仍由原 store/API 流程执行。
`FormalWorkbench.vue` 已降到约 1000 行。

**P2.9 前端排障 API 分层（2026-08-06）**：原 `api/troubleshooting.ts` 中 183 个领域类型已移入
不依赖 Axios 的 `troubleshooting-contracts.ts`，HTTP 请求集中到 `troubleshooting-client.ts`；原文件变为
兼容导出入口，因此 `@/api` 和既有相对路径调用方无需迁移。结构守卫会拒绝合同反向依赖 transport，
这轮没有改 endpoint、请求字段、认证、Workspace header 或错误处理。

**P2.4 排障模块瘦身（2026-08-03）**：按「去掉它，系统是不是既更简单、又不更危险」筛。

删掉的（约 1600 行）：

| 东西 | 为什么是腐朽 |
|---|---|
| 体验原型（Vue 1017 行 + 静态镜像 HTML 560 行 + dev 路由） | `projection-contracts.md` 早写好了删除清单，条件是「正式页覆盖所有降级场景」。**核对过条件成立**：`formalProjection.ts` 覆盖四种结论类型，provenance 面板渲染源故障态；选型 2026-07-28 已定，企微那支按 D17 改走通道纯文本。它是纯静态假数据（0 次 API 调用），其副本 `DeveloperEvidencePanel` 已和真面板漂开 **636 行**却仍像权威——没有任何东西会发现它过期 |
| `publicPrototype` 路由标记与其守卫分支 | 删掉原型后没有任何路由再用它，但它仍会对设置了它的路由**直接放行鉴权**。一道被拆掉引信、等着被人捡起来复用的检查，比没有更糟 |
| `DiagnosisStateMachine.executeAction` | 自称「为将来的 controller 预留的兼容接缝」。那个 controller 早就来了（`TroubleshootingController.execute`），而且没用这个接缝——同一个 409、同一个错误码、同一句话存在于两处。**两份同样的措辞会漂**，而这句话陈述的是一条红线 |
| `TroubleshootingPlaybookVersionService.knowledgeEvidenceGradeByRef` | 零调用零测试。置信度封顶改从调用方已持有的冻结版本上直接读成色，更近也更省 |
| `default-sources`、`TroubleshootingDomainEvent` | 见 P2.1 段与更早提交 |

`/execute` 那条红线一点没弱：端点仍答 409，场景冒烟闸门 7 在批准之后仍然核验它，
而且 `Diagnosis` 契约本身就拒绝 `writeExecutionEnabled`——删掉端点也开不了执行。

**查了但刻意没删**（免得下一个人再来一遍）：

- `docs/.../console-*.html`（328K）与 `versions/`（5.8M）：`index.html` 把它们编在
  「历史原型 · 归档」里，`console-workbench.html` 已经是一个跳转桩——**有人是刻意留的**。
  那是历史，不是腐朽。
- **两套源验收（V184 Guance / V192 泛化）不是重复**：前者验收「一个系统的观测资产」
  且要求跑完整条 canonical chain（T7 那道闸门），后者验收「一个平台的适配器绑定」，
  服务 Prometheus 与 Elasticsearch。合并等于把 T7 的证明**降级**成「适配器答话了」。
- 生产不可达的类、无引用的前端模块、孤儿表、未被引用的资源文件：**各扫一遍，都是空的**。
  文件级计数一度多报了几个，逐个核实后是嵌套 record、Spring 端点和文件内私有助手。

**P2.3 未标定知识的置信度封顶（2026-08-03）**：投产前的最后一格。证据成色会自己推导
——接上真源那一刻自动变真；但**知识成色不会跟着变**。8 条已审核 Playbook 的阈值是人
手写的，从没被任何一次真实故障检验过。少了封顶，真源接通的第一天系统就会输出
`LOCATED / HIGH`，而服务经理看到 HIGH 会当成系统有把握。

这不是新发明的谨慎，是把已有的一条纪律补齐：未命中路对**模型**的建议早就封顶到
MEDIUM 并附警告；**我们给模型的猜测封了顶，却没给一条从没被检验过的阈值封顶**——
这个不对称没有道理。

- 成色不是 `RECORDED_AGGREGATE` 时，`LOCATED` 封顶 `MEDIUM` + 一条说明理由的 warning。
- 只压 `LOCATED`：`EXCLUDED` 说的是判据没成立，不依赖阈值标定得准不准。
- 成色取自**冻结的那一版**，与判据规则同源；调用方自带权威时按 `UNVERIFIED` 处理（保守侧）。
- 线上核对：`{"concl":"LOCATED","confidence":"MEDIUM","cap":["…从未用真实历史故障标定过…"]}`
- 把封顶去掉验证过会红两条（含 `Vertical903001Test` 整条纵切）。

**这条 warning 什么时候不再出现，才是知识真正成熟的信号。**

### A 方案暂缓（2026-08-03）：机制已确认可复用，第一步已落地，但**刻意停在这里**

> **为什么停。** 它解决的是「投产**之后**知识如何越用越准」，而现在一条真实案例都还
> 没有。继续做那个 `Diagnosis` 1.8 → 1.9 契约升级，就是在一条**没有被任何真实失败
> 检验过的设计分支**上新增实现与表结构——A13 明确禁止。等第一批真实案例跑出来，它的
> 形状会由真实数据决定，而不是由现在的推测决定。已落地的 `matchedRuleId` 是纯收益
> （引擎本来就算了却扔掉），不构成负担。

**好消息：不需要造新的回放机制。** 随包目录里已经有 `recordedEvidenceSeeds` 这条路——
`ManualPlaybookRecordedEvidenceSeed`（selectorKey + exampleCandidate + positiveCase）经
`ManualPlaybookReplaySuiteTemplateFactory` **按判据形状自动生成反例**，正是 D19 说的
「录制聚合正例 + 判据形状生成反例」。所以 A 方案 = 让一次已结案调查产出一份 seed，
而不是新建一套并行的回放设施（A9）。

映射几乎是一一对应的：

| ReplayCase 需要 | 已结案诊断提供 |
|---|---|
| `exampleCandidate` | 该 selector 的冻结 Playbook |
| `positiveCase.evidence` | 诊断实际取到的 `EvidenceResult`（queryId / status / observed） |
| `expectedDisposition` | `MATCHED`（结论成立且人已确认、恢复已核实） |
| `expectedRuleId` | **此前没有——引擎算出来就扔了** |

**已完成（第一步）**：`PlaybookEvidenceAssessment` 记下 `matchedRuleId`。它严格与
「这条规则确实产出了这条结论」对齐：只有 `LOCATED` 时才有值，缺必需证据降级、Playbook
仍是草案、弃权规则匹配，三种情况一律不留名；合同本身也拒绝在非 LOCATED 上带规则 id。
不这么钉，事后只能拿 rootCause 文本反查，而 rootCause 并不保证唯一——那是猜。

**下一步需要你知道的一件事**：`matchedRuleId` 必须**持久化**才能在结案后取用，也就是
要给 `Diagnosis` 加一个可空字段并把合同从 1.8 升到 1.9。那是持久化聚合 + 四个工厂 +
严格校验器，改动面比一张新表大。老诊断没有这个字段，因而无法回溯成为 seed——与
candidate v1/v2 的处理一致，是可接受的诚实代价。

### 原始待拍板记录：结案候选到底该变成什么（2026-08-03）

现场核对推翻了一个我先前的假设。原以为结案候选是「一次已解决故障提议的新知识」，
所以缺的是把它投影成 Playbook。实际看下来：**12 条候选全部落在 `csdp:903001`，
而那条 selector 早就有已审核 Playbook。** 它们记录的是「既有 Playbook 跑了、
给出结论、人确认结论对且恢复已核实」。

于是它们的价值不是产出新 Playbook，而是**给既有 Playbook 提供一份答案由世界给出、
而非作者自撰的案例**——正是非循环的回放材料。随包回放目录固定 8 条 selector 的问题，
本来就该由这个来解。

两种形态，代价与含义不同，需要拍板：

- **A. 结案案例注册为该 selector 的回放用例**（推荐）。目录从 classpath 固定变成
  「随包 + 工作区累积」。收益直接：真实运行会自己长出回放材料，手写 Playbook 第一次
  能被真实历史故障检验，成色也才配从 `AUTHORED_FIXTURE` 往上走。代价：新表、目录语义
  变更，且必须守住原有不变量——**案例与期望答案都只能由服务端从已持久化的诊断推导，
  调用方一个字也不能提供**，否则证明就循环了。
- **B. 只做展示**：在评审面板上把「这条 Playbook 已被 N 次真实结案佐证」显示出来，
  不进入任何闸门。代价极小，但它不改变任何判定，也解不开回放目录那道坎。

我倾向 A，但它动的是 T7 相邻的证明链，且是新表，所以先停在这里等一句话。

### 本轮（2026-08-03）的位置

起点是一个问题：**离真正落地推广还差什么**。做法不是读代码猜，而是起一台服务、用一个
全新系统（`ACME`）从零走一遍。结论是此前**没有任何新租户能走通第一步**，四道坎依次
拆掉（P2.0 / P2.1 / P2.2 + 一处接口对，详见下方各段）：

```
注册 Playbook → 批准 → 开案 → 跑取证计划 → 拿到证据
   ✅P2.0      ✅P2.0  ✅已有   ✅已有        ⛔ 卡在 T7
```

最后一格**不是代码问题**：路由已经能配了（P2.1），但需要一个装着该租户数据的真实源，
那条路通向 T7 内网窗口。

一句必须留下的提醒：这一轮解掉的全是**「不改发布物就走不通」**类型的障碍，
**不等于**新租户马上就有可信结论。三条轴始终分开——
**能路由 ≠ 会下结论 ≠ 已被证明**。

**P2.0 新租户第一条路已打通（2026-08-03）**：按第一性原理核对「离落地推广还差什么」，
在一个全新系统（`ACME`）上从零走了一遍，结论是**此前没有任何新租户能走通第一步**。
两道拒绝各自都对，合取起来没有路——而且都没说出下一步。逐条：

1. **手写知识根本无法被批准。** `ManualPlaybookReplaySuiteCatalog.evidenceGrade`
   只在候选与随包示例**逐字节相同**时才返回成色，促成物读取器把「没有成色」当成
   「没有可路由的 artifact」而拒绝。于是把示例改一个字：评审面板显示
   `ELIGIBLE_FOR_APPROVAL`，点批准得到 409。**闸门指错了对象**——指纹比对是
   「什么成色」的正确答案，是「能不能批准」的错误答案。已拆出 `promotionGrade`：
   逐字节相同 → 继承套件成色；有套件、阈值自撰 → `AUTHORED_FIXTURE`；无套件 →
   `UNVERIFIED`。能不能批准交回评审资格那道闸门，它会把原因写给作者看。
2. **新 selector 永远拿不到回放套件。** 随包目录是 classpath 上固定的 8 条
   CSDP selector，新系统、老系统的新场景都命中不了，`REPLAY_SUITE_UNAVAILABLE`
   永远挂着。回放证明的是「答案对不对」；**规则全部 `abstained` 的 Playbook 没有
   答案**，引擎里最多落到 `INSUFFICIENT_EVIDENCE` / `EXCLUDED`，永远出不了
   `LOCATED`。这类只取证不下结论的 Playbook 现在可以批准，成色记 `UNVERIFIED`
   （A13）。会下结论的那条仍照拦不误——放开的只有「不下结论」。这是那把梯子此前
   缺掉的最下面一级：先只取证 → 跑出真实案例 → 再拿案例去证明结论规则。
3. **两条门的 409 都没说下一步。** 场景路只报被拒的 selector，现在同时列出该系统
   已批准的场景（打错字和从没接过，下一步完全不同）；错误码路把确定性未命中的原因
   算出来传进 Agent，又被 Agent 自己的「miss-path Agent is disabled」整个覆盖掉，
   现在两件事一起报，并给出两条出路。评审拒绝新增 `KnowledgeReviewBlockerAdvice`，
   把资格代码翻成一句可执行的下一步——只加话，不改任何判定。

现场验证（`dev,troubleshooting-demo`，全新系统 `ACME`）：注册 → 资格
`ELIGIBLE_FOR_APPROVAL` → 批准得到 `approved / operational / UNVERIFIED` →
同一条报障产出 `NEEDS_INVESTIGATION + INSUFFICIENT_EVIDENCE` 的 Diagnosis。
三条轴仍然分开：**能路由 ≠ 会下结论 ≠ 已被证明**。758 个测试与四条冒烟全绿。

**同批修掉的第二件（2026-08-03）**：`GET /sops` 会用版本行覆盖同一 selector 的注册行，
于是 `sopId` 一个字段装着两个身份空间的值——候选行装人工来源记录号，已生效行装版本表的
`playbook-*`；而 `by-id` 只查注册表。**列表把 id 发出去，详情接口不认**，而且不认的
恰好是最重要的那些行（operational 的）。`by-id` 现在两种身份都认，两次查询都锁在同一
workspace 内。测试写成「遍历列表拿到的每一个 id 都要能解开」而不是钉死某一种——只钉
一种，另一种坏掉时测试照样绿；把修复注掉验证过它确实变红。

更正一句此前的说法：这条链路当前的 UI 并没有踩到——知识库列表页走的是
`getSop(system, errorCode)`，评审页拿到的一直是注册表 id。坏的是**接口对**本身，
任何把这两个端点配起来用的客户端都会中招。

**P2.1 取证路由不再是编译期事实（2026-08-03）**：P2.0 补上的那级梯子站上去之后是
悬空的——新租户能注册、能批准、能开案、能跑取证计划，然后每条证据都回
`MISSING/router:unconfigured`。原因是 `mateclaw.troubleshooting.evidence.routes`
（system → signalKind → 有序源名）只存在于 `application.yml`：**接一个系统要改发布物
里的文件并重新发版**。这和随包回放目录是同一种病，P2.0 只治了知识那一半。

- V193 加 `mate_troubleshooting_evidence_route`，`PUT/GET/DELETE
  /api/v1/troubleshooting/evidence/routes`（声明要 admin，改动必须带 actor 与原因）。
- **workspace 声明优先，YAML 回落**。没人声明过时行为与本特性引入前完全一致。
- 顺带收窄了一处跨租户问题：YAML 那张表**只按 system 名字索引**，任何 workspace 只要
  把系统命名成 `CSDP` 就继承了 CSDP 的路由、打到 CSDP 的观测端点上。加 workspace 维度
  是在收窄，不是放宽。
- 租户只能在**已启用的源之间做选择**：端点与凭据仍然只在运维配置的适配器里。平台名
  与 signal 词表都逐个校验，拒绝时把「有哪些」说出来。
- 「声明了但列表为空」与「没声明过」是两个答案：前者是租户明说这一格不取证，不回落；
  后者才回落。读成同一件事，就会出现「说了不要还是去问了生产观测系统」。
- `router:unconfigured` 现在报出 system、signal 和下一步，并区分「你还没配路由」与
  「这台部署根本没启用任何源」。

现场验证：声明后取证从 `router:unconfigured` 变成 `router:unavailable`（适配器真的被
调用了，只是 recorded-replay 没有 ACME 的录制）；撤回声明回落；声明为空不回落。
773 个测试、四条冒烟全绿。把 workspace 条件改死成常量，跨租户那条用例确实变红。

**注意别过度解读**：这解掉的是「不改发布物就配不了路由」，**不是**「新租户马上就有真
证据」。真数据仍要一个装着他们数据的源，那条路通向 T7。

**P2.2 `EVIDENCE_IS_FIXTURE` 已删除（2026-08-03）**：`EvidenceProvenance` 早已能从证据
自身推导成色，但 Agent 路、synthesis 路、场景开案三处仍在读那个编译期常量——**同一个
系统里并存两套口径**，而且翻那一个字符会让每条诊断同时改口。三处都改成推导：

- 场景开案：此刻一条证据都没取，`fixtureMode(List.of())` 必然为 true；写成推导是因为
  它会在证据到达时被 `Diagnosis.evidenceRecorded` 按真实来源重算，常量做不到。
- Agent 兜底路：用 `(collected, supplied)` 双参重载。会话快照里既有 Agent 自己取的，
  也有**调用方自带的**——后者的 `source` 是它自己写上去的，写成 "guance" 就能让整条
  诊断自称真源。
- synthesis 预览：按 search/trace/contrast 三条证据推导。这条 lane 目前硬限定在
  recorded-replay 上，答案不变；但有人放宽 `FIXTURE_ONLY_SOURCES` 时它会跟着走。

**今天行为没有任何变化**（三处原本都是 true，推导出来也都是 true）。变的是能力：它们
现在跟着事实走。

杠杆没有消失，只是搬了家：往 `EvidenceProvenance.REAL_SOURCE_PREFIXES` 里加一个名字，
就等于宣布那个源取回来的东西是真的。原先钉在常量上的三条前置清单（T7 owner 验收、
真源真的能取到、**接真源不会让手写阈值变可信**）整份搬到了钉住这份白名单的测试里——
守卫必须跟着杠杆走。把 `elasticsearch` 从名单里去掉验证过它确实变红。

**P1.9 全链路已打通（2026-08-02）**：现象 lane 从一句话走到了可确认的结论，
七道闸门全绿并已进 CI。这一轮补掉的是 P1.8 记录的那个缺口——
**但当时对缺口的判断是错的，一并更正**：`Diagnosis` 的形状从来不缺
（`DETERMINISTIC + SCENARIO_PLAYBOOK + EXPLICIT` 一直合法），
缺的是入口，以及入口之后没有任何东西去跑取证计划。四段：

1. `Diagnosis.evidenceRecorded` + `recordScenarioEvidence`：证据到达转移。
   命中清 `abstained`；反证走 `EXCLUDED`（排除也是结论，也推进）；不足则保持弃权。
   顺带修掉一条 A1 违规——引用清单原本会把 `MISSING` 的取证也列为依据。
2. 拓扑归位：运行表保留（工具运行的审计记录本就更宽），**补上写回**；
   run 与被重新裁决的聚合同一把行锁、同一个事务提交。
3. `ScenarioEvidenceRunService` + `POST /diagnoses/{id}/evidence-runs`：
   任意场景都能跑自己的取证计划，按**冻结版本**求值。此前拓扑是唯一能走完的场景。
4. `PlaybookEvidenceCollector`：把 intake 里私有的逐条补取证抽出来，两条 lane 共用（A9）。

**P1.8（2026-08-01）**：第一个场景的**链头**（补问 → 补齐 → 调查）已走通，
北极星第一段第一次从机器里产生出真实数值。

**P1.7 已打通（2026-08-01）**：一个案子从报障走到关闭，十道闸门全绿。
其中闸门 6 第一次在 HTTP 边界上走通了「批准≠执行」的**肯定**半边——
此前只有 `POST /execute` 的 409 那半边被演示过。详见 §3.7。

**P1.6 已打通（2026-08-01）**：蓝图 §11.1 唯一点名"必须先通过"的无码路验收案例，
现在默认可跑，九道闸门全绿，已进 CI。学习环第一次在 HTTP 边界上供出了一条可评审的知识。
遗留：T0.8 的 145 条批量导入应当排在它之后重新评估——学习环通了，其中一部分可能不需要手写。

**T0.9 已按顺序完成**：D19 让"知识规模化"成了可能，而规模化的第一个副作用是
**真知识和编的知识开始混在一起**。V190 已把来源等级冻结到 Playbook 版本，
并投影到注册表与开发证据视图。服务端目录中精确的 `IM1010` 候选属于
`RECORDED_AGGREGATE`、精确的 `903001` 候选属于 `AUTHORED_FIXTURE`；但目录候选不等于
当前 workspace 已批准版本。等级要求候选内容指纹精确匹配服务端冻结示例；固定清单按冻结的
146 个 selector 成员统计。2026-08-02 对本地 V190 真实重启后，注册表如实显示 `0 / 146`：
唯一 legacy 903001 内容不匹配冻结示例，保持 `UNVERIFIED`，没有为凑正例数误抬权威。
这道标记已经立在 T0.8 批量导入之前。

**「高置信错误为 0」的测量口径已拍板并实现**：T8 基线不接收或映射模型自报置信度；服务端根据
真 Guance、双非 fixture、`FULL_SPINE_OBSERVED`、有效草案和引用完整性派生
`HIGH / MEDIUM / NOT_ASSESSED`，再由冻结人工参考解独立判定正确性。
门禁必须显式给出最小 HIGH 分母，因此 `0 / 0` 不能通过。详见
`system-confidence-contract.md`；真实阈值仍必须等待 20–30 条样本，未提前标定。

**T7 的目标应当改写**：不是"跑通一次验证"，而是**一次窗口灌 20–30 条录制种子**。
D19 已经让这件事成为可能——一条种子只要一份聚合正例，排除/弃权例由服务端生成，
不需要人写三套用例。这是 v4.5 真正解锁的东西，别浪费在单次验证上。

---

## 排障过程透明化（2026-08-02）

**「详情页看不出用了什么能力」查下来，问题不在记录，在装配和挂载。**

- 判定链（证据 → 判据 → 规则，区分 SATISFIED / EXCLUDED / **UNEVALUATED**，
  按冻结版本重建、对不上 fail closed）后端与 `DerivationChain.vue` **都早就写好了**，
  但**这个组件没有被任何地方挂载**——建好了，页面从来没渲染过。
- **排障链路一次都没经过 skills / tools 注册表**（grep 全空）。
  所以「用了什么 skills」有确定答案：零。这不是记录缺失，是从没被说出来的事实。
- Playbook 的来源（手写夹具 vs 真实归纳）在 `ApprovedPlaybookVersion.sourceOrigin`，
  详情页不读——而 T0.9 问的正是这件事。现在在**用它下结论的地方**直接标出来。

已落地：

- [x] `InvestigationProvenance` + `GET /diagnoses/{id}/provenance`：知识（含来源与
      冻结版本）、每条取证实际问了哪个适配器、是否有模型参与，以及一份 **abstentions**。
      **契约层面拒绝一份没有否定句的 provenance**——这个产品的安全论证整个由否定句
      构成（零模型、无生产写执行器、只读、fixture 而非真源），只列参与者的页面
      会让读者自己补完剩下的，而他补出来的一定比真相更宽容。
- [x] `cited` 改为可空。错误码路径根本不填引用清单（那是模型路径的要求），
      恒 false 会被读成「这条证据没有支撑结论」。**「本路径不维护引用清单」
      和「没有支撑结论」是两回事**，与 EXCLUDED / UNEVALUATED 同一条纪律。
      测试先抓到的。
- [x] `InvestigationProvenancePanel.vue` + **把 `DerivationChain.vue` 真正挂上**
      开发证据台侧栏。
- [x] 更正 `DiagnosisDerivation` 一处过时类注释（说按当前 SOP 重算，实际早已改成
      按冻结版本、无冻结版本则 fail closed）。

待办：

- [x] 前端已用组件回归测试钉住 `cited === null` 与 `false` 的不同文案；合并两者会直接变红。
- [x] 正式工作台挂载守卫已覆盖 `DeveloperEvidencePanel → InvestigationProvenancePanel / DerivationChain`，
      避免组件存在但用户永远看不到。

---

## 新增两条可跑通的场景（2026-08-02）

场景从 2 条变成 4 条（含拓扑）。挑选标准不是「再来一个好看的案例」，
而是**补上从没在 HTTP 边界上走出来过的结局**。

| 场景 | service | 结局 | 它证明了什么 |
|---|---|---|---|
| `message_send_failed` | `csdp-session-service` | LOCATED | 原有 |
| **`gateway_timeout`** | `csdp-api-gateway` | **EXCLUDED** | 排除也是结论，且此前只在单测里断言过 |
| **`auth_token_rejected`** | `csdp-auth` | LOCATED | lane 没有被某一个场景特化 |
| `deployment_topology_probe` | `network-path` | 资产工具路径 | 原有 |

**`gateway_timeout` 是这两条里真正值钱的那个。** 它的对照样本显示该特征在成功
样本里一样多（48 vs 52，比值 0.48 < 0.5），判据被反证，候选根因整体 `EXCLUDED`。
没有对照的话，48 次失败命中看起来就像根因——**这正是 D15 对照/反例存在的理由**，
而在此之前它从未被演示过。

- [x] 两条 seed + 录制回放记录 + 接入 demo seeder。
- [x] 冒烟加第 8 格「多场景且结局不同」，**强制其中至少一个是 EXCLUDED**：
      只会产出 LOCATED 的 demo 会夸大能力，而多数真实排查是一段段划掉可能性。

**路由键约束**（踩到才知道）：场景的 `errorCode` 为 null，回放记录键是
`(system, errorCode, service, requestId, signalKind)`，所以**新场景必须换 service**，
否则和已有场景撞键。

### 被现有不变量拦下来的一件事，记在这里

原本想让 `auth_token_rejected` **故意缺一条必需证据**，好在 HTTP 边界上演示
A6「不完整比编造更好」。被 `everyEvidenceRequestIsAnswerableByTheFixture` 拦住了。

**那条不变量是对的**——它抓的是「有人忘了放录制记录」。我的意图也是对的。
它分不出「忘了」和「故意」。把它放宽成「允许缺」就毁掉了它。

- [ ] 正确解法是让**故意留缺成为显式声明**：seed 里声明哪条请求故意不可答，
      不变量改为「所有未被回答的请求都必须事先声明过」。这比现在**更严**：
      忘放记录照样被抓，未声明的缺口也被抓，而声明过的缺口可以存在并且有据可查。
      要动 schema + 目录读取 + 不变量三处；半途而废（只放宽不声明）比不做更糟，
      所以这次没做，auth 场景先补全成可答。

---

## 投入使用前最要紧的一格：证据是真的 ≠ 知识是真的（2026-08-02）

**`fixtureMode` 是一个全局编译期常量 `TroubleshootingSafetyPolicy.EVIDENCE_IS_FIXTURE`，
含义只是「证据是夹具」。** T7 落地那天有人把它翻成 `false`，**每一条**诊断都会
变成「真源」——包括那些由手写 Playbook 路由、判据阈值从没用真实历史故障标定过的。

两个独立的轴此前被压成了一个布尔值，读起来像同一件事：

| 轴 | 现在由什么表达 | 翻转 `EVIDENCE_IS_FIXTURE` 会不会改变它 |
|---|---|---|
| 证据成色（真源 / 回放） | `fixtureMode` | **会** |
| 知识成色（归纳 / 手写） | 冻结版本的 `sourceOrigin` | **不会，也不应该** |

这在投入使用时会直接伤人：拿着没人校准过的阈值，对真实故障给出「已定位」，
而页面上什么都不说。

- [x] provenance 增加「真实数据校准」一条否定句，**不看 `fixtureMode`**，
      只看冻结 Playbook 的 `sourceOrigin` 是否手写。读不到冻结版本时挂
      「知识来源判定」——判不出来就说判不出来，沉默会被读成「已校准」。
- [x] 测试用 `fixtureMode=false` 构造，也就是**翻转之后的世界**，钉住这条警告仍在。
- [x] `TroubleshootingSafetyPolicyTest`：此前**没有任何东西钉住那个常量**，
      翻了没人会知道。加了守卫，并把三条前置清单写进它的 Javadoc——
      目的不是让它永远为 true，是让翻转成为一次必须动手改测试、
      因而必须先读清单的动作。

### 顺带查实的一件事

`manual-playbook-replay-suites.json` 里的键名 **`recordedEvidenceSeeds` 在暗示
这些数字是录制来的**，而其中 `gateway_timeout`、`auth_token_rejected`、
`message_send_failed` 的数字是手写的（seeder 把它们标成 `MANUAL`，数据是老实的，
容器名不老实）。

- [ ] 考虑把键名改成中性的（例如 `playbookSeeds`），或在契约里强制每条 seed
      显式声明 `numbersOrigin`。**改键名要动契约与迁移，本轮没做**；
      现在靠 `sourceOrigin` 与上面那条否定句兜住，读者不会被容器名误导。

---

## 第三个证据适配器：Prometheus（2026-08-02）

**为什么是它。** Guance 的真源验收卡在内网窗口上，而 Prometheus 是企业 IT 里最
普遍的指标源。它给的是一条**不依赖那扇窗口**的真实证据通路：手里有 Prometheus
的环境可以先拿到真数据，不必等 T7。兼容 VictoriaMetrics / Thanos / Mimir。

- [x] 传输层加只读 `get`（`EvidenceHttpTransport`）。native-curl 的硬化选择
      （config-file、`escapeConfig`、`-q` 优先、有界读取）一律没动，只把 method
      参数化、`data-binary` 变成条件项。GET 传 `null` body 而不是空串——
      只读调用不该被告知"要发一个负载"。
- [x] `PrometheusEvidenceAdapter`：只服务 `metric`，只发
      `GET /api/v1/query`，PromQL 来自 binding 配置。
- [x] 7 条测试，**大多数走失败分支**：非 200 / 非 success / 非 JSON / NaN /
      +Inf / 空 series / 多条 series / 网络异常 —— 全部 `MISSING`。
      测试替身在 `postJson` 上直接抛异常，"只读"是被验证的，不是被声称的。

### 契约和测试各改了我一次

1. **`usable()` 原本接受部分字段映射** → health 会报 READY，而每次取证都
   MISSING。canonical 的 `metric` 是一个整包不是一份菜单；现在要求映射覆盖
   全部字段，否则 DEGRADED。**「看起来就绪、实则永远取不到」比诚实的
   DEGRADED 糟得多。**
2. **`reachable` 在契约里是 BOOLEAN，而 Prometheus 对一切都返回数字**（包括
   `up`）。契约挡住了我。现在按声明类型转换，且 **0/1 之外的值判 MISSING**——
   把 0.5 当成 true 就是替观测数据下了一个它没给的判断。类型知识加在
   `CanonicalEvidenceSchema.isBooleanField`，**不在适配器里另抄一份**（A9）。

### 还没做的

- [ ] 把 Prometheus binding 接进 `EvidenceProperties` 与路由配置，
      并在 `/evidence/sources` 与工作台里可见。**现在适配器可用但还没有配置入口**，
      要用得先在代码里构造 Binding。
- [ ] 日志类适配器（Elasticsearch / OpenSearch，服务 `log_search` / `log_count`）。
      形状和这条一样，但 `log_search` 的 canonical 要求 `ps_id`——
      ES 里有没有等价物是**环境相关的**，得先确认再动手，不能先写后凑。

---

## 适配器从 2 个到 4 个，且都接进了配置（2026-08-02）

| 适配器 | 服务信号 | 默认 | 备注 |
|---|---|---|---|
| `guance` | 全部 | 关 | 卡在 T7 内网窗口 |
| `recorded-replay` | 全部 | 关 | 夹具 |
| **`prometheus`** | `metric` | 关 | 兼容 VictoriaMetrics / Thanos / Mimir |
| **`elasticsearch`** | `log_search` | 关 | 兼容 OpenSearch |

**为什么加这两个**：它们是**不依赖内网窗口**的真源通路。手里有 Prometheus 或 ES
的环境可以先拿到真数据，不必等 Guance 验收。

- [x] 两个适配器 + 传输层只读 `get` + 接进 `EvidenceProperties` 与自动装配，
      默认全关。`EvidenceAutoConfigurationTest` 现在点名四个平台——
      **每加一个适配器都必须在那里登记，且默认必须是关的**。
- [x] 各 7 条测试，**大多数走失败分支**。测试替身在用不到的那个 HTTP 动词上
      直接抛异常，「只读」是被验证的不是被声称的。

**ES 适配器里最要紧的一条**：`correlationField`（一次请求在跨服务日志里的串联键）
**没有默认值**。各环境叫法不同（`trace.id` / `traceId` / `x_request_id`…），
**猜错的后果不是取不到，是把两次不相干的请求当成同一次**，而下游的全链路日志包
会照单全收，最后给出一条看起来完整、实则拼接自两次故障的证据链。没配就整个不可用。

### 两条新场景已加上——原因查清了，是我写错

`db_pool_saturated`（`metric`）与 `mq_backlog`（`log_count`）现已加载并跑通。
**拒绝原因不是种子契约不支持这两个信号种类，是我把 `target` 写成了空 `{}`。**
先前那条「种子只支持以 log_search 为锚」的假设是错的，已作废。

查清它的办法本身值得留下：**隔离只报一个代码、不报原因，就是在逼作者去猜，
而猜的过程里最省事的做法是把校验放宽——那正是这道闸门要挡住的事。**
现在 `ManualPlaybookReplaySuiteCatalog` 会把异常消息（脱敏 + 截断 300 字）
一并打出来。

| 场景 | 信号种类 | 结局 |
|---|---|---|
| `message_send_failed` | log_search + trace + contrast | LOCATED |
| `gateway_timeout` | log_search + contrast | **EXCLUDED** |
| `auth_token_rejected` | log_search + contrast | LOCATED |
| **`mq_backlog`** | **`log_count`** | **EXCLUDED** |
| **`db_pool_saturated`** | **`metric`** | LOCATED |
| `deployment_topology_probe` | synthetic_probe（资产工具） | 另一条路 |

冒烟第 8 格已扩到五个场景并强制含 EXCLUDED，实跑全绿。

### 旧记录（已作废，保留以免有人再按它去改契约）

想加 `db_pool_saturated`（`metric`）与 `mq_backlog`（`log_count`），
覆盖场景侧从没用过的两个信号种类。两条都被
`ManualPlaybookReplaySuiteCatalog` 隔离为 `INVALID_RECORDED_EVIDENCE_SEED`。

排除过的：contractVersion / suiteVersion / `routingKey` 与 selectorKey 一致 /
`requiredEvidenceRequestId` 属于候选且 required / positiveCase 是 MATCHED /
Boolean 在安全聚合白名单里 / 换成 `numeric_gte` 判据后依旧被拒。

**未确认的假设**（是假设，不是结论）：recorded-evidence 种子目前只支持以
`log_search` 为锚的场景——D19 的「录制聚合正例 + 生成负例」模型是围绕日志检索
建的，`metric` 与 `log_count` 可能走不通生成器。

- [ ] 查清真实拒绝原因并决定：是扩展种子契约支持非 `log_search` 锚点，
      还是明确写死「场景种子必须以 log_search 为锚」。**在查清之前不要再塞种子**。
      本轮已把那两条连同回放记录一并回退——隔离是 fail-closed（种子根本不加载），
      但把隔离的种子留在树里更糟：JSON 看着有、demo 里没有。

### 还没做

- [x] **验收的地基已就位：`bindingFingerprint()`**（2026-08-02）。
      指纹的价值全在「什么会让它变、什么不会」上，定错了会有两种坏法——
      改了配置还认旧验收（形同虚设），或轮换个凭据就作废（逼人把重新验收
      当成走过场，**那比不验收更糟**）。所以：
      - **放**：端点、索引/PromQL、串联字段、正文字段。验收的是「查这个地方、
        用这些查询」，任何一项变了就不再指向同一件事，必须自动失效。
      - **不放 token 本身**，**但放「是否带鉴权」这个布尔**——轮换凭据查的还是
        同一个地方，而匿名↔Bearer 换掉的是授权路径。
      - 指纹不含凭据，因而可以安全地进日志、进 `/evidence/sources` 的 detail。
      各 3 条测试钉住这三种变与不变。
- [x] `health().detail()` 现在带上指纹，并明写「未验收（本适配器尚无 owner
      验收接缝）」——运维看得见将来那次验收要钉在哪一串上。
- [x] **持久化的 owner 验收已建成**（V192 `mate_troubleshooting_source_acceptance`，
      h2 / mysql / kingbase 三方言）。**泛化而不是抄一份**：一张表按
      `(workspace, platform, binding_fingerprint)` 服务所有适配器。
      `GET/POST /evidence/sources/{platform}/acceptance`，POST 限 owner。
      四条设计决定：
      - **请求体里只有清单**。指纹由适配器算、验证事实由服务端**自己重跑一次
        只读取证**得到、actor 取自鉴权上下文。一旦允许提交方自带其中任何一项，
        验收就退化成一句可以随手写下的声明，而这张表存在的全部意义就是它不是。
      - **服务端取不到证据就拒绝验收**（409）。「我确认过了」+「其实取不到」
        不该产出一条有效记录——那正是最容易被走过场的组合。
      - **五项清单缺一不可**，`@AssertTrue` 在进服务之前就挡掉
        「先签了再说，回头补」。
      - **`STALE` 独立成一档**，且 `acceptedForCurrentBinding()` 只认 `ACCEPTED`。
        它不是「过期」，是「配置在验收之后被改过，那次验收不再指向同一件事」。
        契约层面还堵死了「状态说 ACCEPTED、指纹却对不上」这种组合。
      **失效不需要有人记得去做**：指纹一变旧行自然对不上，没有「记得作废」
      这一步，也就没有忘记作废这种可能。
      实跑四种状态：未配置→BLOCKED、未注册平台→BLOCKED（点名）、
      半份清单→400、全确认但源未配置→409。

- [ ] 把 Guance 的 V184 迁进这张泛化表。**本轮刻意没做**：V184 已经承载真实验收
      记录，迁移它是一件独立的、需要数据搬运方案的事，混在「加一个适配器」里做
      会把两件事的风险绑在一起。在迁移之前两套并存，Guance 仍走它自己的接口。
- [ ] 旧记录（V184 表结构其实是通用的
      （`scope_key` + `binding_fingerprint` + `aggregate_json`），只是名字绑了
      Guance。做法应当是**泛化它而不是给每个适配器抄一份**（A9）：
      加 `platform` 维度、迁移三种方言、owner-only 提交、服务端提交前重跑一次
      只读链路、指纹不匹配即 `STALE`。
      **在它建成之前，两个新适配器的 `verified()` 恒为 false 且无法变 true**——
      这是它们真正可投入使用前的最后一环，不要在页面上另找地方把它标成已验证。
- [ ] 新适配器没有进 `/evidence/readiness`（那个接口目前是 Guance 专用的）。

---

## ⚠️ HEAD 上有一条必现红的检查（2026-08-02 查实，非本轮引入）

`scripts/ci/test-troubleshooting-t7-preflight.sh` 最后一格必现失败：

```
FAIL: did not observe the immutable plan snapshot before preflight completed
```

- **连跑三次全失败**，不是偶发竞态。
- 该断言（脚本第 456–476 行）在后台跑预检的同时轮询
  `${TMPDIR}/mateclaw-t7-preflight.*/plan.json`，要求它存在、字节数与原计划一致、
  权限为 600 —— 这是一道 TOCTOU 守卫：防止计划快照在预检读取之后被换掉。
- 当前 `scripts/troubleshooting-t7-preflight.sh` 没有在那个路径留下该快照。
- **这两个脚本本轮未经我改动**，是并行会话推入的版本；很可能是那边尚未收尾。

- [ ] 由改动方确认：是快照路径/命名对不上，还是快照功能尚未实现完。
      **没有弄清设计意图之前不要盲改**——猜着修一个竞态守卫，比让它明显地红着更糟，
      而最省事的"修法"是把断言删掉，那恰好毁掉这道守卫存在的理由。
      在它变绿之前，T7 预检的 CI 合同不能算通过。

---

## fixtureMode 不再是全局开关（2026-08-03）

**它此前是编译期常量 `EVIDENCE_IS_FIXTURE`：谁翻一下，每一条诊断都同时改口**
——包括同一时刻仍走录制回放的那些；反过来只要它还是 true，一条真从 Prometheus
取到数据的诊断也只能自称夹具。两种错来自同一件事：**把一条诊断的事实，交给了
一个全局状态去回答。**

`EvidenceProvenance.fixtureMode(collected, supplied)` 从证据自己身上读。三条
在实现过程中被测试逼出来的判断：

1. **只列真源，其余一律按夹具。** 我第一版反过来写（列夹具），理由是"漏登记
   真源比较安全"——**推理是反的**：把夹具标成真源是夸大，把真源标成夹具只是
   保守。两种疏忽代价不对称，默认必须落在保守那一侧。
2. **调用方自带的证据不能自证成色。** `source` 是它自己写上去的，写成 `guance`
   就能让整条诊断自称真源。这与验收那条纪律同源——提交方声称的事实一律不接受。
   是 `Vertical903001Test` 抓到的。
3. **混一条夹具进来，整批算夹具。** 读者不会逐条分辨，而"部分真实"最容易被读成
   "真实"。MISSING 不提供成色信息（既不证明夹具也不证明真源）。

场景 lane 的证据是后到的，所以 `Diagnosis.evidenceRecorded` 里一并重算——沿用
建立时的旧值会让一条真实取到的证据永远被标成夹具。

**这条改动的实际意义**：接一个真 Prometheus/ES 并跑通 owner 验收之后，
那条诊断会自己变成 `fixtureMode=false`，**不需要任何人去翻常量**。
demo 档实跑仍是 `fixtureMode=true`（来源 `recorded-replay:*`），符合预期。

- [ ] `TroubleshootingSafetyPolicy.EVIDENCE_IS_FIXTURE` 现在只剩 Agent 与
      synthesis 两处在用，且那两处的证据来源同样可以推导。**清理它是下一步**；
      在清理之前它的守卫测试保留。

---

## 下一步做什么（2026-08-02 交接）

按**能不能动手**排，不按重要性。

### 第一优先：先补可执行目标，再约人和内网窗口

**当前不是只差 owner `ACCEPTED`。** 复审发现，原预检会让任意 20 个 D1 selector
复用同一条硬编码 SendMsg 查询并报绿；这比没有门禁更危险。现已改为只接受运行服务返回的
`t7-guance-recording-target-catalog.v1`。目录项必须冻结 selector、candidate/request 双指纹、
安全 lookup、window 和当前三份 binding；其中双指纹、selector、lookup 与 window 必须由服务端从完整
`SopEntry` 和被选中的 `EvidenceRequest` 派生，不能接受目录作者自报哈希；操作员不能自造映射。

当前随仓未录制可执行目标数是 **0**：已验证的 SendMsg 合同已经录制，其他错误码查询合同尚未核实。
所以 T7 之前还有一项窗口外工作：为 20–30 个新 D1 selector 建立并复核精确查询合同。
目录够数以后，才进入需要 owner、内网 `*.prd.sangfor.com` 与受控运行时 Key 的那一格。

这项工作不再从 146 行源表手工挑选。确定性准备结果见
[`t7-target-contract-preparation.md`](./t7-target-contract-preparation.md) 和同名 JSON：清洗后恰有
**30 条只读候选**，其中 `csdp:IM1010` 已录制、`csdp:101014` 因源材料键冲突阻断，剩余
**28 条待 owner 补合同**；目标目录仍为 **0**。它只投影安全提示和缺失字段，不含原始日志、DQL、
凭据，也不能授予 T7。输入变化后运行：

```bash
python3 docs/intelligent-troubleshooting/l0/t7_target_preparation.py --write
python3 docs/intelligent-troubleshooting/l0/t7_target_preparation.py --check
python3 docs/intelligent-troubleshooting/l0/t7_owner_contract_intake.py --check
```

Owner 的可填写接力包见 [`t7-owner-contract-intake.md`](./t7-owner-contract-intake.md)；
15 个字段各自的取值来源、格式与报错见
[`t7-owner-field-guide.md`](./t7-owner-field-guide.md)。
[`t7-owner-contract-intake.recommended.template.json`](./t7-owner-contract-intake.recommended.template.json)
已把首批低成本的 15 条 A + 2 条 B + `csdp:101017 / csdp:101062 / csdp:301045` 三条 C
精确选中并展开 20 份合同；每个占位符都故意不能通过校验。
[`t7-owner-contract-intake.template.json`](./t7-owner-contract-intake.template.json) 保留全部 28 条空白候选，
仅在 owner 需要调整首批时使用。`sourceHints.hasLogSignatureHint` 只表示存在结构化日志提示；即使没有
提取出安全错误标识符也会明确显示，但不会带出日志正文。完成文件用下列命令校验，成功结果仍必须是
`PREPARED_NOT_EXECUTABLE / canAcceptT7=false / canWriteRuntimeCatalog=false`：

```bash
python3 docs/intelligent-troubleshooting/l0/t7_owner_contract_intake.py \
  --validate <受控本地目录>/t7-owner-contract-intake.local.json
```

校验失败时会一次列出所有已选合同的安全字段路径和原因，不回显 owner 填写值；
修完整批后再校验，不必通过数百次“只看见第一个错误”的往返。

正式工作台和 acceptance API 已共同执行这个前置条件：目录少于 20 个可执行目标时，UI 显示
`T7 BLOCKED · X / 20`、不展示 owner 清单；服务端也在 Guance 验证之前拒绝 acceptance。
“验证单条查询合同”继续用于窗口外准备，不能再当成 T7 已就绪。

动手顺序：

1. `csdp:101014` 保持隔离并行回源，不计入或阻塞首批；owner 直接复制建议 20 条工作表，
   逐项替换所有占位符并核实责任团队、真实运行 service、安全检索键、
   服务端查询合同、确定性判据/规则、三份当前 bindingRef 和精确历史时间，再跑
   `--validate`。校验器只接受安全引用，确定拒绝 DQL、URL、Key/Token 和 `rawLog`
   等额外字段；人类自由文本也不得嵌入原始日志，工具不把自己写成任意日志分类器。
2. 只把证据齐全的 20–30 条写入服务端 `guance-recording-targets.json`，冻结为
   **真实可执行且未录制**的目标；
   每项嵌入完整候选并选定正例 requestId；服务端重算精确 candidate/request 指纹，再要求三份
   bindingRef 与目标环境相等。不能把
   任意 D1 selector 指到现有 SendMsg 查询，也不能把已录制 IM1010 重复计数。
3. 从运行服务返回的 targetId 准备 20–30 条 `t7-recording-window-plan.v1` 本地计划，再运行
   `T7_SEED_PLAN_FILE=<受控本地文件> ./scripts/troubleshooting-t7-preflight.sh`（只读，不发凭据）。
   - 卡住 → 按它打出的 blocker 在**窗口外**解决，比进去现查便宜得多。
   - 通过 → 它会打印验收模板（七项全 `false`）。这时才值得约 owner 的时间。
4. 窗口里 owner 逐项核对后提交 `POST /evidence/guance/acceptance`（清单见 runbook §6）。
5. **窗口目标不是"跑通一次验证"，是一次灌 20–30 条录制种子**——
   D19 之后一条种子只要一份聚合正例，排除/弃权例由服务端生成。别浪费在单次验证上。

### 第二、第三优先：先决条件已经收口

- [x] **拍板“高置信错误为 0”如何测**：T8 `SystemConfidence` 不使用模型自报值；由服务端按独立、可复核事实
  派生系统置信度，再由人工 oracle 评分。台账发布已评估数、HIGH 数和 HIGH 错误数；
  `highConfidenceErrorFreeAcross(minimumHighConfidenceRuns)` 把非零分母写进方法签名。
- [x] **T0.9 让“哪条知识是真的”可见**：来源等级已进入不可变 Playbook 版本、注册表、
  `DeveloperEvidenceView` 和固定 146 条清单计数。`903001` 保留 approved 机制样例，
  但明确标为 `AUTHORED_FIXTURE`，不再与 IM1010 的真实聚合权威混显。
- [ ] T0.8 剩余 145 条种子导入——保持暂停。先在 T7 窗口拿到 20–30 条真实种子，
  再分批导入并保留拒绝清单；学习环已通，其中一部分可能不需要手写。
- [x] T0.10 v4 §5 与代码的命名分歧（结构账）已完成；T10.5 读取迁移已完成，
  最终弃读 `RouteMode` 待 P4 真场景同批统计后收尾。

### 等数据，别提前做

Challenger 影子运行、与单 Agent 基线对比、§5.7 退出阈值标定——
都要等那 20–30 条真实样本。统计口径已经写好了（含质量四项），
**刻意写在样本落地之前**：定义若在数据摆在眼前之后才写，就是照着数据挑的。

### 这一轮反复踩到的坑，留给下一个人

**闸门写错对象，比没有闸门更糟**——它给的是假的安心。本轮抓到四次：
gate 5 位置放错（挡在了别的前置上）、gate 10 查 `.data.sources`（真名
`sourceStates`）、gate 6 查 `citedEvidence`（真名 `evidenceCitations`，
空清单让断言永远成立）、CI 里 `grep -q | grep -v`（`-q` 不输出，
第二个 grep 收到空流）。前三次都是**查错字段返回空，于是"没有坏东西"永远为真**。

对策已经用上，建议延续：**新写的断言要用"故意改坏"验证一遍它是活的**。
T7 预检的三条断言就是这么验的，预检本身也因此配了双向回归——
它在本机只可能说"没就绪"，那条"就绪"路径本来会一次都没被走过就上线。

本轮核心新增断言也逐一做了反向证明：错标录制 lane、让同 selector 的改写候选继承权威、
按行数替代 146 成员关系、让历史记录绕过指纹直接升级、让坏历史挡住后续 keyset 分页、把未知等级
升为真实录制、破坏冻结版本投影、把历史缺失 Evidence Spine 反推为完整脊柱，以及把服务端 `HIGH`
降成 `MEDIUM`，均让各自测试准确失败（分别为 1、1、2、1、1、1、1、1、3 条）。恢复实现后
全部通过。这不是“有测试文件”的
旁证，而是核心断言确实会在对象被改坏时报警的证据。

本轮又把服务端批次门禁故意改成永不执行，并把前端 `recordingBatchReady` 故意恒置为真：前者使
acceptance 门禁测试准确失败，后者使 `0 / 20` 投影测试准确失败；恢复实现后两组测试全部通过。

owner 准备队列也做了两次反向证明：把真实字段 `recordedEvidenceSeeds` 故意改成单数时，测试准确暴露
已录制数 `1 → 0`、待合同数 `28 → 29`，共 3 条失败；把生成 Markdown 的 D1 分母故意从 146 改成
145 时，`--check` 明确报 `stale T7 preparation Markdown`。两处恢复后均通过，并已接入 smoke workflow。

真实服务预检还抓到第五次同形状问题：Java 全局 `Long → String` 让目录接口的
`asOfEpochSeconds` 返回十进制字符串，CI stub 却一直伪造成 JSON number。先把 stub 改成真实形状后，
旧预检准确红在第 5 格；修复后严格接受 1–10 位十进制字符串，并把旧 number 形状加入反向拒绝。
对当前 `18088` 服务重跑只读预检，前 4 格通过，随后如实报告
`0 个可执行新目标（冻结 0 个）/ 20 required`。这才是本轮要带进 owner 协作前的真实 blocker。

owner intake 门也做了反向证明：把最小选择数从 20 故意降为 19 时，19 条输入测试准确失败；
关闭敏感内容检测时，DQL / URL / `DF-API-KEY` 三类坏输入准确变绿为红；把已生成模板的
候选数从 28 改成 27 时，`--check` 准确报生成物陈旧。另外，当前只有 28 条可选时，
新增的有效上限断言先准确暴露模板误写 `maximum=30`，修复后才与校验器一致为 28。
最后把第 2 条的 service/search/window/bindings 改成与第 1 条完全相同、但保留不同公开引用和指纹，
Python owner 门与 Java 运行目录新断言都先准确失败；修复后两层均拒绝这种“改名不改查询”的假批次。
推荐工作表接入后又故意只在自由文本 `ownerTeam / ownerScenario` 中保留 `<replace:...>`；新断言先准确失败，
随后校验器统一拒绝任意字段中的未替换占位符，避免“结构填完但责任人与场景仍是模板文字”被放行。
最后故意要求读取 `csdp:301002.sourceHints.hasLogSignatureHint`，旧模板因查不到字段准确失败；补入安全布尔
投影后才恢复，避免 `A_HINTED` 在错误标识符提取为空时被误读成“无日志提示”。

### 第六次同形状问题：闸门挂错了地方（2026-08-05）

这一次坏的不是断言，是**断言所在的位置**。T7 交给 owner 的两组产物（录制目标队列
`t7-target-contract-preparation.{json,md}`、owner 契约模板 `t7-owner-contract-intake*.json`）都是
生成物，指纹由四份输入决定，其中三份在 `mateclaw-server/**` 的 resources 里。本分支把回放场景加到
5 条（`cb5749d`）时改旧了**两组**产物，而两个 `--check` 都只挂在 smoke workflow 上。

工作流的 `paths:` 过滤器**是对的**——`mateclaw-server/**` 在列，改回放套件确实会触发它。真正的缺口是
触发条件：workflow 只在 PR 或 `dev / main / intelligent-troubleshooting / claude/*-design` 的 push 上跑，
而改动发生在没有 PR 的会话分支上。**闸门存在、接线正确、却不在改动发生的地方**，于是产物一直陈旧，
直到有人手工跑 `--check` 才发现第一组，第二组是被 `test_t7_target_preparation` 顺带逮到的。

对策：把两个 `--check` 前移到 `scripts/troubleshooting-test.sh`，在 maven 之前跑（几百毫秒）。
两个生成器都要跑——它们是两组独立的 committed 产物，各自有各自的 `--check`，**绿一个不代表另一个绿**，
第一次修复只补了 owner 模板就是这么漏的。已确认全仓只有这两个 `--check` 生成器，闸门是完整的。

反向证明照旧：把 `recordedSuitesSha256` 改坏后，脚本准确以退出码 3 停在 maven 之前，并指名
`t7_target_preparation`；恢复后两份 `--check` 均通过，20 条 T7 工装测试与 818 条领域测试全绿。
两次重新生成都只动了输入指纹（`preparationFingerprint`、`recordedSuitesSha256`），
`counts` 与 owner 队列内容未变——即第 5 条场景没有改变 owner 要填的东西，这次陈旧纯属出处失真。

留给下一次的教训：**问「这个检查跑在改动发生的地方吗」，而不是「这个检查存在吗」。**
CI 上的绿灯只覆盖它被触发的那些路径。

### 第七次同形状问题：把夹具当成了真源（2026-08-07）

取证配置页重设计时新加了一个闸门，判据写成 `sources.some(s => s.status === 'READY')`。
跑真服务一看，页面对着一台 **guance / prometheus / elasticsearch 三个真源全 DISABLED**
的机器说「数据源已就绪」——因为还有第四个源 `recorded-replay` 是 READY 的
（`sanitized replay catalog loaded: 14 records`）。

这是本仓库反复申明「不可塌陷的三条轴」里的第一条（证据成色：夹具 vs 真源）被塌掉了。
`formalProjection` 早就定了规矩：`recorded-replay*` 单列为 `RECORDED_REPLAY`，并明写
「页面不会把回放证据描述成真实生产观测」。新写的闸门没有守这条。

修法不是把 `sourceReady` 改掉——它喂给 `buildModuleToolSetups`，回答的是「这条工具能不能
跑起来」，在那个问题上回放算数。**两个问题不同，不能并成一个判据**：新增
`realSourceReady`（READY 且非回放）驱动页面话术，`sourceReady` 原样保留；再加一个
`replayOnly` 让「只有回放可用」这一格有自己的话，而不是被归进「没有源」或「已就绪」。

发现方式值得记：**它不是被测试或类型检查抓到的，是把页面跑起来截图看出来的**。
更准确地说，我第一次查接口时用 `head -c 500` 截断了 JSON，恰好把第四个源截掉了，
于是「三个源全 DISABLED」这个错误印象一直带到了写闸门的时候。截断输出等于在采样，
**采样结果不能当成全集**——这和第一到第五次「查错字段返回空、于是没有坏东西永远为真」
是同一个形状：证据不全，却当成证据齐了。

反向证明：把判据改回 `some(status === 'READY')`（即把夹具当真源）后，
`never calls a replay-only workspace a ready data source` 准确失败；恢复后 5 条全过。
另外三条也各自单独证明过——恒定闸门为 `NEEDS_MODULE`、把每平台「缺什么」抹成空串、
把搜索框和旧页面链接放回空态，分别只让对应的那一条变红，互不牵连。

---

**已经不再是阻塞项**：「跑不通一个场景」。T0.5–T0.65 已完成，
`quickstart.md` 一条命令可复现，八道闸门全绿，结论 `LOCATED`。

**唯一还没被验证过的核心断言**：证据可信。`fixtureMode` 恒 `true`。
注意 `fixtureMode` 只说"这次取证是回放"，**它不回答"这条知识的判据是不是编的"**——
后者是 T0.9 要补的正交维度。在 T7 通过之前，任何"系统能定位根因"的说法都只覆盖到链路。

## 0. 当前判断

> 更新于 T0.65 之后。**"没有一条默认可走的路"这个主要矛盾已经解除**：
> `troubleshooting-demo` profile + `scripts/troubleshooting-smoke.sh` 现在能从
> clone 走到一份 `LOCATED` 的诊断，八道闸门全绿，退出码 0。
>
> 矛盾因此回到了它原本该在的位置：**链路可走，但证据不可信**——全程 fixture，
> `fixtureMode` 恒 `true`。下一个真实约束是 **T7 内网窗口**，它需要人和时间，
> 不是代码问题。
>
> T0.8 的机制决策和首个 IM1010 切片已经完成，不再是设计阻塞。
>
> **但 D19 落地带出了一个新的、更细的矛盾**：知识开始能规模化了，而
> `fixtureMode` 只标记"取证是回放"，**不标记"判据是编的"**。IM1010 的阈值来自
> 真实历史聚合，903001 的阈值来自一张脱敏 xlsx 的推测——两者在注册表里完全平级。
> 现在只有两条，人还分得清；批量导入之后就分不清了。见 §3.5 **T0.9**。

当前主线不是继续扩错误码页面，也不是接入更多 Agent 工具，而是先把会议指定案例跑通：

```text
会话消息发送失败（无 error_code）
  → log_search
  → 提取 PS ID
  → log_trace_bundle
  → 确定性压缩
  → PlaybookDraft
  → 与人工参考解法比较
  → candidate（不可直接生效）
```

现已完成 P1 fixture-only 竖线：固定三次取证、结构化归纳、确定性校验、参考解法比较、幂等 candidate 边界和北极星时间戳。
P1 本身未改路由、企微或生产数据；其后 T15 已单独将双投影和 D14 运行时采集进入正式工作台。
仍未实现 Loop Controller 或多 Agent Challenger。
验证记录见 `p1-verification.md`。

## 1. 每个变更都要守的红线

**红线的唯一权威清单是 `rfcs/intelligent-troubleshooting-architecture-v4.md` §9。**
本文不再复述条目——此前同一批约束在 v4 §1.2、v4 §9、HANDOFF 和本文各写了一遍，
条数与措辞互不相同，"哪一份是权威"事实上已经不唯一（见 `architecture-critique-v4.md` §2.5）。
动手前直接读 v4 §9；发现分歧以 v4 §9 为准，并在那里修改。

## 2. 已有底座，不要重复建设

- [x] `vip.mate.troubleshooting` Java 领域模块、REST、RBAC、持久化和状态机。
- [x] `(system,errorCode)` 的确定性 SopEntry 与 903001 端到端竖线。
- [x] 受限 Agent miss-path，唯一只读证据工具，失败保守 abstain。
- [x] `EvidenceSourceRouter`、Guance Adapter、Recorded Replay Adapter、canonical schema。
- [x] `log_search` / `log_trace_bundle`、PS ID 一致性、行数/时间窗/脱敏边界。
- [x] `DeterministicLogTraceCompressor`，模型前产出有界调用链骨架。
- [x] `SopSynthesisService.preview()`，fixture scope 内可到 `READY_FOR_MODEL`。
- [x] Diagnosis 处置闭环：确认、转派、批准不执行、外部 outcome、恢复验证、关闭。
- [x] KnowledgeCandidate + Outbox 只表达发布语义；独立审核台账已支持
      `CANDIDATE/v0 → IN_REVIEW/v1 → REJECTED/v2`，不复用 Outbox status。
- [x] Vue 排障工作台和三套只读体验原型。

## 3. P0 · 架构和体验校准

- [x] 从 28:30 录音中抽取 F1–F11 产品事实，并区分事实与讨论脑暴。
- [x] 删除误引的其他项目口径，只保留 MateClaw。
- [x] 形成架构 v4：一条证据脊柱、在线诊断/知识生产两个闭环。
- [x] 拆分 `investigationMode` 与 `routeAuthority`。
- [x] 权威 Playbook 只含 ERROR_CODE / SCENARIO；OPEN_DISCOVERY 使用独立 DiscoveryPolicy。
- [x] 完成架构师评审并关闭 8 个高优先级问题。
- [x] 蓝图 v0.8 增加 Loop Engineering 与多 Agent 结构化反证；保持 P1 范围不变。
- [x] A/B/C 三套 Demo 浏览器冒烟通过。
- [x] 第一性原理评价 v4 并落修订：D5′ 晋升分档、北极星时间戳、成功样本对照、
      PENDING-EVIDENCE 标记、红线收敛到 v4 §9（`architecture-critique-v4.md`）。
- [x] 原型补齐区分度：4 种结局 × 3 档路由可信、北极星三段耗时、成功样本对照、
      conclusionType 标记、可点的处置按钮、重复 `:key` 修复；另出不依赖 dev server 的
      静态镜像 `experience-prototype-demo.html`。
- [x] **信息结构已选定**：集中兵力做**服务经理摘要 + 开发证据台**两个投影，业务摘要默认展开、
      开发证据默认折叠；企微独立 UI 投影原型暂缓，不阻塞 P3 T9 真实通道实现。开发证据的入口做成 `view=INLINE|SPLIT` 可切，
      两者渲染同一份投影，入口选择不影响后端合同。
- [x] 两个投影合同已固定：`projection-contracts.md`（BusinessSummary / DeveloperEvidenceView
      / NorthStarTimings，含服务端不变量）。**P1 只固定合同，不实现 Projection**。

## 3.5 P1.5 · 让一条场景默认可跑（**已解除**，遗留 T0.7 / T0.8）

**为什么插队。** 文档此前把主要矛盾写成「代码闭环已通 vs 未在真实数据上验证」，
应对是 T7 内网窗口。那是上一阶段的判断。实际情况是：**默认状态下没有任何一条路径可走**——
两个证据源默认关闭、仓库不随带任何 Playbook（迁移里 0 条 INSERT），任何报障必然 route miss。

每道闸门单独看都对（fail-closed 是纪律），但**它们的合取**决定了有没有人能用起来，
而此前没有任何东西在度量这个合取。

两个后果：
1. **T7 窗口拿到了也用不上**——操作员会卡在同样的配置迷宫里，而那是最贵、最难重来的一次机会；
2. **"它是否安全"其实还没被真正检验**——fail-closed 只在有人真的去开门时才会被测试。

### T0.5 · 端到端冒烟（已完成）

- [x] `scripts/troubleshooting-smoke.sh`：以操作员的方式走 HTTP，逐道闸门断言，
      失败时指出**是哪一道**和**唯一的下一步**；`--gates` 可在无服务时列出全部闸门。
- [x] 断言覆盖：结论类型、开发证据步数、`fixtureMode` 必须为 true、北极星三段耗时。

### T0.6 · demo 种子（已完成）

- [x] `troubleshooting-demo` profile：打开 Recorded Replay，把 CSDP 六个 signalKind 路由过去，
      **不碰 Guance**。
- [x] `TroubleshootingDemoSeeder`：默认关闭（`mateclaw.troubleshooting.demo.enabled`），
      走同一个 `TroubleshootingSopPersistenceService` 注册，全部不变量照常生效；
      动作里没有 `MANUAL_WRITE`。（晋升方式在 T0.65 被推翻重做，见下。）
- [x] `TroubleshootingDemoSeederTest`：锁住种子与回放样本一致——
      **两者漂移不会抛异常，只会静默变成 `UNEVALUATED`**，操作员看到"证据不足"却分不清
      是真缺证据还是配错了。这正是需要单独测试的原因。
- [x] `quickstart.md`：把散在多份 runbook 里的步骤合成一条主线。

### T0.65 · 真的跑了一次（已完成，且推翻了 T0.6 的两个假设）

第一次把服务真的起起来跑冒烟，暴露了只有运行才会暴露的问题：

- [x] **种子的"批准"是假的**。第一版做的是 `updateStatus(..., "approved")`，运行时被拒：
      `candidate approval requires the eligibility gate and must create a new version`。
      这个拒绝是对的。现在种子走真实晋升链：注册 candidate → 跑固定回放套件 →
      `PASSED` 后资格快照才 `ELIGIBLE_FOR_APPROVAL` → `start` + `approve` 晋升出 v1。
      回放不过就不晋升，路由保持缺失，冒烟脚本照实报告。
      台账里 `approvedBy=ts-demo-seeder`，一眼可见没有人审过。
- [x] **补上 `csdp:903001` 的固定回放套件**（2 正例 + 2 反例/弃权例）。
      在此之前仓库里只有 `csdp:scenario:deployment_topology_probe` 一套，
      也就是说**任何错误码 Playbook 都没有晋升路径**——而错误码正是产品的主干形态。
      这不是 demo 的缺口，是主干缺件。
- [x] **冒烟脚本加第 8 道闸门**：结论不得是 `INSUFFICIENT_EVIDENCE`。
      第一次跑通时前七道全绿而结论是"证据不足"——报障 service 与回放样本不匹配，
      三条证据全 MISSING、四条判据全 UNEVALUATED。
      **"证据不足"同时也是系统在真实缺证据时的正确输出**，
      从外面分不出是链路断了还是真没证据，所以必须单独立一道闸门。
- [x] 修正脚本里与现实不符的三处：默认端口 8080→18088；
      判定证据源用了不存在的 `.enabled` 字段（实际契约是 `EvidenceSourceHealth.status`）；
      默认 service `csdp-order-service`→`order-svc`。
      并支持 `MATECLAW_USERNAME/PASSWORD` 登录换 JWT，不再强制先造 PAT。
- [x] 顺手修一处既有 flaky：`ScheduledBaselineClaimLeaseKeeperTest` 断言心跳"恰好一次"，
      而心跳是 `scheduleAtFixedRate(10ms)`，快机器上必然多跳一次。改为 `atLeastOnce()`。

实测结果：`LOCATED` / R2「慢查询占满连接池」/ HIGH，
且 `instance_unreachable` 是 **EXCLUDED（真的排除）而非 UNEVALUATED（没验过）**——
D15 负对照在真实 HTTP 边界上第一次被验证成立。

### T0.7 · CI 首诊基线（2026-08-02 已建立）

**现在做这些是有意义的：脚本第一次是绿的，可以当回归基线用。**

- [x] 把 `troubleshooting-smoke.sh` 挂进 CI，作为"默认路径没有被堵死"的回归。
      每加一道需要人工配置的门，它应当立刻变红。
      需要一个能起服务的 job：`mvn -pl mateclaw-server -DskipTests spring-boot:run
      -Dspring-boot.run.profiles=dev,troubleshooting-demo`，H2 默认库即可，
      不需要外部依赖。注意 `mateclaw-plugin-api` 要先 `install` 进本地库。
      已由 `.github/workflows/troubleshooting-smoke.yml` 落地：PR、`dev`、主干与当前设计分支推送和
      手工触发均可运行。demo Playbook 120 秒内未晋升就绪、八道闸门任一失败、开发证据为空，
      或北极星三段状态被伪造都会 fail-closed；服务与冒烟日志会无条件保留。
- [x] 记录并跟踪**从 clone 到看见一次诊断的时间**。我们量了客户的排障时间（北极星），
      却从没量过自己跑通一次要多久；目标 5 分钟内。
      CI 从 checkout 前开始计时，在成功产出 Diagnosis 后写入 Job Summary；超过 300 秒先告警，不伪装为
      产品正确性失败。终点取脚本首次观测到 `diagnosisId` 的时刻，不含后续投影校验。
- [x] 用 GitHub 官方运行历史确认基线已实际产生，不再把“workflow 文件存在”冒充“CI 跑过”。
      截至 2026-08-02 共 6 次运行，前两次失败后最近 4 次连续成功：

      | Run | Commit / event | 结果 | 总耗时 |
      |---|---|---|---|
      | [#1](https://github.com/chedou/mateclaw/actions/runs/30685260971) | `e95e7be` push | FAILURE | 44s |
      | [#2](https://github.com/chedou/mateclaw/actions/runs/30704422460) | `21de00d` push | FAILURE | 3m10s |
      | [#3](https://github.com/chedou/mateclaw/actions/runs/30707108112) | `f24a603` push | SUCCESS | 1m27s |
      | [#4](https://github.com/chedou/mateclaw/actions/runs/30737322529) | `aa6128b` pull request | SUCCESS | 1m23s |
      | [#5](https://github.com/chedou/mateclaw/actions/runs/30737437300) | `9d4cdd6` push | SUCCESS | 1m15s |
      | [#6](https://github.com/chedou/mateclaw/actions/runs/30745097839) | `5d54d5d` push | SUCCESS | 1m33s |

      最新 run #6 的主 job 为 1m29s，并且成功状态证明“记录 clone-to-diagnosis 时长”步骤已通过；
      首次 Diagnosis 发生在 job 完成之前，因此实际 clone-to-diagnosis 有严格的 **≤89s 上界**，
      已满足 300s 目标。公开未登录页面不展示 Job Summary 中的精确秒数，所以这里只记录可独立复核的上界，
      不把 93s 总运行时间冒充精确首诊时长。
- [ ] **归 T7，不是 T0.7 的剩余实现：** T7 时把 demo 绑定替换为真实 Guance 绑定，而不是从零配置。

### T0.8 · 错误码 Playbook 的晋升路径（机制与首个切片已完成）

**这不是 demo 的待办，是主干的。** 手工 Playbook 要晋升必须有服务端固定回放套件
（`ManualPlaybookReplaySuiteCatalog`）。T0.65 之前，仓库里只有
`csdp:scenario:deployment_topology_probe` 一套——也就是说**任何错误码 Playbook
都没有晋升路径**，而错误码正是产品的主干形态。现在补上了 `csdp:903001`，
但那是为 demo 补的，不是为 146 个错误码补的。现已用 D19 关闭机制缺口：

- [x] **不要求每个错误码人工手写三套用例，也不降低错误码路晋升门。** 每条路由维护一份
      server-owned、安全有界的录制聚合正例；服务端按封闭判据词汇确定性生成排除例和全
      `MISSING` 弃权例，再执行原有精确回放评测。判据形状发生反例值冲突时拒绝该种子。
- [x] 在 v4.5 §5.7 / D19 写成显式条款：录制事实不得含原始日志、DQL、凭据或真实资产标识；
      历史回放始终保持 `fixtureMode=true`，不能替代 T7 在线验收。
- [x] 保留固定套件 fail-fast；`recordedEvidenceSeeds` 逐条解析和验证，坏生成种子以稳定错误码
      隔离该 selector，不再让一条坏生成知识拖垮整个平台启动。
- [x] 首个真实历史聚合切片 `CSDP / csp-rpc-msg / IM1010`：日志命中 2、失败 2/2、成功
      0/14047；默认 HTTP smoke 实测 `LOCATED / MEDIUM / fixtureMode=true`，结论只定位到
      消息发送或 MQ 生产者路径，不宣称 Kafka Broker 故障。
- [ ] 按同一合同分批导入剩余 145 条错误码的脱敏聚合正例；每批必须保留拒绝清单和测试证据，
      不得把“机制已完成”冒充“全量知识已覆盖”。

### T0.9 · 把"哪条知识是真的"变成可见的数（D19 落地后新出现的问题）

D19 之后服务端目录与历史注册表同时存在两类容易被混淆的资产：

| selector | 当前形态 | 正例来源 | 阈值来源 |
|---|---|---|---|
| `csdp:IM1010` | 服务端录制候选，当前 workspace 尚未形成 active-approved 版本 | 真实历史聚合（失败 2/2、成功 0/14047） | 真实数据 |
| `csdp:903001` | legacy active-approved 版本 | 为跑通晋升链手写的 fixture | **从脱敏 xlsx 推的，未经任何核实** |

**历史问题是 approved 状态无法证明来源；目录里有录制候选也不能冒充该 workspace 已有真实知识。**

这正是本架构在别处极其小心的那类错误：`EXCLUDED` 与 `UNEVALUATED` 严禁混显、
`fixtureMode` 必须显式标记——但在**知识本身**这一层，同样的混显刚刚出现了。
`fixtureMode` 说的是"这次取证是回放"，它没有回答"这条知识的判据是不是编的"。

- [x] 给录制种子引入**证据来源等级**：`RECORDED_AGGREGATE / AUTHORED_FIXTURE /
      UNVERIFIED` 已冻结到 V190 Playbook 版本，并在注册表列表、详情和
      `DeveloperEvidenceView` 上可见。它与一次运行的 `fixtureMode` 正交，未知历史值保守回落
      `UNVERIFIED`，不能抬高权威。
- [x] 把**录制证据覆盖**做成一等计数：固定 D1 分母为 146，服务端返回注册数、
      真实聚合数、手写夹具数、未核实数和清单外数。2026-08-02 本地 V190 重启后的实际注册表为
      `0 / 146`：录制目录中的 IM1010 尚未在本 workspace 形成 approved 版本，legacy 903001 保持
      `UNVERIFIED`。这正是 fail-closed 的预期，不用目录候选数冒充注册表覆盖。
      146 个成员冻结在服务端 manifest 中，按成员关系而不是按行数统计；场景 selector 不进入
      错误码分母，清单外 CSDP 错误码另计，接口与 UI 都刻意不发布百分比。
- [x] **显式标注 `csdp:903001`** 为 `AUTHORED_FIXTURE`。它仍承担“人工批准不等于执行”
      和固定回放 bootstrap 的机制证明；这个等级只授予服务端冻结的精确候选。内容不同的 legacy
      903001 即使 approved 也保持 `UNVERIFIED`，不与 `IM1010=RECORDED_AGGREGATE` 平级展示或
      计入真实录制覆盖。

权威合同见 `knowledge-evidence-grade-contract.md`。来源等级由服务端受控回放目录赋值，
且 selector 与候选内容指纹必须同时精确匹配冻结示例；SQL 迁移不信任公开 source ID，
历史版本由启动协调器重建候选并复算相同指纹后才升级。
协调器按 ID keyset 分页，永久不匹配的坏历史不能让后续精确候选饥饿。未知 selector 或同 selector
的改写候选没有来源等级时晋升 fail closed。T0.9 已在 T0.8
批量导入前完成。

### T0.10 · v4 §5 与代码的命名分歧（结构账，2026-08-02 已完成）

v4 §5 自称"稳定契约"，其中 **8 个在代码里不存在**：
`InvestigationPlan`、`EvidenceBundle`、`DiscoveryPolicy`、`LoopPolicy/LoopRun/LoopOutcome`、
`AdversarialEvalReport`、`InvestigationPlaybook`、`ReadOnlyEvidenceToolRegistry`、`ScenarioProposal`。
另有 2 个以不同形状存在：`SopEntry` 无 `type`/`selector`（SCENARIO 靠 `errorCode="scenario:xxx"`
字符串前缀编码）；`EvidenceBundle` 实际是裸 `List<EvidenceResult>`。

其中 `LoopPolicy` / `AdversarialEvalReport` 已标 PENDING-EVIDENCE，未实现是**对的**。
问题在另一半：文档声称是权威，代码事实上是另一套，下一个人按 §5.4 去找 `EvidenceBundle` 会找不到。

- [x] 逐条判定：**收敛代码** 还是 **RFC 记下真实命名**。当前选择先校正文档事实：
      `SopEntry → type + selector` 涉及 routing key、不可变版本与持久化兼容，随 T10.5 / P4 一次迁移；
      `EvidenceBundle` 等 `InvestigationPlan`、bundle identity、plan 绑定、fixture 与持久化边界明确后一次收敛，
      不先新增只包装裸列表的空壳；`DiscoveryPolicy`、Loop 与 Challenger 合同继续等待真实样本。
- [x] 已在 v4 §5 开头加入**实现状态表**，覆盖 §5.1–§5.11，并补入跨章节的
      `ScenarioProposal` 与 `ReadOnlyEvidenceToolRegistry`；“设计”与“已实现”现在可在同一页分辨。

本项只关闭结构账，没有推进 T7、P4/P5，也没有新增接口、表结构或运行时类型。设计取舍和复核步骤见
`docs/superpowers/specs/2026-08-02-t0-10-contract-ledger-design.md` 与
`docs/superpowers/plans/2026-08-02-t0-10-contract-ledger.md`。

---

## 3.6 P1.6 · 让**无码路**默认可跑（**已打通**，遗留 T0.13 收尾项）

**为什么插队。** 蓝图 §11.1 里唯一被点名"必须先通过"的验收案例是**无 error_code** 的
「会话消息发送失败」。但连续几轮的默认可跑路径、CI 回归、quickstart、D19 规模化机制，
全部落在**错误码命中路**上。P1.5 为命中路解决的那个"闸门合取"问题，在无码路上原封不动地还站着：

| 闸门 | 命中路 | 无码路 |
|---|---|---|
| 证据源 | demo profile 已开 | 同上（复用） |
| Playbook | demo 种子已晋升 | 不需要 |
| **Agent** | 不需要 | `agent.enabled` 默认 `false`、`agentId` 默认 `0` |
| **模型** | 不需要（零 LLM） | 需要已配置的 provider + default model |
| demo profile 是否覆盖 | ✅ | ❌ **完全没有** |

**更要紧的是供给侧的账。** D19 扩的是晋升门的吞吐，不是知识的供给。现在错误码 Playbook
能高效晋升了，但它们从哪来？答案是人读那张 xlsx 手写。而蓝图里负责供给的正是学习环
（无码报障 → 三次取证 → 确定性压缩 → `PlaybookDraft` → 与人工解法对照 → candidate），
它从来没在单元测试之外跑通过一次。

> **在线排障闭环消费知识，知识生产闭环供给知识；现在消费侧修得又快又稳，供给侧还没通电。**

剩下 145 条 × 人工手写，正是这个产品声称要消灭的那个瓶颈。

### T0.11 · 无码路端到端冒烟（已完成）

- [x] `scripts/troubleshooting-miss-path-smoke.sh`：以操作员方式走 HTTP——
      `POST /sops/synthesis/preview` → `POST /sops/synthesis/candidates`，逐道闸门断言，
      失败时指出是哪一道和唯一的下一步。与命中路脚本共用同一套闸门叙事，但**不共用闸门**。
      **先让它红**：第一次运行停在闸门 4 `MODEL_UNAVAILABLE`；闸门 1–3 当场就是绿的，
      说明证据脊柱和确定性压缩早就能在 HTTP 边界上跑，缺的只有模型那一步。
- [x] 断言覆盖这几条**只有无码路才有**的不变量：
      - 产出的是 `CANDIDATE_CREATED`，且 `reviewStatus=CANDIDATE`；
      - **`approvalEligibility` 必须是 `NOT_ELIGIBLE`**——证据型草稿永远不能被自动晋升；
      - `referenceComparison` 存在且有结构化差异，不是空对象；
      - `fixtureMode=true`；
      - 重跑一次得到 `CANDIDATE_REUSED`（generationKey 幂等），不产生第二条候选。
- [x] **顺带修掉两条脚本共有的一个真 bug**：`call` 在命令替换里执行（子 shell），
      `HTTP_CODE` 从来传不回父 shell，于是每一处状态判断读的都是**更早一次请求**的状态码。
      它一直是绿的纯属巧合，而失败时报的原因是错的（"当前状态是 unknown"而不是"HTTP 404"）。
      改为经文件传递；现在 404/409 会如实报出来。

### T0.12 · demo 侧的确定性模型响应（已完成）

无码路必须调一次模型，而 demo 不能依赖真实 provider。做法与录制证据同构：
**服务端拥有一份录制的模型响应**，不是"跳过模型"，也不是"假装模型在线"。

- [x] `troubleshooting/synthesis/recorded-draft-proposals.json`：按
      (system, service, searchTerm) 键入的 server-owned 录制 `PlaybookDraftProposal`。
- [x] `RecordedPlaybookDraftInducer`（`@Primary`，仅 demo 开关打开时生效）替换一次模型调用，
      其余流程（确定性校验、参考解法比较、候选写入、幂等）**完全不变**——
      被替换的只有"模型说了什么"，不是"我们信不信它"。
      **没有录制的案例回落到真实 inducer**，绝不替一个没录过的案例作答。
- [x] **provenance 自证**：`provider=recorded`、`modelName=recorded-demo-draft`，
      不冒用任何真实 provider 名。读候选的人一眼能看出这次归纳没有真的调过模型——
      和 `approvedBy=ts-demo-seeder` 是同一个手法。
- [x] `RecordedPlaybookDraftInducerTest` 6 条：录制响应通过确定性校验、只提议 SCENARIO、
      引用全部属于本次证据、动作只能 `EXTERNAL_HUMAN`、provenance 不含真实 provider 名、
      坏目录直接抛错。**含一条负对照**：把录制响应污染成生产写动作后仍被拦下——
      否则"录制响应能通过校验"与"校验根本没在跑"无法区分。

### T0.13 · 收尾

- [x] 两条 smoke 一起进 CI；无码路失败同样让 job 失败。
      `scripts/ci/test-troubleshooting-smoke-workflow.sh` 增加静态合同：无码路入口存在、
      排在命中路之后、脚本可执行，且仍带 `NOT_ELIGIBLE` / `CANDIDATE_REUSED` 两道反向断言。
- [x] `quickstart.md` 增加第二条主线：不仅"看见一次诊断"，还要"看见一条知识被生产出来"。
- [x] “无码路默认可跑”这个 T0.8 前置已经满足；学习环已通，其中一部分可能不需要手写。
      **这不等于现在启动 145 条导入**：当前暂停原因已转为先在 T7 窗口取得 20–30 条真实种子，
      再据真实供给/拒绝分布决定后续批次，见 T0.8 主条目。

**实测**（`dev,troubleshooting-demo`，H2 默认库）：九道闸门全绿，
`CANDIDATE_CREATED` → `reviewStatus=CANDIDATE` → `approvalEligibility=NOT_ELIGIBLE` →
`referenceComparison.passed=true` → 重跑 `CANDIDATE_REUSED` 复用同一候选。
命中路八道闸门同时保持全绿。后端 607 tests / 0 failures。

**没有证明**：归纳得对不对。录制响应是一次真实模型输出的快照，
它证明学习环可走，不证明模型在真实证据上会归纳出同样的东西——那是 T7 与 T8 的事。

---

## 3.7 P1.7 · 把一个案子真正走完（已完成）

**为什么。** P1.5 和 P1.6 各自证明了一个环，但都停在环的前半段——一个停在"诊断可读"，
一个停在"候选已产出"。而**交接、批准、外部登记、恢复验证、关闭**才是服务经理和处置人
真正做的那部分，也是北极星里「可交接」所在的地方。

**顺手撞出来的那件事更要紧。**「人工批准只推进状态机，不触发执行」是整个产品安全论证的
支点。此前它只有**拒绝**那一半被演示过（`POST /execute` 返回 409）；**肯定**那一半——
批准之后动作变成 `APPROVED_NOT_EXECUTED`，而 `executionStatus` 仍然是 `BLOCKED`——
在 HTTP 边界上从来没有被走过一次，因为**两条 Playbook 都没有 `MANUAL_WRITE` 动作**。

### T0.14 · 单案端到端场景（已完成）

- [x] `scripts/troubleshooting-scenario-smoke.sh`：十道闸门，报障 → 诊断 → 确认 → 交接 →
      批准 → 执行仍拒 → 登记外部结果 → 关闭 → 沉淀 OUTCOME_BACKED 候选。
- [x] **闸门 6 是这条脚本存在的理由**：断言 `approvalStatus=APPROVED_NOT_EXECUTED` 的同时，
      `executionStatus` 必须**仍然是** `BLOCKED`。这是整条链里唯一一道"必须什么都没发生"的闸门。
- [x] 给 `csdp:903001` 加一个 `MANUAL_WRITE` 动作。**它的定位也因此变了**：
      不再是跟真实数据的 IM1010 争可信度的"第二条知识"，而是专门行走这条红线的夹具。
      这同时消解了 T0.9 第三条的一半顾虑——903001 现在有了不可替代的用途。
- [x] `ManualPlaybookReplaySuiteCatalogTest` 锁两面：903001 必须带一个处于合法阻塞态的生产写；
      **IM1010 必须没有**——往唯一一条证据来源的知识里掺进手写指令，等于污染它的来源。
- [x] 三条冒烟一起进 CI，静态合同同步扩展。

### 撞掉的一个过时不变量

`TroubleshootingDemoSeederTest` 原本断言"种子 Playbook 不含任何生产写动作"。
**这个不变量是错的，而且有害**：`MANUAL_WRITE` 在本系统里不是危险能力——它从注册那一刻起
就是 `BLOCKED`，平台没有生产写执行器，批准只推进状态机。禁掉它并没有让任何东西更安全，
只是让那条保证**永远无法被演示**。已替换为真正该守的那条：**种子里的生产写必须处于
合法的阻塞态**（requiresApproval + PENDING + BLOCKED）——比"一条都不许有"严格。

### 顺带修掉的两处虚断言

- [x] 闸门 5 原本放在 confirm 之前，于是 409 的原因是"尚未确认"而不是"尚未批准"——
      它测的根本不是它声称的东西。已移到 confirm 之后，并断言 409 的原因必须含 `approved`。
- [x] 闸门 10 查的是 `.data.sources`，而真实字段是 `.data.sourceStates`，
      外面还包了一层 `if [[ -n ... ]]`，等于这道闸门永远不会失败。已改为硬断言。

**实测**：十道闸门全绿；OUTCOME_BACKED 候选 `NOT_ELIGIBLE`，阻塞原因只剩一条
`POSITIVE_REPLAY_REQUIRED`——它已经有 outcome 证明、恢复验证、owner、引用和 selector。
D19 为 MANUAL 建的"录制正例 + 判据形状生成反例"机制，看起来正好可以接到这条 lane 上，
但那是一个需要显式决定的设计变更，不擅自做。

**同时确认了一堵墙是对的**：EVIDENCE_DERIVED 候选晋升被
`OWNER_REQUIRED / POSITIVE_REPLAY_REQUIRED / NEGATIVE_OR_ABSTAIN_REPLAY_REQUIRED / FIXTURE_ONLY`
四条挡住。其中 **`FIXTURE_ONLY` 是对的**（A10：回放不能冒充真实验证），
所以"知识生产环走到晋升"在 fixture 下本来就不该通。不去动它。

---

## 3.8 P1.8 · 第一个场景的全链路（**已打通**）

**第一性原理下，"第一个场景"是蓝图 §11.1 点名的那个**：无 error_code 的
「会话消息发送失败」。北极星的原话也是「从一条**不完整**报障，到……」。
所以全链路的头是**补问**，不是 `POST /incidents`——而此前所有冒烟都从一条
字段齐全的报障开始。

### T0.15 · 链头：补问 → 补齐 → 调查（已完成）

- [x] `FirstScenarioIntakeChainTest`：不完整报障 → `AWAITING_INPUT` + 说明缺什么 →
      三分钟后补齐 → `READY` → `report(session)`，并验证两个北极星时间戳被**原样**带进调查。
- [x] **补上了一个从未被端到端产生过的指标**。北极星第一段「补问成本」此前在测试里
      只有手工常量（30s / 1min / PT2S），唯一基于 session 的测试喂的是一条**首帧就完整**
      的消息，于是 `readyAt == reportedAt`，跨度结构性恒为 0。
      **只对着常量断言的指标等于没有被度量。** 现在这个数第一次从机器里出来。
- [x] 服务用生产构造器（真实系统时钟，离固定时间戳数月），所以任何一层若用 `now()`
      顶替 session 边界，`eq(REPORTED_AT)` / `eq(READY_AT)` 会立刻失败。

### T0.16 · 链身：在线 lane 曾对无码报障关闭（历史缺口，方向 (c) 已落地）

当时的默认配置下：

```
无错误码报障 → POST /incidents → 409 "troubleshooting miss-path Agent is disabled"
```

蓝图点名的第一个场景，**在默认可运行配置下报不进在线 lane**。
（冒烟脚本能跑无码路，是因为它走 `/sops/synthesis/*`——知识生产 lane，不需要 Agent。）

**这不是路由代码偷懒，是契约没给它留位置。** `Diagnosis` 只有两种合法形状：

| routeMode | investigationMode | routeAuthority | 前置 |
|---|---|---|---|
| `DETERMINISTIC` | `ERROR_CODE_PLAYBOOK` | EXPLICIT / RULE_MATCHED | **要求 errorCode** |
| `LLM_FALLBACK` | `OPEN_DISCOVERY` | `MODEL_PROPOSED` | **要求模型提议** |

**没有"路由未命中且没有模型参与"这个形状**，所以没有任何合法的 Diagnosis 可以落库，
只能 409。

fail-closed 本身是对的（A6：不完整比编造更好），报障人也确实会被告知——
5 次重试后走终态通知：「只读调查未能安全完成，系统已停止自动判断……
MateClaw 未执行任何生产变更」。所以链是完整且诚实的，**只是默认不产出结论**。

- [x] `FirstScenarioIntakeChainTest` 第三例钉住这个行为：拒绝必须**指名**是
      「这条路没开」，而不是抛一个泛化错误——操作员据此知道要改的是部署决策不是代码。
**方向 (c) 的承重部分已完成（2026-08-01）**：`ScenarioDiagnosisService` 从
`DeploymentTopologyScenarioDiagnosisService` 里抽出来了。此前这个能力是通用的
（`ScenarioDiagnosisDraft`、`initializeScenarioAwaitingEvidence`、
`createOrGetForScenario` 都以 scenarioKey 为参数），**只有入口被绑死在拓扑一个场景上**。
拓扑现在是调用方，只保留它自己那条专属检查（Playbook 必须要求 synthetic probe），
守住 A9。事务边界随通用半边一起移走，但拓扑通过**注入的 bean** 调用它，
Spring 代理照常生效——锁定权威版本与插入 Diagnosis 仍在同一事务内，测试已改为
钉住新的持有者。612 tests 全绿。

**因此 §5.5 的"契约缺口"结论要修正**：`DETERMINISTIC + SCENARIO_PLAYBOOK + EXPLICIT`
本来就是合法形状，不要 errorCode、不要模型。缺的从来不是契约，是入口。

**更正（同日，实跑后）**：下面这条我说过头了。场景入口**能接住**无码故障并落一份
合法诊断，但**没有任何东西去执行它的证据计划**——诊断停在 `NEEDS_INVESTIGATION`，
确认时被正确拒绝：`abstained diagnosis requires new evidence before confirmation`。
拓扑场景有专用探针端点把证据跑起来，无码场景没有对应件。冒烟第 10 道闸门已改名为
「在线 lane 能接住」并断言状态必须是 `NEEDS_INVESTIGATION`——
**一个说过头的绿灯比没有灯更危险。**

### T0.17 · 场景诊断的证据到达转移（**既有缺陷**，不是新引入的）

**查实后要把上一条的范围改大。** 这不只是"我新开的入口缺后半段"——

- `initializeScenarioAwaitingEvidence` 建出的诊断是 `abstained=true`（对的：
  指定场景是选证据计划，不是断言原因）；
- `confirm` 对任何 abstained 诊断一律拒绝，要求"新证据"（也是对的）；
- **而代码里没有任何路径会提供那份新证据。** `Diagnosis` 聚合的可变方法只有
  confirm / transfer / actions / outcomes / close，**没有"证据到达"这一个**；
  部署拓扑的探针写的是自己的运行表（V188），不写回 Diagnosis。

两半都对，合取的结果是：**每一条场景诊断都永久停在 `NEEDS_INVESTIGATION`，
无法确认、无法交接到关闭、无法关闭。这包括先于通用入口上线的部署拓扑场景。**

- [x] `DiagnosisStateMachineTest.aScenarioDiagnosisIsStuckUntilAnEvidenceArrivalTransitionExists`
      把当前行为钉住（不是让构建变红），并用反射断言聚合上还没有
      `evidenceRecorded` / `reevaluated`——**一旦有了，这条断言就该失败，那正是修好的信号**。
- [x] 给 `Diagnosis` 聚合加"证据到达"转移（`Diagnosis.evidenceRecorded` +
      `DiagnosisStateMachine.recordScenarioEvidence`）：命中则清 `abstained`、
      推进到 `READY_FOR_HUMAN`；排除则 `EXCLUDED`（也是结论，也推进）；仍不足则保持弃权。
      **用户已授权的 v4 §5.5 契约新增。**
- [x] A9：把命中路径的判据/规则求值与结论合成抽成 `PlaybookEvidenceAssessment`，
      `DeterministicDiagnosisService` 改为消费它，**两处求值合并为一处**。
      副作用：错误码路径的文案由 "SOP" 统一为 "Playbook"（无测试/夹具钉住旧文案）。
      注意一条被这次抽取补上的 A1 违规——引用清单原本会把 `MISSING` 的取证也列为结论依据，
      现已按 `status() != MISSING` 过滤：「我们查过」不等于「我们查到了」。
- [x] 拓扑那条路一并归位：**运行表保留**（它是工具运行的审计记录：原始观测、
      相邻链路、执行人、耗时，比 EvidenceResult 更宽，本就该独立），
      **缺的是写回**——`TopologyProbeEvidence` 把一次拨测翻译成 `EV-TOPOLOGY` 的
      EvidenceResult，run 与被重新裁决的 Diagnosis 在**同一把行锁、同一个事务**里提交。
      - 覆盖不完整且未见失败时**不给出** `failed_probe_count`：判据只能是"未求值"，
        不能是"已反证"。否则控制台会拿没人看过的节点去宣告"网络已排除"。
      - 已进入人工环节后重跑只记录运行、不改写结论，并在 `conclusionUpdated` 上如实说明。
- [x] 通用在线编排已补上：`ScenarioEvidenceRunService` +
      `POST /diagnoses/{id}/evidence-runs`。跑 Playbook 自己的 `evidenceRequests`
      （复用从 intake 抽出的 `PlaybookEvidenceCollector`，A9），
      按**冻结的那个版本**求值，再 `recordScenarioEvidence`。
      - 已进入人工环节的诊断**拒绝重跑**（409）而不是悄悄改写结论。
      - 必需证据属于资产工具（`target.assetType`，D18）的 Playbook 在此**拒绝执行**，
        而不是走 Router 伪造一条 MISSING——那等于对一个从未问过的来源说「查过了，没有」。
      - 端到端已实跑：`scripts/troubleshooting-scenario-evidence-smoke.sh` 七道闸门全绿，
        并已接入 CI workflow 与静态合同校验。
      - 教训记一笔：闸门 6 最初查 `citedEvidence`，真实字段是 `evidenceCitations`，
        查错字段返回空清单，于是「没有 MISSING 被引用」永远成立——**空转了一整轮**。
        现改为双向比对（引用必须恰好等于非 MISSING 的取证，空清单直接失败）。
        这是本轮第三次同型问题：闸门写错了对象，比没有闸门更糟。
- [x] 这条“此前状态”已结束：知识生产 lane 仍保留，但注册过的无码场景现在还可走
      `POST /scenarios/{scenarioKey}/diagnoses → POST /diagnoses/{id}/evidence-runs` 在线 lane；
      只有证据命中/反证后才进入 `READY_FOR_HUMAN`。未注册场景仍 fail closed，不能把一个场景的通过
      外推成任意无码现象都可在线诊断。

**(c) 已完成（2026-08-02）——显式场景入口与证据执行均已落地：**

- [x] `POST /scenarios/{scenarioKey}/diagnoses` 通用场景入口。语义按 §3.1 分开：
      人显式指定记 `EXPLICIT`；模型提议注册键必须走别的路并记 `MODEL_PROPOSED`，
      所以这个入口要求已认证操作员，不接受调用方自带 actor。
      请求体**故意没有 `errorCode` 字段**——这是无码故障的入口，
      允许传码等于开了一扇不检查错误码权威的门。
- [x] `csdp:scenario:message_send_failed` 的 SCENARIO Playbook + 录制种子，
      证据计划就是那三步脊柱；走 D19 的同一条晋升链（录制正例 + 服务端生成
      排除例/弃权例 → 回放 PASSED → 知识评审晋升）。
- [x] demo 种子扩到三条；无码路冒烟加第 10/11 道闸门。
- [x] `TroubleshootingRequestTimingFilter` 补上场景路径。此前它只认精确路径，
      带变量段的新入口拿不到 `reportedAt` 直接 500。新增的正则**刻意收紧**到
      前缀 + 单段 + `/diagnoses`：给一个非接入请求盖上到达时间戳，
      等于往北极星里塞一个编造的数。

实测：`DETERMINISTIC + SCENARIO_PLAYBOOK + EXPLICIT`，`errorCode=null`，
`INSUFFICIENT_EVIDENCE / NEEDS_INVESTIGATION`，绑定精确 approved 版本，零 LLM。

- [x] **决定已落地：采用方向 (c) 的显式注册场景变体。** 没让 `/incidents` 根据自由文本猜场景，
      而是增加需要认证操作员明确选择注册键的 `/scenarios/{scenarioKey}/diagnoses`：
      服务端锁定 active-approved Playbook，写入
      `DETERMINISTIC + SCENARIO_PLAYBOOK + EXPLICIT`，先停在
      `INSUFFICIENT_EVIDENCE / NEEDS_INVESTIGATION`；随后无请求体的 `evidence-runs` 只执行 Diagnosis
      已冻结版本中的取证计划并重新裁决。这样保留了 (c) 的零 LLM / 强权威，又不把自由文本路由猜测
      伪装成规则命中。
      - (a) 未采用：不新增 `routeAuthority=NONE` 弱形状，避免“未路由”成为绕过 Playbook 权威的入口；
      - (b) 仅保留为**未注册现象**的 fail-closed 边界，不能反过来关闭已经有 approved 场景的零 LLM 路；
      - 模型未来若提议同一注册键必须走独立入口并记 `MODEL_PROPOSED`，不得复用这个 `EXPLICIT` 入口。
- [x] 全程没有为了 demo 录制整个 Agent 回合：录制的是 server-owned 结构化证据与草稿响应，
      路由、冻结版本、判据求值、证据到达转移和确认仍由生产代码执行。

---

## 4. P1 · 无错误码证据→PlaybookDraft 竖线（已完成）

### T1 · PlaybookDraft 合同与结构化归纳

- [x] 新增 `PlaybookDraft` 值对象：generationKey、proposedType/selector、evidencePlan、criteria、
  diagnosisHypotheses、humanActions、evidenceCitations、modelProvenance、validationErrors。
- [x] 模型输入只包含已确认上下文和 `LogTraceSkeleton`，不含原始 EvidenceResult/DQL。
- [x] 复用 MateClaw 现有模型配置工厂和 Spring AI 1.1.8 `BeanOutputConverter`。
- [x] 一次结构化调用、低温、固定 token 上限；空响应、坏 JSON、provider 失败返回 rejected result。
- [x] 当前 `SopSynthesisPreview` 与 API 路径保持兼容，不做全仓改名。

完成标准：有效固定模型响应可生成 draft；模型未配置/失败时不创建 candidate，也不影响既有 preview。

### T2 · 确定性 PlaybookDraftValidator

- [x] selector/type/必填字段/长度/枚举/跨字段不变量校验。
- [x] evidence citation 必须属于本次 EvidenceBundle。
- [x] 拒绝 DQL、原始日志包、工具调用、生产写动作和未脱敏 secret。
- [x] 错误码候选不得由模型猜码进入 deterministic 权威；场景候选只能提议注册 selector。
- [x] 验证结果保存具体错误码和字段路径，供审核人理解，而不是只返回 false。

完成标准：伪造引用、危险动作、坏 selector、secret、DQL 均可被稳定拒绝并有测试。

### T3 · ReferenceSolution 比较与离线 Eval

- [x] 建会议正例 `会话消息发送失败` 的人工参考解法。
- [x] 参考解法结构：requiredStepIntents、forbiddenStepIntents、orderingConstraints、
  requiredEvidenceKinds。
- [x] 比较输出覆盖率、缺失步骤、顺序违规、引用缺口、危险动作，不做逐字相似度。
- [x] 至少加入一条负例，要求 abstain 或校验失败。
- [x] prompt/model/schema 变更必须跑固定 replay eval，并与上一次 baseline 比较。

完成标准：必需意图全覆盖、必要顺序满足、引用有效、禁止动作命中数为 0；差异逐项可解释。

### T4 · Candidate 幂等与不可晋升边界

- [x] `generationKey = hash(workspaceId, incident, bundle, modelConfigVersion, contractVersion)`。
- [x] 同一生成请求重试返回同一 candidate，不重复入库。
- [x] fixture 生成物始终保留 `fixtureMode=true`。
- [x] P1 只能创建 draft/candidate，不能写 active approved Playbook。
- [x] Outbox publication status 与 review status 分开；不复用 `PENDING/PUBLISHED` 表示审核。

完成标准：重复请求幂等；API/持久化往返不丢 fixture、引用和验证结果；任何接口都不能直升 approved。

### T4.5 · 成功样本对照与北极星时间戳（v4.1 新增）

来自第一性原理评价，用户已认可；论证见 `architecture-critique-v4.md` §2.3 / §2.4。

- [x] 合成流水线增加第 2.5 步 `contrast_sample`：同窗口同接口的**成功样本**对照。
- [x] `DeterministicLogTraceCompressor` 产出里带失败↔成功差异；模型看到的是差异，不是单条链路。
- [x] 对照取不到时**降级不失败**：草稿仍生成，标 `contrastAvailable=false`，
      并按 v4 §5.7 一律走校准期档，不得进入运行期晋升。
- [x] 记录四个北极星时间戳：`reportedAt` / `readyAt`（IntakeSession）、
      `conclusionAt` / `handoffAt`（Diagnosis）；abstain 也要写 `conclusionAt`。
- [x] 未发生的阶段保持 `null`，不得用 `0` 或当前时间填充。
- [x] 三段差值（补问成本 / 系统调查成本 / 人的采纳成本）分开统计，禁止只报总时长。

完成标准：对照命中与缺失两条路径都有测试；fixture 样本也能算出三段差值，
P2 拿到真实数据时有可比基线。

**为什么值得在 P1 就做**：对照是把"我们有全量日志"这个差异化兑现成**确定性判据**的最短路径
（"失败请求里 92% 有该特征、成功里 3% 有"不需要模型背书）；时间戳不在 P1 埋，
P2 就无法回答"到底省了多少人的时间"——而那是北极星本身。

### T5 · P1 测试清单

- [x] `PlaybookDraftInducerTest`：成功、空响应、坏 JSON、provider 失败、prompt injection。
- [x] `PlaybookDraftValidatorTest`：引用、selector、动作、DQL/raw log、跨字段不变量。
- [x] `ReferenceSolutionComparatorTest`：必需意图、顺序、禁止动作、证据类型、delta。
- [x] 扩展 `SopSynthesisServiceTest`：任一步失败不调用模型；成功只产 candidate。
- [x] 固定 Replay Eval：真实 Recorded Replay 组合固定模型正例 + 危险输出负例。
- [x] Candidate 集成测试：generationKey 幂等、不可直升 approved、fixture 标记保留。
- [x] 对照与时间戳测试：`contrastAvailable=false` 时降级不失败且锁定校准期档；
      四个时间戳往返不丢，未发生阶段保持 `null`。

### T5.5 · 正式 Evidence Spine 开发入口（2026-08-06 已并入诊断效果评估）

- [x] “诊断效果评估 → 历史样本回放”直接调用既有 synthesis preview API，不新增第二套取证实现。
- [x] 页面显式展示 `log_search → log_trace_bundle → contrast_sample`、PS ID 确定性调用链和
      失败/成功样本差异；对照缺失时保持不可用状态，不伪造测量值。
- [x] 该入口由服务端硬限制为 Recorded Replay，默认数据源仍关闭；不调用模型、不创建 candidate、
      不提供审核、晋升或生产写操作。
- [x] 独立“无码场景预演”菜单和规则库按钮已移除，用户名称改为“历史样本回放”；
      历史 `/troubleshooting/sops?focus=evidence-synthesis` 深链自动转入诊断效果评估并打开同一
      只读入口，不新建 API、Scenario 运行时或 candidate 通道（2026-08-06）。

## 5. P2 · 接真实 Guance 和影子评估

### T6 · Workspace→观测资产授权

- [x] 设计 workspace/system/service 到 Guance 资产与 binding 的显式授权关系。
- [x] 未授权必须 fail closed，不能回退默认全局 API key/measurement。
- [x] 密钥只来自运行时配置，不进领域表、日志、prompt 或页面。
- [x] 正式工作台按 workspace/system/service 展示秘密无关的真源就绪投影。
- [x] 正式工作台增加独立于当前 Diagnosis 的“P2 真源接入”向导：owner/admin 可选择安全的
      system/service，取得只含 workspace 精确 ID、核心 binding 占位符与密钥系统环境变量名的外部配置
      骨架，再复用既有 readiness/acceptance API 检查 T6→T7→T8 门禁。页面不接收 API Key、不把占位符
      写回服务端；只有 readiness 进入 `READY_FOR_VALIDATION` 或 `CANONICAL_SIGNALS_OBSERVED` 才可转入
      既有 T7 只读验收。向导作用域与当前 Diagnosis 的侧栏/T8 状态隔离（2026-07-30）。
      异步响应必须继续匹配对话框 session、发起 origin，以及 system/service/searchTerm/window/occurredAt
      完整 lookup identity；关闭后重开不得复用旧响应。
- [x] 增加管理员触发的 Guance-only `log_search → log_trace_bundle` 单次只读验证；
      在 Router 调用前限制允许源，不得回退 Recorded Replay。
- [x] 将部署拓扑拨测从独立分析能力归位为 `deployment_topology_probe` Diagnosis 场景：拓扑快照作为
      Workspace 资产，`topology_synthetic_probe` 作为只读 Tool，Guance CloudDial 作为首个 Adapter；
      选定资产和脱敏结果均写入同一 Diagnosis Evidence Spine，详情页可查看不可变运行历史（V188）。
- [x] “发起排障 → 部署拓扑拨测分析”已能先由服务端创建/复用专属场景 Diagnosis：
      浏览器只提交 system/service/现象/严重程度/可选 Trace，服务端固定
      `deployment_topology_probe` 并在同一事务内锁定精确 active-approved Playbook。冻结版本必须显式要求
      `assetType=deployment_topology + toolKey=topology_synthetic_probe`，否则 409 fail closed。新建 Diagnosis 保持
      `INSUFFICIENT_EVIDENCE / LOW / NEEDS_INVESTIGATION`，不在拨测前输出根因或处置建议。
      非演练重试使用包含 scenarioKey 的五分钟幂等命名空间，不会与普通无码事件或其他场景错误合并（2026-07-31）。

2026-07-29：`workspaceId` 已贯穿唯一 Evidence Router/Adapter 脊柱；Guance 只有命中唯一、精确的
`asset-bindings[workspaceId,system,service].signal-bindings[signalKind]` 后才会读取运行时 API Key 并发请求。
重复/缺失/大小写归一后歧义均在 transport 前返回 `MISSING`。这只完成授权机制，**不代表**
任何真实资产、measurement、字段或阈值已经通过 T7。

2026-07-30：首个试点已写入默认不激活的 `csp-clouddial-pilot` Profile，激活时必须提供精确授权
`${MATECLAW_TROUBLESHOOTING_CSP_WORKSPACE_ID} / csp-deployment / csp-prm-miniapp / synthetic_probe`，
并把 `D::http_dial_testing` 标准请求外层、任务名、列映射写入 Guance Adapter 与配置。开关、API Key 和
明文 HTTP 许可默认仍 fail closed；仅本地联调可在操作员明确授权后临时设置
`MATECLAW_TROUBLESHOOTING_GUANCE_ALLOW_INSECURE_HTTP=true`，正式部署仍禁止。正式工作台已增加管理员
“部署图拨测 SOP”入口与 `/api/v1/troubleshooting/sops/deployment-topology/analyze`：上传快照后解析所有节点，
只把同时具备 `url + guance_url` 的节点经既有 Router/Guance Adapter 批量查询。本次样例为 21 节点、
27 链路、1 个可执行拨测；其他 20 个节点保持未覆盖。入口最多接受 32 个可执行拨测，以 8 路并发共享
25 秒总预算，超时节点只记 `UNAVAILABLE` 并保留已完成结果。尚未由自动化发起携 Key 的真实 HTTP 请求。
单次验证报告只保留匹配数、PS ID、trace 节点数、证据引用和时间戳；不保留原始行、
查询文本或凭据。验证报告现同时返回每个 Guance 核心信号与端到端的应用侧 round-trip 耗时，
作为后续 T8“取证时延”的同口径输入；它不冒充 Guance 服务端 DQL 执行耗时，后者仍需 owner
在 T7 用真实返回字段或观测平台核实。正式工作台把 T6 授权、T7 真字段验收、
T8 20–30 条历史样本拆成三个明确门禁并给出下一步动作；单次成功仍只是 owner 执行 T7 的工具，
不是 T7 或 T8 完成证明。

2026-07-31：新增默认不激活的 `csdp-guance-evidence-pilot` Profile，精确授权
`workspace 1 / CSDP / csdp-session-service` 的 `log_search / log_trace_bundle / contrast_sample`。
三份合同已在真实 Guance `csp-rpc-msg` 数据上运行：失败搜索返回规范 PS ID，同 ID 链路返回
3 条原子 JSON 日志；独立的 `failed` 失败终态与带显式 `success` 标记的成功终态 cohort 使用同一
时间范围和单桶聚合，四项均按 `@trace_id` 去重，对照可复算。统一 `EvidenceSpineOrchestrator` 首次返回
`FULL_SPINE_OBSERVED`，三次真源请求均为 `CANONICAL_RESULT_OBSERVED`，无 Replay 回退。
早期同状态条件构造的对照结果已废弃；当前失败样本量仍小，只证明机制可运行，不代表判据已泛化。
这仍是一次不持久化预览：尚无 owner `ACCEPTED` 记录，尚未进入 T8 样本台账，
`fixtureMode` 不变；CloudDial `synthetic_probe` 仍待独立真源验收。

### T7 · 内网核实

- [x] 建立持久化、按当前 binding 配置指纹失效的 owner 验收接缝：只有 Workspace owner 明确核对
      measurement/字段、索引、同 PS ID、时间单位/窗口、DQL 延迟与 903001 冲突，且服务端再次跑通
      Guance-only 两步读链后，才保存 V184 秘密无关验收记录；Guance T8 采集与基线复跑都在 Router
      调用前强制校验当前指纹。该接缝不代表下面任何真实核实项已完成。
- [x] 在真实 CSDP SendMsg 数据上核实 `csp-rpc-msg`、原子 `message` JSON 内的
      `trace_id / level / msg`、毫秒时间戳类型和同 PS ID 一致性；JSON `source` 是源码位置，
      canonical `service` 改由服务端 binding 固定提供，并明确拒绝 trace 跨 series 序号拼接（2026-07-31）。
- [x] 将成功样本对照改为独立 `failed` / 显式 `success` 终态 cohort，为两边使用同一服务器时间范围、
      24 小时单桶 rollup，并按 `@trace_id` 去重；失败特征命中率在真实数据上严格高于成功样本
      （2026-07-31）。`success` 终态标记的业务语义仍纳入下面 owner T7 acceptance。
- [ ] 由 Workspace owner 完成索引、时间窗、DQL 延迟和旧 route 冲突复核，并对当前配置指纹提交
      `ACCEPTED`；提交前不得开放 T8 真源采样。
      **代码侧已就绪**（V184 验收接缝、正式工作台向导、`GET/POST
      /evidence/guance/acceptance`，清单见 runbook §6）。剩下的是**人和内网窗口**：
      端点在 `*.prd.sangfor.com`，需要 owner 本人和受控运行时 Key。这一项不会因为
      再写代码而前进。
- [x] 窗口预检 `scripts/troubleshooting-t7-preflight.sh`（2026-08-02）。
      §3 早就写过这条风险——「窗口拿到了也用不上，操作员卡在同样的配置迷宫里」，
      而窗口是整条路上**最贵、最难重来**的一格。预检只读，走 7 格：
      服务可达 → adapter 启用 → 三个核心 signal 已路由 → 指纹可唯一计算 →
      20–30 条服务端冻结目标 + 历史执行计划 → 验收状态 → 真源采样闸门；卡住时把
      **服务端自己的 blockers 原样打出来**，
      再给下一步动作。四条刻意的约束：
      - **只读，只发 GET**（登录除外）。一支能顺手把 Key 提交出去的预检比没有更糟；
        CI 合同静态断言这一点，并已用「故意加一条 POST」验证该断言是活的。
      - **绝不在夹具环境里报就绪。** 本机跑必须停在第 2 格，且真源采样必须是关着的。
      - **验收清单模板七项一律 false。** 那是 owner 的书面确认，预填 true 等于机器替人签字。
      - **没有 20–30 个 server-owned target 不报窗口就绪。** `GET
        /evidence/guance/recording-targets` 只投影运行服务中未录制且与当前三份 binding 精确相等的目标；
        每项从完整候选及选中请求派生 selector、candidate/request 双指纹、lookup 与 window，不接受自报哈希，
        也不返回候选正文、DQL/凭据。当前随仓目录为 0，
        因为唯一核实的 SendMsg 已录制，其他合同不能假造。
      - 操作员 `t7-recording-window-plan.v1` 每条只允许 `targetId / occurredAt / sourceReference`；
        未来时间、未知/重复目标、旧合同、额外字段、非法/超 128 KiB JSON 均阻断。文件先有界读取到
        mode-600 临时快照，校验与 SHA-256 只读该快照，原文件中途替换不会改变结果；服务端响应与计划
        在任何 `jq` 读取前先拒绝重复键和尾随根值。
      - 配套 `scripts/ci/test-troubleshooting-t7-preflight.sh` 用桩服务把
        2→7 格双向走通。**理由**：本机永远只能让它说"没就绪"，
        那条「就绪」路径本来会一次都没被走过就上线——真进内网那天，
        没人知道它会不会因为一个字段名写错而误报通过。已进 CI。
- [ ] 用当前受控运行时 API Key 跑通 CSP `synthetic_probe`，核对 `status_code/url/name`、时间排序和无数据语义；
      本地联调可按本次操作员明确授权临时开启 insecure HTTP，完成后立即关闭；正式部署仍须使用 HTTPS
      端点或受控 TLS 代理，不得提交 Key。
- [x] 用会议案例跑真实 `log_search → log_trace_bundle`，确认搜索和 3 条关联日志使用同一 PS ID。
- [ ] 核实 903001 的字段/阈值与三处历史 route key 冲突；这只阻塞错误码竖线，不阻塞 P1 fixture。
- [ ] 真实源未验收前 `fixtureMode` 不得改为 false。

### T8 · 历史样本与性能基线

**影子模式的前置件已补齐（2026-08-01）。** 底座此前能答「准不准」
（HELPFUL / UNHELPFUL / HARMFUL_BLOCKED / TECHNICAL_FAILURE + p50/p95 + token），
**但答不了「省不省时间」**——它量的全是机器耗时，样本上没有任何人工基线。
而北极星量的是人的时间。本文 §4.5 早就写过这句：「P2 就无法回答"到底省了多少
人的时间"——而那是北极星本身」。

- [x] `EvidenceEvaluationSample.HumanBaseline{minutesToLocate, basis, note}`，
      随人工 oracle 一起在 `PUT /{sampleId}/reference` 录入（可空：没有历史耗时
      来源的样本仍值得评分对错，只是不参与耗时对照）。
- [x] `basis` 分 `MEASURED`（工单/聊天时间戳读出）与 `ESTIMATED`（处置人回忆），
      **两者分开统计、绝不合并**——合并会让弱证据借走强证据的可信度，
      和 `EXCLUDED` / `UNEVALUATED` 不许混显是同一条纪律。
- [x] `NorthStarComparison` + `GET /evaluation-samples/north-star`：人工基线与机器
      耗时**并排列出**。
- [x] **刻意不产出「节省了多少分钟」**。影子跑出来的结论人还得读、还得核，
      那段成本就是北极星第三段 `adoptCost`，而影子模式按定义永远到不了那一步。
      发布一个悄悄漏掉采纳侧的节省数字，会朝着所有人都希望它倾斜的方向夸大结果。
      两个数并排给出，减法留给能看见少了什么的人做。测试用反射钉住：
      对外不得出现 `savedMinutes` / `savingsMinutes` / `timeSaved` 字段。
- [x] 每个 caveat 跟数字一起返回，而不是只写在设计文档里——
      只活在文档里的注意事项，不会跟着被引用的那个数字走。
- [x] 已关闭 Diagnosis 详情增加“纳入试点评估”入口，复用现有评估台账并显示四步下一动作；
      普通用户不会从配置页重新开始。
- [x] 修复 `EvidenceEvaluationSample.finalizeReference()` 丢弃 `humanBaseline` 的实现缺口；
      人工标准答案冻结后，耗时与来源强度一并进入不可变聚合 JSON。
- [x] `north-star` 只统计真实 Guance、非 fixture Diagnosis 及与这些样本 ID 匹配的机器运行；
      新的 Replay / fixture 参考冻结若携带人工耗时会 fail closed，历史记录即使存在也不会进入效果统计。
- [ ] 用第一张已关闭的真实 Diagnosis 形成“真源样本 + 人工标准答案 + 有依据人工耗时 + 影子运行”
      的完整效果记录；当前入口和度量装置完成不代表已有试点效果结论。



- [x] 正式工作台增加 Guance-only 的单条完整 Evidence Spine 预览：复用唯一
      `EvidenceSpineOrchestrator` 执行 `log_search → log_trace_bundle → contrast_sample →
      deterministic compress`，只返回调用链骨架、异常数、对照比率、证据引用和应用侧总耗时；
      不持久化原始日志、不调模型、不回退 Replay。
- [x] 在真实 Guance 环境运行首条不持久化预览：`log_search → log_trace_bundle →
      contrast_sample → deterministic compress` 返回 `FULL_SPINE_OBSERVED`，且
      `sourceRequestCount=3`（该项不等于 T8 台账已有 1 条样本）。
- [x] 建立管理员 T8 历史样本台账基础设施：采集时由服务端重新执行 Guance-only Evidence Spine，
      V181 只保存结构化计数、PS ID、调用链骨架、对照、耗时和固定证据引用；不接收浏览器预览、
      outcome、fixture 标记或审计 actor。人工参考步骤只接受结构化 intent key，关联 Diagnosis 必须
      已关闭，权威 outcome 与安全摘要由服务端读取并冻结；台账没有 `passed` 或 Gate verdict。
- [x] 共享 `EvidenceSpineOrchestrator` 记录三次 Router 往返和两次确定性压缩的应用侧墙钟时间；
      台账只纳入完整计时样本，以 nearest-rank 分别计算 Guance / Recorded Replay 的取证、压缩和
      端到端 p50/p95。V181 旧 JSON 缺少计时时兼容读取并排除统计；该指标不是 Guance DQL 时延，
      也不包含模型耗时或质量结论。
- [x] 建立 candidate-free 单 Agent 基线运行接缝：V182 样本冻结精确有界模型输入 SHA-256 与人工
      `DRAFT/ABSTAIN` 期望；运行时先原子占住样本+模型版本键，再按 Guance / Recorded Replay
      重放同一 lookup、核对输入指纹，并对固定模型配置只调用一次。结果只保存模型/组合时延、
      Token、Validator code 和逐样本结构比较分类，不保存草案正文、
      拒答正文、搜索键、原始证据、candidate 或 Gate verdict；正式台按 Guance/Recorded Replay
      分来源展示已有运行事实，但当前没有真实样本结果。
- [x] 关闭基线并发与复现缺口：模型版本使用 `model-config/v2` 覆盖并钉死 model + provider 配置快照；
      15 分钟 claim 每 4 分钟 CAS 续租，丢失所有权会中断当前有界外部调用，并在 persistence / evidence /
      model / complete 边界拒绝继续；ABSTAIN 校验完整 proposal，安全的协议残留或应弃权却生成安全草案
      归 `UNHELPFUL`，样本人工 reference 的 forbidden intents 进入 ValidationContext；只有危险原因、命中样本级
      禁止动作、越权引用等真实安全问题归 `HARMFUL_BLOCKED`。
- [x] V183 增加不可变采集修订：相同 capture identity 每次先重跑 Guance/Replay，同模型输入指纹复用
      最新 revision，证据漂移自动创建 `rN`，旧样本和人工参考解不覆盖；并发异指纹争用同一 revision 时
      核对数据库赢家指纹，不一致则基于最新 revision 有界重试，绝不误返回另一份输入。
- [x] Replay 采集按钮绑定服务端 capability：同时核对 fixture workspace/system/service scope、
      `log_search` / `log_trace_bundle` 路由、Adapter 支持与精确搜索样本；页面只提交 `diagnosisId`，
      服务端从 Diagnosis 与 `ApprovedEvidenceSpineCatalog` 唯一解析 scenario/search/window，浏览器提交目标字段
      直接返回 400；无码主案例不依赖 Guance 表单或错误码，默认关闭时明确禁用。Guance/Replay 基线按钮
      分别恢复各自来源的冻结 lookup context，不再用 Guance context 代跑 Replay 样本。
- [x] Guance 样本采集与基线复跑增加 T7 服务端门禁：只接受当前 workspace/system/service 的 V184
      owner acceptance，查询模板、字段映射、端点或路由配置变化后旧验收自动 `STALE`，任何真源请求前
      返回 409；Replay 仍保持独立 fixture capability，不受这条真源门禁混淆。
- [ ] 建 20–30 条历史样本，保留人工结论、参考步骤和 outcome。
      **卡在 T7 owner `ACCEPTED` 与真实历史故障上，不是代码。**
- [x] 统计口径补齐（2026-08-02）：耗时 p50/p95 早已有；**引用完整率、必需意图覆盖率、
      abstain 质量、危险提议此前只逐条存着、从来没有被汇总过**。
      `BaselineEvaluationLedger.CohortMetrics.QualityMetrics` 补上，仍按来源 ×
      真实/fixture Diagnosis 分栏，绝不混成一个数。三条刻意的取舍：
      - **只给计数与分母，不给率。** 2 分之 2 与 30 分之 30 渲染出来一模一样，
        而这批样本就是 20–30 条，正好是这个差别决定一切的量级。除法留给能看见分母的人。
      - **覆盖率给 p50 与最小值，不给 p95。** 它是下限条件；p95=1.0 与"有一条只覆盖 0.2"
        完全兼容，而那一条正是阈值存在的理由。
      - **`dangerFreeAcross(minimumRuns)` 把样本量写进签名。** 裸的
        `dangerousProposalRuns == 0` 会被空队列满足，而"0 条里没有危险提议"
        正是一次过早放权会呈现的样子。
      **在样本落地之前写**：定义若在数据摆在眼前之后才写，就是照着数据挑的，
      §5.7「退出条件是数据达标、不是排期到点」也就没有意义了。
- [x] 分开统计"没帮上忙"和"引向错误方向"：`HELPFUL / UNHELPFUL / HARMFUL_BLOCKED /
      TECHNICAL_FAILURE` 早已分栏，危险提议现在也有了汇总计数。
- [x] **「高置信错误为 0」已有可测口径**（2026-08-02 拍板）：T8 基线对模型自报置信度
      **不接收、不存储、不映射到 `SystemConfidence`，也不让其参与放权**。旧 miss-path
      `AgentTriageDraft.confidence` 只是被服务端降档的模型提议，不能进入本计数或 Gate。
      `BaselineEvaluationRun` 由服务端根据真 Guance、
      双非 fixture、`FULL_SPINE_OBSERVED`、确定性校验和引用完整性派生
      `HIGH / MEDIUM / NOT_ASSESSED`；冻结人工参考解在另一条轴上独立产生质量分类。
      `highConfidenceError = HIGH && classification != HELPFUL`，台账汇总已评估数、
      HIGH 数和 HIGH 错误数。`highConfidenceErrorFreeAcross(minimumHighConfidenceRuns)`
      要求显式非零 HIGH 分母，`0 / 0` 不能过门。详见 `system-confidence-contract.md`。
- [x] Recorded Replay 与真实 Guance 结果分组展示和统计，并在每个来源内继续分开
      真实 Diagnosis / fixture Diagnosis，禁止混成一个成功率。
- [ ] 在同一批样本上影子运行 Evidence Challenger + Safety Challenger，各一次调用、固定一轮。
- [ ] 与单 Agent/单次归纳基线比较引用完整率、弃权质量、危险动作拦截、p50/p95、token 和失败率。
- [ ] 无可复现质量收益或成本不可接受时，停止在影子模式，不进入在线或晋升 Gate。
- [ ] 在这批样本上确定 v4 §5.7 的**退出校准期阈值**（必需意图覆盖率、危险动作拦截率、
      高置信错误数为 0），并统计 §5.10 三段时间差；退出条件是数据达标，不是排期到点。

2026-07-29：上述完整预览与单 Agent 基线接缝已接入正式台账，当前完成的是**采集、冻结参考解、
可复现输入指纹、不可变采集修订、钉死 Provider 的单模型版本运行、分组计数与应用侧
取证/压缩/模型/总时延描述性统计能力**，
不是 20–30 条真实样本本身，更不是 T8 验收结论。`contrast_sample` 未绑定或不可用时仍保留核心
同 PS ID 链路，显式标记对照缺失并继续校准期；Guance 与 Recorded Replay、证据 fixture 与关联
Diagnosis fixture 分开记录。逐样本引用/意图覆盖、安全且证据落地的拒答原因、危险提议分类已经具备
结构化存储；Recorded Replay 采集和基线执行已接入。系统置信度计数只是让退出条件可测，
不是退出阈值或 Gate verdict。当前只有一次真源预览事实，没有 owner 验收后持久化的
T8 真实样本，仍不能产出质量结论；
Challenger 影子运行和两者对比仍未实现。
只有 owner 完成 T7、实际累积 20–30 条并跑完质量/完整性能统计后，
才能计算和评审整体 T8 基线。

## 6. P3 · 企业微信一线闭环

### T9 · IntakeSession（**扩平台现有企微通道，不新建入站**）

平台已自带 `vip.mate.channel.wecom.WeComChannelAdapter`（支持 proactiveSend 与交互卡片）
和 `WeComCardDispatcher` 多 kind 注册表。飞书排障 kind 只示范了卡片点击的前缀隔离；企微普通 @
消息不走 Dispatcher，而是在现有 Router 上接 pre-route handler。
详见架构 v4 §7.4 / D17。

- [x] 普通 @ 消息经平台现有 `WeComChannelAdapter → ChannelMessageRouter` 入站；Router 已增加
      显式开关的 `ChannelMessagePreRouteHandler`，已接管报障不再进 Trigger/通用 Agent，失败保守关闭。
      **不自建 webhook、不自建签名校验**——那是 Adapter 的职责。`WeComCardKind`
      只路由模板卡片点击，不再被当作普通消息 Intake 入口。
- [x] `conversationRef` / `reporterRef` / `sourceMessageId` / 附件引用取自 `ChannelMessage`
      （`chatId` / `senderId` / `messageId` / `contentParts`）与 `ChannelSessionStore`；Router 在 pre-route
      接管前保存带 channelId/targetId 的原通道路由，不新建会话表。raw `conversationRef` 保持业务身份稳定，
      单独持久化 `deliveryConversationId` 作为精确 ChannelSessionStore key，不用配置 ID 污染 routingKey。
- [x] `RECEIVED → AWAITING_INPUT → READY` 独立记在 `IntakeSession`；显式记录
      `reportedAt/readyAt`；补问往返沿用通道会话，
      **不塞进 `DiagnosisStateMachine`**。
- [x] 附件只保存受控 `storedName` 或消息级引用与元数据；不持久化本地路径/签名 URL，视频不做内容理解。
- [x] sourceMessageId 独立 receipt 表保证幂等；企微 `send_time` 经校验后作为事件时间，
      不可变 `reportedAt` 划分跨 Session 归属，`receivedAt <= lastMessageAt` 拒绝乱序覆盖；聚合版本检查 +
      active-key 唯一约束覆盖并发更新/创建冲突；锁覆盖完整事务边界，唯一键冲突回滚后只重试一次；
      READY 时原子释放 active key，稳定哈希 routing key 保留事件时间定位；迟到旧消息归入上一 Session，
      只有时间更晚的消息才创建新报障。
- [x] Intake 只将 `reporterRef` 当作不可信通道身份，未绑定仍可报障/补充，但不得用它
      审核或推进受审计状态。将来增加此类操作时，必须复用 `ExternalIdentityEntity`
      映射 workspace 主体，未绑定即拒绝该操作。

### T10 · 原路回复与关闭通知

- [x] 回调线程只提交 Intake + PENDING 调查任务并立即回复“已收到/还缺什么”；完整调查由数据库租约
      worker 异步执行，不让群消息等待取证或模型。真实企微 2 秒 p95 仍需上线后观测。
- [x] READY 与调查任务同事务提交；worker 带 120 秒租约、最多 5 次常规处理，启动时补齐历史 READY
      缺失任务。`source_intake_session_id` 唯一约束保证同一 Intake 只创建/复用一个 Diagnosis；通知失败
      不重跑调查。常规预算耗尽后进入持久终态投递并持续退避重试；先按 Intake 回查 Diagnosis，存在则继续
      投递摘要，确实不存在才投递明确 fail-closed 文本。
- [x] 业务摘要来自同一 Diagnosis 的 `BusinessSummary` 类型化投影，由通道交付 renderer 生成纯文本；
      首行保留 `conclusionType + confidence`，能力边界和 fixture 标记不截断。
- [x] 调查完成后经 `ChannelSessionStore → ChannelManager.sendToWorkspaceConversation → proactiveSend`
      原路返回，附 `/troubleshooting?diagnosisId=...` 正式工作台深链。只有 workspace/type/enabled 匹配且本节点
      持有 active leader Adapter 时才认领；精确路由缓存 miss 回源 DB，follower 不烧任务，平台 ACK 后才完成。
- [x] 关闭且 outcome 已登记后原路 @ 原报障人：Diagnosis 关闭更新与 V180 通知状态在同一事务边界提交；
      120 秒租约 worker 只在本节点持有精确 workspace 路由时认领，用
      `ChannelAdapter.proactiveSend(targetId, content, DeliveryOptions)` 发送纯文本最终结果与正式页深链。
      企微仅对安全 reporter ID 生成 `<@userid>`，平台 ACK 后才完成；失败持久退避且无硬重试上限。
      群聊还必须持有当前入站 reply context；重启后没有 `req_id` 时不认领、不回落
      `aibot_send_msg`。结案摘要入库前限制 500 字并拒绝凭据/DQL/原始日志/伪造 mention，出站文本
      继续做脱敏、mention 转义与 1800 字硬预算。
      非 Intake 来源的 Web/API Diagnosis 明确为 `NOT_APPLICABLE`，不伪造原路。
- [x] 未映射为可信 workspace 主体时，只允许报障/补充与接收只读摘要；本轮未增加任何通道审核、
      确认、关闭或其他受审计状态推进入口。
- [ ] **出站交互卡片先不做**：`WeComCardRenderer` / `FeishuCardRenderer` 的签名都是
      `render(ApprovalNotice)`（tool-guard 形状），且"批准=回放执行"与排障"确认=只推进状态"
      语义相反。**严禁把 `BusinessSummary` 适配成 `ApprovalNotice`**——先泛化平台接缝（单独评审），
      在此之前 IM 出站只发纯文本摘要。

## 6.5 T10.5 · 收敛 `RouteMode`（不要无限期停在中间态）

**现状（2026-08-03 复核）**：`Diagnosis` 里三个字段仍并存，但 v4 的两个正交字段已经成为当前
服务端与前端的读取权威。V191 将真实持久化的 `investigationMode / routeAuthority` 写入可索引列，
1.3/1.4 历史行保持空值并投影为 `LEGACY_DERIVED`，没有从 `routeMode` 猜测回填：

```java
initializeDeterministic        -> DETERMINISTIC + ERROR_CODE_PLAYBOOK + EXPLICIT
initializeScenarioAwaitingEvidence -> DETERMINISTIC + SCENARIO_PLAYBOOK + EXPLICIT
initialAgentFallback           -> LLM_FALLBACK + OPEN_DISCOVERY + MODEL_PROPOSED
```

`defaultInvestigationMode(routeMode)` / `defaultRouteAuthority(routeMode)` 只保留在 `Diagnosis` 的旧签名兼容构造，
用于读取没有新字段的 1.3/1.4 历史合同。服务端投影、生命周期不变量、索引列表筛选和前端
`DerivationChain.vue` 均已改读 v4 字段；列表会明确显示“旧合同推导”，不会把兼容值混入持久化统计。
真正尚未收敛的是生产侧还没有同时产生并统计 `RULE_MATCHED` 与 `MODEL_PROPOSED` 两类场景来源，
因此 `RouteMode` 暂时仍保留为兼容字段，尚不能完成最终 deprecated-for-read 宣告。

**为什么必须收敛**：D3 的原意是把"怎么查"和"为什么选中"拆成两个**独立**维度。
当前下游已消费两个 v4 维度，也能区分人工显式场景 `EXPLICIT` 与 Agent miss-path 的
`MODEL_PROPOSED`；但还没有生产 `RULE_MATCHED` 场景的入口，因此尚不能在同一批真实样本中分别统计
规则命中与模型提议。等 P4 的场景 Playbook 落地并同时出现两类来源时，统计必须继续读取
`routeAuthority`，不能退回 `routeMode` 把两类权威重新压扁，也不能让历史推导值与真实持久化值混显。

v4 §10 允许这个兼容中间态，但它是迁移的一站，不是终点。

**收敛步骤**（建议随 P4 T11 一起做，不单独排期）：

- [x] 当前诊断工厂已**显式**写入 `investigationMode` + `routeAuthority`：错误码、显式场景与 Agent fallback
      分别写入上表三种组合；`defaultXxx(routeMode)` 只保留在旧签名兼容读取路径，不再服务当前新建聚合。
- [ ] 新增的场景路径按真实来源写 `RULE_MATCHED` / `MODEL_PROPOSED`；两者必须能在数据上分开统计。
- [x] 下游判断（服务端 + `DerivationChain.vue` + 列表筛选）改读 `investigationMode`，
      `routeMode` 退化为纯持久化兼容字段。
- [x] 历史记录**不回填猜测值**：1.3/1.4 旧行保持由 `routeMode` 推导，并在投影上可辨识，
      不能让"推导来的"和"真实写入的"混在一张统计表里。
- [ ] 收敛完成后，`RouteMode` 在契约文档里标注为 deprecated-for-read。

**读取迁移完成标准已满足**：服务端与前端业务判断中的 `routeMode` 读取为 0，聚焦后端 79 项、
前端 24 文件 / 183 项、生产构建和 Scenario Smoke 合同均通过。T10.5 的最终完成标准仍是：
`RULE_MATCHED` 与 `MODEL_PROPOSED` 能在同一批真实样本上分别统计出条数，再标记
`RouteMode` deprecated-for-read。

---

## 7. P4 · 场景 Playbook 与开放探索

### T11 · Scenario Playbook

- [x] 先为会议正例 `message_send_failed` 建配置型 approved Evidence Spine 目录：模型只提交 workspace/system
      可见 `scenario_key`，搜索词、窗口、平台白名单和三阶段 request ID 全由服务端解析；当前平台固定
      `recorded-replay`。这只锁定 Planning 安全边界，不等于完整持久化 Registry 已完成（2026-07-29）。
- [ ] 先做 `slow_interface`、`system_unavailable`，再考虑更多场景。
- [ ] approved Scenario Playbook 拥有固定 EvidencePlan、ParameterBindingSpec、criteria 和输出策略。
- [ ] 模型只产 `ScenarioProposal(scenarioKey, parameterCandidates, reason, confidence)`。
- [ ] key 必须来自注册表，参数只绑定已确认字段/本次证据；模型不得产 DQL、EvidenceRequest 或工具名。
- [ ] `MODEL_PROPOSED` 结论最高 MEDIUM；显式/规则命中与模型提议分开统计。

### T12 · DiscoveryPolicy

- [x] 先把现有受限 miss-path 的**真实运行边界**持久化：V197 + V198
      `OpenDiscoveryRunAudit` 只记录 workspace 可见/实际选中的 approved scenario key、三类计划信号、
      精确计划 SHA-256 指纹、Agent 实际迭代上限、证据/时长上限、发出前记账的实际源请求数、
      安全证据引用、时间和类型化 stopReason。V198 又在 Agent/取证前原子 claim Web 去重键，
      超时/取消后禁止续查下一阶段，并在 Diagnosis + audit 短事务中完成 claim。
      它不保存 prompt、模型输出、DQL、日志或 observed，也不代表 DiscoveryPolicy /
      多轮 Loop Controller 已完成（2026-08-12）。
- [ ] OPEN_DISCOVERY 不注册成 Playbook，不拥有 selector/已批准根因。
- [ ] Policy 只限定 allowedSignalKinds、证据调用次数、迭代、上下文和置信上限。
- [ ] 继续只暴露唯一只读证据工具；不得因新增场景而扩大 Agent 工具面。
- [ ] 引入 `LoopPolicy / LoopRun / LoopOutcome`，统一迭代、证据、模型、时长、上下文预算和 stopReason。
- [ ] ERROR_CODE 路固定一轮且零 LLM；只有 SCENARIO / OPEN_DISCOVERY 可在预算内继续取证。
- [ ] Agent 不得递归创建 Agent、延长预算或直接从 Challenger 请求证据源。

### T13 · Impact 与排除结论

- [x] `IncidentImpact` 增加功能范围、可空人数、BlastRadius、evidenceRefs、observedAt；Diagnosis 1.6
      兼容 1.3–1.5 字符串影响。正式投影仅接受能由本次非缺失 `incident_impact` canonical evidence
      逐项复算的精确事实；精确人数强制带观测时间，所有引用必须通过 schema 且彼此无矛盾。Intake 在
      路由、取证和持久化前统一脱敏影响文本。真 Guance 产出仍属 T7/T15 未完成项。
- [x] 未知人数保持 null/UNKNOWN，不用 0 冒充已测量；正式前端仅在人数非空时渲染数字。
- [x] `EXCLUDED` 与 `UNEVALUATED` 分开；“平台侧未见异常”不能写成“已定位客户网络问题”。

## 8. P5 · 知识治理

### T14 · Review status 与版本替换

- [x] 正式 `/troubleshooting/sops` 增加统一 Knowledge Review Inbox；服务端按 workspace
      同时读取 `EVIDENCE_DERIVED` PlaybookKnowledgeRecord、`OUTCOME_BACKED` 关闭候选与
      `MANUAL` 注册候选，页面展示来源、审核/校验状态、晋升资格、缺失条件、证据引用、模型与参考解法。
      未开始独立审核时统一投影为 `CANDIDATE/v0`；每条来源的当前资格由服务端返回，人工候选执行
      完整合同交叉引用校验，关闭候选则显式保留当前合同无法证明的 outcome/恢复验证缺口，
      不得由前端猜成已校验。旧式 candidate → approved 按钮已撤下。
- [x] 旧 `POST /sops/{system}/{errorCode}/status` 已拒绝 `candidate → approved`；V186 版本化
      Playbook 不能从通用状态接口退役：有 review 的版本必须携精确 review version 与 reason，迁移生成的
      LEGACY 权威必须携精确 playbookVersion，并统一记录服务端 actor/reason/退役时间（2026-07-30）。
- [x] 新建/扩展审核状态：DRAFT → CANDIDATE → IN_REVIEW → APPROVED/REJECTED → DEPRECATED。
  - [x] H2/MySQL/Kingbase V185 独立审核台账；无记录为 `CANDIDATE/v0`，可开始为
        `IN_REVIEW/v1`，可按精确版本拒绝为 `REJECTED/v2`。重试幂等，并发旧版本 409；
        审核人只从登录主体取得，reason 禁止凭据、DQL、原始日志和堆栈。
  - [x] V186 开放服务端门禁的 `APPROVED / DEPRECATED`：批准前重读当前资格与 server-owned
        routeable material，退役只作用于该审核创建且仍占有 selector 的 active 版本；重试幂等，旧版本 409。
        V185 已处于 `IN_REVIEW` 的记录在迁移时冻结当时 active baseline；不同 `sopId` 的不可变 MANUAL
        source 可共享 selector，避免首版终态后无法创建替代候选。
- [ ] EVIDENCE_DERIVED / OUTCOME_BACKED / MANUAL 分别按 v4 的最低证据计算晋升资格。
  - [x] 当前来源事实已由统一服务端策略计算并随 Inbox 返回：证据型显式处于默认
        `CALIBRATION` 档并核对 validation/reference/citation/fixture，不把 candidate 生成当正例回放；
        人工型核对 owner 与证据请求→判据→规则交叉引用；关闭型不再用
        “尚未实现”占位，而是逐项列出可证明事实与缺口；前端只消费该投影，缺失时 fail closed（2026-07-29）。
  - [x] Diagnosis 1.7 在确定性命中时冻结来源 Playbook owner（与后续人工 `routeToTeam` 分离）；
        `knowledge-candidate.v2` 在同一个关闭事务中冻结 outcome、恢复验证、actor 与时间。新关闭候选可由服务端
        消除 `OUTCOME_VERIFICATION_NOT_PROJECTED / OWNER_REQUIRED`，历史 v1 行继续 fail closed。候选合同仅接受
        v1/v2：v1 不得携带 proof/owner，v2 缺少服务端关闭 proof 直接拒绝（2026-07-30）。
  - [x] Diagnosis 1.8 在确定性落库前从 V186 版本库复核并冻结精确
        `playbookId + playbookVersion`；复核 active-approved 行时持有锁并与 Diagnosis 插入保持同一事务，
        判定链只从该不可变版本重建。新合同缺少引用直接拒绝，
        1.3–1.7 旧行继续可读但重建时 fail closed，正式开发证据台显示精确版本或“历史未冻结”，
        不用当前 active Playbook 冒充历史知识（2026-07-30）。
  - [x] `MANUAL` 首个精确候选 Gate 已接入部署拓扑场景：服务端托管固定正例、健康反例和缺证据弃权例，
        对完整候选执行零 LLM 确定性回放；V189 只持久化计数、失败码、执行主体/时间以及候选和套件双
        SHA-256，不保存 fixture 事实、查询或原始响应。证明只对精确候选与精确套件有效，合同或套件变化
        自动失效；通过后仍须人工开始审阅并批准，浏览器不能提交 fixture、预期答案或证明（2026-07-31）。
  - [ ] 将 `EVIDENCE_DERIVED / OUTCOME_BACKED` 的真实 T8 正例、负例或弃权回放接入各自的精确候选 Gate；
        selector 单 active-approved 已由 V186 数据库唯一约束关闭，以 ≥20 条样本和高置信错误数为 0 驱动
        `CALIBRATION ↔ RUNTIME` 切换。该样本门禁不适用于不分阶段的 `MANUAL`，固定 fixture 回放通过也
        不代表 T7/T8 真源验收完成。
- [x] 审核记录 reviewer、reason，并在开始审阅时冻结 validation summary、
      reference comparison、模型版本、fixture 与当时的资格缺口（2026-07-29）。
- [x] approved 永远创建新版本；审核开始冻结旧权威 baseline，批准时乐观校验，V186 以 nullable
      `active_selector_key` 数据库唯一约束防并发双权威；替代或显式退役同时把旧 review 置为
      `DEPRECATED`。确定性命中只读取 operational 权威；最新版本已退役时直接 route miss，绝不回落
      复活 legacy 行；治理详情仍可读取最新历史版本（2026-07-30）。
- [ ] 定义 `AdversarialEvalReport`：反证、缺证据、危险动作、权威违规、未解决分歧和成本。
- [ ] Challenger 首期只读冻结 EvidenceBundle；缺证据只返回 EvidenceGap，由 Loop Control 决定是否补证。
- [ ] P2 影子评测达标后才允许 `PROMOTION_GATE`；报告不可用不得默认通过。
- [ ] 确定性 Gate 与人工审核裁决；Agent 共识或票数永远不是批准条件。

### T15 · 双投影吸收

- [x] 用户选定 Demo 信息结构后，提炼 `BusinessSummary` 与 `DeveloperEvidenceView`。
- [x] 服务经理默认只看问题、影响、结论/下一步、状态；开发证据默认折叠。
- [x] 不展示模型私有思维链，只展示证据、判据和可复算推导。
- [x] 页面不自行推断影响或结论；所有事实来自后端投影。
- [x] `DeveloperEvidenceView` 已新增不可变 `InvestigationTraceView`，固定投影七阶段、
      证据合同、已持久化的适配器最终结果、实测耗时、停止原因与证据引用；
      历史未持久化的候选/重试/逐次耗时明确为「未记录」（2026-08-03）。
- [x] 正式开发证据台已增加「证据关系视图」，只按服务端持久化的
      `Evidence → Criterion → Rule → Conclusion`关系从结论反查；断链明示「未记录」，
      不由前端推断补齐（2026-08-03）。
- [x] 正式 `/troubleshooting` 已吸收双投影并读取真实 API；旧处置台临时保留在
      `/troubleshooting/legacy`，跳转携带同一个 `diagnosisId`（2026-07-29）。
- [x] 正式工作台已提供受 `operate:troubleshooting` 保护的 Web 事件上报入口，复用既有
      `POST /api/v1/troubleshooting/incidents` 与唯一 Diagnosis 主链，不新建第二套 Intake。浏览器只提交
      system/service/现象/严重级别及可选错误码、Trace 安全标识，默认演练；不接受原始日志、DQL、凭据、
      影响人数、调用方 evidence 或自定义 incidentId；服务端 Intake 在路由、持久化或模型前再次拒绝 Incident
      字段中的 DQL、原始日志和堆栈正文。错误码优先走零 LLM Playbook；未命中路径未启用时明确
      fail closed。非演练事件统一服从五分钟幂等：错误码事件按 route，无码事件按规范化 system/service/
      symptom/trace 生成稳定键（2026-07-29）。
- [x] `Diagnosis` 1.5 已持久化 `investigationMode` / `routeAuthority` / `conclusionType`
      与 D14 四时间戳；定位、排除、假设、弃权不再由前端或投影根据 `routeMode` 猜测。
- [x] `reportedAt` 由 Servlet Filter 在请求映射前捕获；Duration 以 ISO-8601 输出；首次人工确认补写
      `handoffAt/adoptCost`，登录态浏览器已完成 Diagnosis 1.5 创建与确认验收。
- [x] 判据字段缺失、类型错误或不可解析时保持 `UNEVALUATED`；只有完整可求值且为假的判据才能形成
      `EXCLUDED`，避免把“没取到”升级成“已排除”。
- [x] 双投影已直接复用 Diagnosis 内既有 canonical evidence：`log_count` 只作为带引用的事件量，
      不冒充客户/用户数；`trace` 只显示“部分异常 hop”；`log_trace_bundle + contrast_sample`
      可确定性压缩为有界调用链和失败/成功样本对照。未新增表、接口或第二套证据结构。
- [x] 聚合持久化的 Long→String 精度保护已纳入投影边界：只接受 canonical 十进制整数表示，
      完整链路、对照计数和事件量往返后仍可复算；宽松数值强转继续 fail closed。
- [x] `IncidentImpact` 已进入 Diagnosis 1.6 / Intake / HTTP / 投影合同；精确人数、BlastRadius、
      observedAt 必须由安全 evidenceRefs 指向的本次 canonical `incident_impact` 证据逐项复算，
      每条引用都必须通过 schema，任一引用混入非影响证据或彼此不一致都返回 null/UNKNOWN，不把任意
      日志量或前端输入冒充人数。
- [x] 在线 Diagnosis 的安全 `log_search` 已由服务端固定展开为
      `log_search → log_trace_bundle → contrast_sample`，与 `SopSynthesisService` 复用唯一
      `EvidenceSpineOrchestrator`。Agent 只提交注册 `scenario_key`；搜索词、窗口和平台白名单来自 approved
      服务端配置。三次 Router 调用先整体预留预算；预检失败粘滞并强制 abstain。完整 canonical evidence
      保存进同一个 Diagnosis，supplied evidence 与工具响应共用模型安全投影，不含 query、原始 `entries`
      或日志正文。核心 trace 缺失由服务端强制 abstain；对照缺失保存为显式 `MISSING` 并在正式页显示
      “已采集但来源不可用”，不冒充正常基线或“尚未保存”（2026-07-29）。
- [ ] 真 Guance 仍需稳定产出经核实的 `incident_impact` 人数/BlastRadius；旧记录或缺失事实继续返回
      null/UNKNOWN。1.3/1.4 的 D14 也不回填伪数据。

## 9. 明确不进入当前计划

- 自动重启、扩容、切流、改配置、改数据、改代码。
- 自动给代码补 error code 或自动提 PR。
- 把错误码确定性命中路交给 LLM/Workflow。
- 把 Wiki、Memory、Skill 或聊天记录当成诊断权威。
- `CODE_BUG / DATA_FIX / BUSINESS_OPERATION / EXTERNAL_CLIENT / INFRASTRUCTURE` 五类 FaultClass。
  录音只支持“代码类只定位、数据/业务类可给人工建议”两种能力边界；五分类需样本证明后另行设计。
- 继续堆 dev-only 原型；信息结构已选定，后续产品增量只进入正式工作台。

## 10. 工程约定与验证命令

### 10.0 分层跑测试（实测数据，2026-08-01）

| 层 | 范围 | 耗时 | 什么时候用 |
|---|---|---|---|
| `scenario` | `intake` + `synthesis`，147 tests | **13s** | 改第一个场景那条链 |
| `domain` | `vip.mate.troubleshooting.**`，612 tests | **67s** | 提交前（默认） |
| `all` | 整个 module，741 tests | **>10min** | 动了共享契约/域外代码 |

```bash
./scripts/troubleshooting-test.sh scenario   # 13s
./scripts/troubleshooting-test.sh            # domain，67s
./scripts/troubleshooting-test.sh all        # 慢，别默认用
```

**关键数字是第三行**：多 129 个测试，时间多 10 倍以上。贵的不是测试数量，
是域外那几个重上下文的类。所以"每次都跑全量"不是严谨，是给反馈加 10 分钟的税，
而且大部分在重复验证这次改动根本没碰的代码。

**但分层是一个关于影响面的断言，断错了是静默的。** 动到 `Diagnosis`、
`EvidenceResult`、Flyway 迁移，或 `vip.mate.common` / `vip.mate.channel` 时，
domain 层可以全绿而仓库是坏的——那时要跑 `all`，或者交给 CI。



- 后端测试：JUnit 5 + Mockito + AssertJ。

  ```bash
  mvn -pl mateclaw-server -am \
    -Dtest='vip.mate.troubleshooting.**.*Test' \
    -Dsurefire.failIfNoSpecifiedTests=false test
  ```

- 前端类型检查：

  ```bash
  cd mateclaw-ui
  node --max-old-space-size=6144 ./node_modules/vue-tsc/bin/vue-tsc.js --noEmit
  ```

- 前端直接构建：

  ```bash
  node --max-old-space-size=6144 ./node_modules/vite/bin/vite.js build
  ```

  `../scripts/check-snowflake-precision.sh` 已补齐；当前 `npm run build` 会依次执行 Snowflake ID 精度检查、
  `vue-tsc --noEmit` 和 Vite 生产构建，三段均通过后才算前端构建成功。

- 新增表或列必须同时更新 MySQL、H2、Kingbase 三份 Flyway 迁移。
- 不擅自开 PR；源表 xlsx 含真实 token/IP/人名，未入库且不得入库。

## 11. 推荐接手顺序

0. **先跑一次**：`docs/intelligent-troubleshooting/quickstart.md`。
   在读任何设计文档之前先看见一份真实的诊断——T0.65 的教训是，
   写完不等于跑通，而"跑不通"在没有脚本之前只是一种感受。
1. 先读现行录音基线、v4.5、HANDOFF 和本清单；正式 `/troubleshooting` 是实现权威，不再以 Demo 反推产品。
2. 主攻 P2 真实 Guance measurement/字段/阈值核实和 20–30 条影子样本；当前 CloudDial 请求已到达真源，
   但样例查询尚未返回 series，必须保持 `INSUFFICIENT_EVIDENCE`，不得伪造健康结论。
3. 沿同一 Evidence Spine 补结构化影响、完整 hop 与成功样本对照，不另建一套数据。
4. P3 纯文本闭环已收口；交互卡片仍是需单独平台评审的后续项，不阻塞 P2 真实数据验证。
   不新建入站，不把 `BusinessSummary` 伪装成 tool-guard `ApprovalNotice`。
5. 有真实样本和时延数据后再做 P4 场景路由；不要先搭空的通用 Planning 框架。
6. P2 影子评测证明收益后，再把固定 Challenger 报告接入 P5；知识审核状态和版本替换须在 candidate 真实积累前完成。

## 12. 真实 Guance 持久化竖线与瘦身收口（2026-08-04）

### 12.1 已完成

- [x] 会话消息发送失败场景的正式 `evidence-runs` 不再走只存在于验收弹窗的预览链：
      `ScenarioEvidenceRunService` 在冻结 Playbook 精确包含
      `log_search / log_trace_bundle / contrast_sample` 时，直接复用唯一
      `EvidenceSpineOrchestrator`，并把 canonical evidence 保存回同一 Diagnosis。
      浏览器不能选择 Guance 或 Replay，也不能提供 PS ID；
      `log_search` 真实观测到的 PS ID 才能触发后续链路和样本对照。
- [x] 场景开案新增可选“故障发生时间”；不选时由服务端以当前时间兜底。
      查询窗口围绕该时间展开，不再要求用户为默认情况手工输入。
- [x] 新建 Diagnosis `diag-655d4fde29c746d2abbe22b24dba02bf` 已经由正式入口到达真实
      Guance endpoint 并获得 HTTP 200。本次窗口内返回未满足 canonical scalar
      合同，因而只持久化 `MISSING`，保持 `NEEDS_INVESTIGATION`，没有伪造 PS ID，
      也没有回退 Recorded Replay。这证明了真源边界与 fail-closed，
      **不等于持久化三段已成功**。
- [x] 证据来源文案改为读取已持久化 evidence 行：区分 Guance、Recorded Replay、
      “已取证但无可用样本”与“尚未取证”。全 `MISSING` 不再因保守
      `fixtureMode=true` 被误标为 Replay。
- [x] 完成一次只限智能排障域的编译级引用审计：清理了已拆入子组件后遗留的
      无效导入、计算属性、公开 Store 字段和从未读取的会话参数。
      使用 `noUnusedLocals/noUnusedParameters` 复查后，Troubleshooting 域无剩余报告。
      dev-only 原型、Recorded Replay 评测、T7/T8 治理和历史文档仍有入口或合同作用，
      本轮不删除。
- [x] Java 21 后端排障域 786 项、前端 27 文件 / 201 项、精度守卫、类型检查和
      生产构建通过。浏览器实测全 `MISSING` 时页面无 Replay 文案、0 error；
      `InvestigationProvenancePanel` 遗漏的 `v-loading` 注册已修复。

### 12.2 当前唯一外部输入

- [ ] 提供一个仍在 Guance 保留期内的真实 SendMsg 失败时间，或明确授权在测试环境
      触发一次失败并记录精确时间。在没有这个输入之前，不用推测时间、放宽 DQL
      或回放样本冒充真实持久化 `FULL_SPINE_OBSERVED`。

### 12.3 下一阶段，按第一性原理排序

1. 用精确失败时间跑出同一 Diagnosis 内的三段 Guance canonical evidence，
   验证实测 PS ID 贯穿失败检索、链路查询和成功/失败对照，且全程无 Replay。
2. 由 owner 核对 measurement、字段映射、索引、时间单位、PS ID join 和窗口语义；
   这才是 T7 查询合同验收，HTTP 200 本身不是验收。
3. 固结第一批真实成功/失败样本，校准“成功样本对照”是否真能提供区分力，
   再讨论 T8 阈值与 20–30 条样本台账。
4. 竖线连续可复现后，再建立“系统 → 模块 → 调查场景 → 证据合同 → 只读适配器”目录，
   逐个扩展拨测、日志、Trace 和服务状态；不先搭一个空泛化平台。
5. 等正式工作台和真实样本完全替代设计对照价值后，再做第二轮瘦身；
   届时才评估开发原型和历史静态 Demo 是否可归档。

## 13. 系统观测资产注册表（2026-08-04）

- [x] 用 V194 三方言迁移保存 Workspace 系统观测资产的不可变版本；精确作用域为
      `workspace + system + service`，记录环境/区域/集群/Namespace、合同引用、变更人和原因，
      不记录凭据、端点、DQL 或原始证据。
- [x] 增加 viewer 资产目录与 admin 版本声明 API；启用资产必须绑定已审核且 signal 一致的合同，
      通过 `expectedVersion` 防止并发覆盖；顶层范围与同名查询参数不得分叉，文本字段不得夹带凭据。
- [x] Guance 运行时优先读取 Workspace 资产；不存在才回落部署 YAML。停用声明也遮蔽回落；
      资产所有参数由服务端固定，排障方案不能把 `monitor_checker / deployment / namespace`
      改成其他资源。
- [x] “取证查询目录”二级菜单增加“系统观测资产”工作区，支持新增、接管部署默认和追加版本；
      表单只展示脱敏合同选项及安全资源标识，不猜测或预填生产环境。
- [x] Guance binding fingerprint v2 纳入实际生效的 Workspace 资产版本、合同和参数；
      资产任一变更都会使旧 T7 owner 验收失效，防止 T8 沿用旧验收查询新资源。
- [x] 回归通过：后端排障域 + Skill Manifest `823/823`，前端 Vitest `211/211`，ESLint、
      `vue-tsc --noEmit` 与生产 Vite build 通过。
- [x] 用户已显式确认 `prd`，并登记 CSDP 第一份 SendMsg 核心 Workspace 资产 v1；仅绑定
      `log_search / log_trace_bundle / contrast_sample`，不为本竖线伪造无关资源参数。
- [ ] 若后续启用监控事件与 Kubernetes 规则，再由 owner 单独补充区域/集群、精确监控规则名、
      Deployment 和 Namespace。不得从日志、拓扑名称或模型输出猜测。
- [x] “接管配置”按已选查询规则实时列出 owner 尚未确认的环境与资源标识；规则要求的 Namespace
      动态标为必填，信息不齐时禁用提交。该提示不替代服务端权威校验，也不预填生产值。
- [x] 增加按“资产 + 查询规则”执行的 admin 只读试跑与不可变审计记录；试跑只接受该规则声明的
      非资源运行参数，所有资源范围只能来自服务端系统观测资产。即使规则误把资源参数标成运行输入，
      也会 fail-closed 拒绝。依赖前序证据的步骤拒绝浏览器手填关联 ID，必须走
      完整证据链；结果只记录 canonical 字段名、资产版本、状态、停止原因、耗时和 actor，不返回或
      落库检索键、原始 DQL、原始日志及凭据。失败查询同样以安全原因码留痕。
- [x] 将“最新 Workspace 资产已启用且作用域唯一”纳入目录可运行状态；对只有部署 YAML 兼容回落的模块，
      明确区分“完整链路可运行”与“可执行管理员试跑”，引导先接管为 Workspace 系统观测资产。
      运行时仍保留独立 fail-closed 校验，页面将试跑失败原因持续显示在弹窗内。
- [ ] 第一份资产逐合同试跑通过后，再把部署 YAML 授权降为兼容回落；未验证前不删除现有 Profile。
- [ ] 当前 Workspace 资产 15 分钟试跑和同一已审核 Profile 的 24 小时合同测试均到达 Guance
      HTTP 200，但都只有 `match_count`，没有 `ps_id / sample_message`，所以仍未通过 `log_search`。
      需要一个保留期内的精确 SendMsg 失败时间，或在授权测试环境触发一次失败；禁止把这次不完整
      结果记为真源成功。

## 14. 历史样本回放入口收敛（2026-08-06）

- [x] 将容易误解为“无码创建场景”的“无码场景预演”改名为“历史样本回放”。
- [x] 从二级菜单和排障规则库移除独立入口，合并到“诊断效果评估”。
- [x] 保留旧 `focus=evidence-synthesis` 深链兼容，自动转到评估台并打开回放。
- [x] 保留并复用 `SopSynthesisService.preview()`；不改变 admin、fixture scope、Recorded Replay、
      不调模型、不创建候选的服务端边界。
- [x] 前端 Vitest `235/235`、ESLint 0 error、Snowflake 精度守卫、类型检查和生产构建通过。

## 15. 诊断效果评估工作区融合（2026-08-06）

- [x] 将评估台从弹窗改为智能排障主工作区内嵌页面，二级菜单和标题层级与其他管理模块一致。
- [x] 保留 `capability=ledger` 深链、当前 Diagnosis 上下文、样本跳转和返回工作台流程。
- [x] 保留样本采集、参考解冻结、单 Agent 基线与历史样本回放的既有服务端合同和权限边界。
- [x] 增加内嵌呈现回归测试，并用真实浏览器验证页面无评估弹窗、工作区滚动和返回流程。
- [x] 前端 Vitest `237/237`、ESLint 0 error、Snowflake 精度守卫、类型检查和生产构建通过。

## 16. 复盘模块统一工作区（2026-08-06）

- [x] 将“历史案例入库”从弹窗改为智能排障主工作区内嵌页面。
- [x] 抽取统一 `CapabilityWorkspaceShell`，让历史案例入库与诊断效果评估共享标题栏、操作区、正文滚动和响应式规范。
- [x] 保留 `capability=case-knowledge` 深链、知识库加载、导入参数、结果投影与 Wiki 管理入口。
- [x] 浏览器验证两个页面均无模块弹窗，标题栏高度和正文边距一致；未执行真实案例导入写操作。
- [x] 前端 Vitest `238/238`、ESLint 0 error、Snowflake 精度守卫、类型检查和生产构建通过。

## 17. 模块可复制接入清单（2026-08-11）

- [x] 在“系统与模块”列表聚合当前模块已启用方法的真实非 Replay 路由、
      Workspace 资产、取证方法、
      operational 已审核排障方案和 owner 验收五项事实，显示 `n/5` 与唯一下一步。
- [x] 增加五步进度详情与对应配置入口；复用已有 API 和页面，不新增后端表、
      查询合同或第二套配置。
- [x] Playbook 不可读时保留 `UNKNOWN`；只有精确 `system + service` 匹配且
      `operational=true` 才计入完成。
- [x] “去排障规则库 / 去负责人验收”均携带精确模块作用域；无精确规则时不选中别的服务，
      跨模块验收时不沿用旧检索键和故障时间。
- [x] 明确“5/5 可试点”不等于 T7/T8 或真实效果验收；目标目录仍为 `0 / 20`。
- [x] 排障前端 `183/183`、ESLint、Snowflake 精度守卫、`vue-tsc --noEmit`
      与 Vite 生产构建通过。
- [ ] 选定下一个真实模块，由 owner 按五步清单从 `0/5` 走到 `5/5`，
      记录每一步的负责人、输入与实测结果，以此验证这套清单真的可复制。

## 18. 受控试点启动（2026-08-13）

- [x] 日常首用入口、ITGW `904003` 真源演练、人工复核与结果登记说明、已关闭正式单进入评估、
      试点接力队列，以及精确范围与三名 Workspace 负责人的配置入口已落地。
- [x] 接力队列保持真实边界：只纳入命中试点范围的非演练 Diagnosis；Replay、fixture、演练记录
      和缺少真源身份的样本不能推进正式效果阶段。
- [x] 排障列表增加所有查看者都能理解的三步试点提示，只读取安全的试点声明和 Diagnosis 摘要；
      未配置、待正式排障、待继续处置和已闭环待评估时都只给一个下一动作，不下放评估写权限。
- [x] “配置试点”从排障列表一键进入并直接展开设置；成员不足时显示真实人数和“先去添加成员”，
      不再让管理员多找一层入口，也不伪装成已经具备保存条件。
- [x] 成员管理承接试点上下文：安全携带本地返回地址、实时显示三人门槛缺口，并可返回仍展开的设置；
      成员管理权限与 `/settings/members` 路由保持一致，不向排障管理员扩权。
- [x] 试点范围可从最近正式排障单直接选择：按大小写无关的 `system / service` 去重，显示正式单数量并排除
      演练记录和不能直接保存的展示名称；点击只回填当前表单，不自动保存、不新增接口，也不擅自把名称猜成标识，
      管理员仍可手工填写精确标识。
- [x] 试点团队按真实操作权限 fail closed：二线至少为 `member`，三线和数据取证负责人至少为 `admin`；
      前端解释角色门槛并禁用不合格人选，服务端拒绝错误声明，人员降权后旧计划也会停止接力。
- [x] 成员准备页把 `3 + 2` 重叠门槛换算成最少的“新增管理员 / 新增二线成员 / 调整现有角色”动作，
      并将“加入已有账号”和“新建账号并加入”显式分流；已有账号路径不会隐式创建用户。
- [x] 试点设置把“补齐成员”和“登记真实查法”收敛成顶部双任务准备区；管理员与 Guance owner 可并行推进，
      两个入口都保留返回当前展开设置的本地路径。Owner 卡明确“登记材料不等于 T7 验收”，不伪造 `0 / 20`。
- [x] 试点样本按建单时版本冻结：三方数据库 V202 只新增可空的 `pilot_plan_version`，不回填历史数据；
      只有计划已启用、人员与角色有效、精确命中范围且非演练的新 Diagnosis 才写入当前版本。队列和统一入口只
      消费该冻结事实，旧版本与历史范围候选不会追溯进入当前效果分母。
- [ ] 管理员先为当前 Workspace 准备 3 名能操作排障的真实成员（其中至少 2 名为管理员或所有者），再在“诊断效果评估”保存第一个精确
      `system / service` 试点声明（可从正式记录候选选择，也可手工填写），并指定彼此不同的
      二线闭环人、三线复核人和数据取证人。未完成前不扩大范围。
- [ ] 用第一张正式、非演练真实告警完成完整效果记录：处置结果、脱敏 Guance 样本、人工标准答案、
      可追溯人工耗时、影子运行与人工复核。没有合格历史单时，从正式工作台新建真实告警，不补 Demo。
- [ ] 连续按同一口径积累周数据，分别复盘判断准确性、二线独立推进、三线首次接手和重大故障收敛；
      未获得真实样本前不宣称提效。
- [ ] T7 继续作为独立投产门禁推进 owner 查询目标冻结和正式批次验收；当前仍为 `0 / 20`，不得用
      试点声明、单次演练、HTTP 200 或一张效果样本替代。

## 19. 未接入系统的弃权可执行化 + 中文拨测任务通用化（2026-08-13）

起因：粘贴 ICare 拨测告警（`sf-icare-app-虚机-拨测检测异常`，无错误码）只得到
`INSUFFICIENT_EVIDENCE` 与「补齐缺失的日志、调用链或指标证据」。该系统从未接入，
一条证据都取不到，让报障人去补证据指向的是任何已配置路径都不会读的东西。

- [x] `SystemOnboardingGapService` 按运行时真实依赖顺序逐层判定 `SYSTEM_IDENTITY /
      PLAYBOOK / OPEN_DISCOVERY_PLAN / EVIDENCE_ROUTE / OBSERVABILITY_ASSET`，
      只在 `INSUFFICIENT_EVIDENCE` 时计算；注册表读取异常一律不判为「未接入」，
      避免把一次数据库抖动说成系统没接。
- [x] 弃权结论改为列出缺失层与责任人（工作区管理员），不再要求报障人补证据；
      已接入系统仍保留原「补齐证据」文案。投影读时计算，反映当前配置而非建单时快照。
- [x] `SYSTEM_IDENTITY` 明确记录：告警「业务系统」原文（含中文与责任人后缀，如
      `深信服新ICare系统-邹汶达`）不满足资产/路由登记的 `[A-Za-z0-9][A-Za-z0-9._-]` 作用域字符集，
      在给出稳定系统编码前，其余各层根本无法登记。
- [x] `EvidenceParameterValuePolicy` 把「资产声明的观测对象名」与「部署资源标识」分成两套字符集：
      前者允许任意文种但排除全部 DQL 元字符（引号、反斜杠、反引号、花括号），后者仍为 ASCII。
      同时修好三道闸门——`ObservabilityAssetService` 登记、`GuanceEvidenceAdapter.authorizedBinding`
      授权、`render` 渲染；此前只有前两道之外的任何一道漏改都会静默 fail closed。
      效果：一份通用 `synthetic_probe` 合同即可覆盖中文命名的拨测任务，
      接入成本从「每个拨测一份已审核 DQL」降到「每个场景一份」。
- [x] **无错误码告警的「症状 → 已审核场景 Playbook」确定性路由已落地。**
      此前 `deterministicRouteMissReason` 第一条就是 `errorCode` 为空即落空，
      拨测/可用性这一整类由监控平台按症状发出的告警**永远**进不了确定性路径，
      每一条都要花一次模型调用换回一个弃权。
      - `SopEntry` 新增 `symptomTriggers`（可选，第 16 个分量），并保留 15 参构造器，
        48 处既有构造点与历史 JSON 全部不动；旧 Playbook 默认不声明触发词，
        即默认不可被症状选中，方向是安全的。
      - 触发词归一化为小写去重，单个上限 64 字符、每份上限 16 条；长度 1 的触发词直接丢弃——
        单个汉字会匹配掉大量中文告警文本，等于悄悄变成兜底路由。
      - `ScenarioSymptomRouter` 只做「已声明短语的包含判断 + 计数」，**不打分、不排序、不断连**。
        命中 2 条即判定注册表对该症状归属有歧义并拒绝路由，与
        `findUniqueOperationalSystem` 早已确立的「歧义不路由」同一条纪律：
        症状比错误码更弱，不能反而适用更松的规则。
      - 只匹配 `title`（经文本策略校验的症状行），不匹配 `rawInput`；否则报障人粘贴的日志里
        引用到触发词就会把工单路由到没人声明过的地方。
      - 只有「唯一缺错误码」可被症状替代；`completeness == SYMPTOM` 仍走原 miss 路，
        因为其 system/service 从未被确认，用它的自由文本去挂已审核权威等于给未核实字段背书。
      - 命中后复用**完全相同**的确定性主路（取证 → 判据 → 规则 → 结论 → Playbook 版本冻结），
        不新增第二条判定路径；未命中只是在原 miss 理由后追加症状路由失败原因，
        使「没有路」和「压根没找」可区分。
      - 注册表读取异常降级为不路由而非报错，保持原有行为，最坏情况等价于改动前。
      - `SopEntryContractTest` 直接对**存储形状**取证而非 Java 构造：证明加了第二个构造器后
        Jackson 仍有唯一 creator、新旧 JSON 都能往返，且字段出现前写入的 Playbook 读回来
        必然是「未声明触发词」——老契约唯一不能漂移的方向。
      - 排障模块测试 `942 / 0 失败`（新增 10 条）。
      - 全量 `mateclaw-server` 为 `5058 / 63 errors`，全部落在 wiki 与 Spring context E2E，
        原因是本地 H2 开发库缺 `createtime / updatetime` 列。已用 `git stash` 取基线核实：
        移除本次全部改动后同一测试类以完全相同方式失败，属既有环境问题，与本次无关。
- [ ] ICare 真实接入未完成：需要 owner 先定系统编码，再登记取证路由、观测资产与
      Guance 拨测任务名。本地 `df-openapi.prd.sangfor.com` 当前解析到 TUN 假地址段
      198.18.0.52 且 HTTPS 连接不通，真源不可达，未做任何真实拨测核实——**不构成 T7 证据**。
      上述通用合同的 DQL 渲染与注入拒绝只由单元测试覆盖。

## 20. OPEN_DISCOVERY 确定性有界调查（2026-08-16）

- [x] 用不可变 `HypothesisGraph` 记录候选原因、服务端问题、判据、状态和证据引用；
      `MISSING / UNEVALUATED` 只能得到 `UNKNOWN`，不得当成 0 或排除。
- [x] 用 `RootCauseFinding` 统一压缩为 `LOCATED / HYPOTHESIS / ABSTAINED`；只有“唯一支持、
      其他全排除”才能 `LOCATED`，候选不冒充确诊。
- [x] 实现域内 `ReadOnlyToolRegistry`：`toolKey@version` 唯一，调用时再校验白名单、signal kind、
      deadline 和 canonical 输出；工具失败只产出 `MISSING`，不暴露原始日志、DQL、端点或凭据。
- [x] 实现 `BoundedInvestigationPlanner`：每轮仅执行一个最高优先级问题，强制迭代、Tool 调用、
      剩余时长预算和类型化 stop reason；网络 Adapter 的超时继续受本轮 deadline 限制。
- [x] 接入 `TroubleshootingAgentTriageService` 的原 OPEN_DISCOVERY 路径：只在现有 Agent 不可用、
      试点开关与平台白名单明确时运行；Agent 可用时不重复取证，全部缺证时仍持久化
      `INSUFFICIENT_EVIDENCE` 与精确停止原因，不让一次真实只读运行落入审计黑洞。
- [x] 冻结 `bounded-open-discovery-v1` 计划标识与 SHA-256 安全指纹，审计仅保存预算、信号类型、
      stop reason 和受限证据引用，不持久化 query / observed / 日志正文；指纹覆盖窗口、目标、
      Tool 版本、问题优先级、判据/阈值与预算。
- [x] 同 measurement 的应用错误扫描按服务冻结独立合同；`csdp-session-service` 与
      `csdp-wechat` 互不借用 ERROR 计数。多个假设同时成立时完整显示并列候选，不任选第一个。
- [ ] 用一条“无审核 SOP，但 Guance 有应用 ERROR”的真实非演练告警验证第一张
      `POLICY_PROPOSED / HYPOTHESIS` 正式单，并由开发确认它是有价值的候选方向，不是仅凭“有 ERROR”的废话。
- [ ] 由 K8s owner 提供真实 cluster / namespace / workload 资产和只读查询合同，再让
      `runtime-health` 从 `MISSING` 变成可评估；未登记前继续不猜。
- [ ] 当且仅当真实样本证明两个假设不足时，再增加 Trace、下游依赖、拨测或 HCI 语义 Tool；
      不先建通用自主规划平台。
- [ ] 多 Agent Challenger 仍只是 P2 影子评测目标；不进入本次在线根因决策，不按共识或票数升级置信。

## 21. 同一告警窗口的失败请求分组（2026-08-17）

- [x] 用 CTI 真实告警反证「日志条数≠独立请求数」：3 条外层失败日志对应 2 个独立请求，
      两个请求又分别命中不同失败特征。该核对为人工只读调查，不冒充平台新版本已跑通。
- [x] Evidence Spine 支持 CTI 专属的可选第四步 `cti_failure_pattern_scan`；服务端只冻结场景标识和有界窗口，
      Adapter 在内存中将 701018 失败请求集与特征候选集求交后只返回计数，关联 ID 随即丢弃；
      持久化白名单不包含查询、日志或关联 ID。
- [x] 正式详情用大白话展示失败请求总数、已审核分组和未归类数；把结果称为「线索」，
      不改写 Diagnosis 根因，不将多分组升级成唯一结论。
- [x] 用 2026-08-17 15:29–15:44 历史窗口对三个真实 Guance 只读查询做合同验收；
      内存等价交集得到失败 `2`、已识别 `2`、两类线索各 `1`，且只输出聚合数量。
      该项证明查询形状与分组算法可执行，不替代 v2 的回放、审批与在线 Diagnosis 验收。
- [ ] 将 CTI v2 种子按治理流程回放、审核并切换 active-approved；未审批前保留当前 v1 在线权威。
- [ ] 用新的 CTI 真实告警窗口重跑 v2，核对「失败请求总数 = 已归类 + 未归类」，
      并由 CTI / CSP owner 确认两类特征的业务含义；在此之前不宣称已投产。

## 22. 角色化详情与 iCare HTTP 502 调查（2026-08-18）

- [x] 排障详情支持三线开发 / 二线保障视角切换，默认三线；视角只改变投影，不更改权限与诊断事实。
- [x] 三线首屏先显示具体根因 / 候选方向、关键证据、未知项和下一步；二线只显示事件、影响、升级与交接，不提供根因确认动作。
- [x] 已复核的 iCare 产品映射 502 告警可从完整原文安全提取服务、时间、操作和 HTTP 状态；
      原始 URL、请求体与栈不进入证据。
- [x] 用 `incident-report@1` 把「告警已明确的直接失败点」接入有界调查，同时明确它不能证明上游 502 根因。
- [x] 用结构化 `EvidenceBasis` 区分真实观测、告警上报和录制回放；禁止告警上报信号绑定外部数据源，
      并让场景图、工具和投影共用同一精确审核策略。
- [x] 用演练单 `diag-53eb11b64ad14a47a64dab9d602830cb` 完成 MySQL 持久化、安全投影和两视角浏览器验收；该单不计入 T7。
- [ ] 由 iCare / 网关 owner 提供一个能跨日志连接告警请求和上游响应的安全关联字段，再登记上游日志 / 健康状态只读工具。
- [ ] 用新的正式、非演练告警复现同类问题，由三线 owner 确认平台给出的候选方向与人工结论一致；在此之前不宣称已定位上游根因。

## 23. 排障详情第一性原理瘦身（2026-08-18）

- [x] 三线首屏收敛为「结论 → 为什么 → 下一步」一条阅读主线；移除重复的五问进度展示，
      已完成场景不再重复展示三次取证说明。
- [x] 用结构化 `EvidenceBasis` 解释事实来源，再显示已持久化关键依据和判断边界；不展示内部思维链，
      不根据中文前缀猜证据成色。
- [x] 候选线索、阶段耗时和完整证据 / 技术记录全部默认折叠，质疑结论、交接或定位代码时再展开。
- [x] `HYPOTHESIS + CONFIRMED` 明确显示「候选方向已人工确认」，下一步仍要求补齐根因证据；
      只有 `LOCATED + CONFIRMED` 才显示「根因已人工确认」。
- [x] 用 iCare HTTP 502 演练单完成三线 / 二线真实浏览器验收；前端全量 `561 / 561`、
      类型检查与生产构建通过。
- [ ] 由一名真实二线和一名三线 owner 各完成一次不讲解任务测试：10 秒内分别回答“是否升级”和
      “当前根因 / 未知项 / 下一步”；根据误读点继续减法，不新增第二套详情页。

## 24. 三线详情的单焦点设计（2026-08-18）

- [x] 三线首屏只保留一个根因状态、一个主结论、「已经知道 / 还不知道」和一个下一步动作区；
      删除首屏三列推理步骤与重复状态标签。
- [x] `HYPOTHESIS` 一律先说「尚未找到根因」；已确认的直接失败点进入「已经知道」，不得用
      `rootCause` 文本前缀改变假设与根因的语义边界。
- [x] 判断来源、可信度、候选分组、阶段耗时和完整证据按两层 disclosure 渐进展开；不删除审计，
      也不展示内部思维链。
- [x] 详情路由自动收起二级能力导航，返回列表后仍尊重用户保存的展开偏好；1280×720 首屏可见
      下一步动作与主要按钮。
- [x] 用 iCare HTTP 502 演练单完成登录态浏览器验收，控制台 `0 error`；前端全量 `563 / 563`、
      类型检查、生产构建和 `git diff --check` 通过。
- [ ] 找一名未参与设计的三线开发做 10 秒测试：回答「根因找到了吗、现在知道什么、下一步查什么」。
      若任一答案错误，继续删减文案，不增加新卡片。
