# T7 最小可执行切片：binding 对齐核对表

> 状态：**PREPARATION_ONLY** — 本表不能写 `guance-recording-targets.json`，也不能点 T7 `ACCEPTED`。  
> 目的：在推荐模板的建议 20 条里，标出与现有 pilot binding 家族**真正能对齐**的起点，以及「谁填哪一格」。  
> 硬事实：凑不满 20 条完整 `ownerContract` → 校验失败 → **不能验收**。本切片只告诉你从哪几条开始填。

## 0. 对齐结论（先读）

| 现有 binding 三件套 | pilot service | 检索语义（冻结） | 推荐模板里能否「直接填入 bindingRefs」 |
|---|---|---|---|
| `csdp-message-send-{log-search,trace-bundle,contrast}` | `csdp-session-service`（日志源 `csp-rpc-msg`） | `failed AND sendmsg` / 对照 `message_length_eq_2011` | **0 条**。`csdp:IM1010` 已有录制权威，**禁止重复计数**；模板建议 20 里也无同语义 selector。 |
| `csdp-cti-create-conversation-*` | `csdp-task` | `@code = 701018`（链上核对 701022 / CreateConversation） | **0 条**。`csdp:701018` **不在** 28 候选内。 |
| `csdp-itgw-access-*` | `csdp-wechat` | `@code = 904003` | **0 条**。`csdp:904003` **不在** 28 候选内。 |

因此：**禁止**把三套 pilot binding 名复制到模板任意行冒充 20 条。校验要求每条的  
`verifiedRuntimeService + safeSearchTerm + window + 三份 bindingRef` 查询语义唯一。

本切片能做的是两档：

1. **服务可对齐**：`verifiedRuntimeService` 可先按 pilot 家族填草稿，**binding 必须新建或改写**（开发写 catalog 时再冻结）。  
2. **域相近、服务待 owner 核实**：只作填表优先序，不可预填 binding。

## 1. 建议从这 9 条开始填（仍远小于 20）

| # | selectorKey | 档 | 对齐档 | 建议 verifiedRuntimeService（草稿） | 能否复用现有 bindingRefs | owner 必须另证的检索键 | 开发后续动作 |
|---|---|---|---|---|---|---|---|
| 1 | `csdp:IM2002` | A | 消息域 / SendMsg 家族近邻 | 待核实：是否 `csdp-session-service` / 日志是否落在 `csp-rpc-msg` | **否**（message-send 只认 sendmsg） | `Middleware_MsgCacheFailed_001` 或等价安全键 | 新建 message-cache 三件套 binding，语义不得与 sendmsg 撞车 |
| 2 | `csdp:IM3002` | A | 同上 | 同上 | **否** | `Middleware_MsgPersistDBFailed_001` | 新建 message-persist 三件套 |
| 3 | `csdp:101010` | A | CTI 域近邻 | 待核实：是否 `csdp-task` | **否**（CTI binding 只认 701018） | `Cti_DispatchPulsarFailed_001` | 新建 Pulsar 调度失败三件套 |
| 4 | `csdp:101015` | A | CTI 域近邻 | 待核实：是否 `csdp-task` | **否** | `Cti_EnqueueFailed_001` | 新建入队失败三件套 |
| 5 | `csdp:401007` | A | CTI/会话域近邻 | 待核实：CTI 侧真实 service | **否** | `Conversation_CspLoginFail_001` | 新建 CSP 登录失败三件套 |
| 6 | `csdp:501001` | A | CTI 域近邻 | 待核实 | **否** | `Third_CTILoginDataFail_001` | 新建第三方 CTI 登录三件套 |
| 7 | `csdp:Workorder_CustomerDetailFail_004` | A | **wechat 服务可对齐** | 草稿可写 `csdp-wechat`（与 ITGW 同服务） | **否**（itgw 只认 904003） | `Workorder_CustomerDetailFail_001` | 新建 workorder-detail 三件套（service=`csdp-wechat`） |
| 8 | `csdp:Workorder_CustomerListFail_003` | A | 同上 | `csdp-wechat` | **否** | `Workorder_CustomerListFail_001` | 新建 workorder-list 三件套 |
| 9 | `csdp:Workorder_EmergencyCreateFail_005` | A | 同上 | `csdp-wechat` | **否** | `Workorder_EmergencyCreateFail_001` | 新建 workorder-emergency 三件套 |

可选第 10 条（同 wechat 服务，仍在建议 20 内）：

| # | selectorKey | 对齐档 | 说明 |
|---|---|---|---|
| 10 | `csdp:Workorder_UpgradeServiceFail_006` | wechat 服务可对齐 | 同上，**禁止**填 `csdp-itgw-access-*` |

### 不在本切片内、但 demo 已跑通的真源（勿混进「复用 binding 凑数」）

| 真源 | 状态 | 对 T7 20 条的含义 |
|---|---|---|
| SendMsg / `message_send_failed` + `csdp-message-send-*` | Demo 竖线已通 | 场景键不在本 intake 的 28 selector 里；不能靠复制合同凑数 |
| `csdp:IM1010` | `ALREADY_RECORDED` | **禁止**再计入本批 20 |
| CTI `701018` + `csdp-cti-create-conversation-*` | Demo/告警窗已验证 | selector **不在** 28 候选 → **不能**经本模板 intake 写入 |
| ITGW `904003` + `csdp-itgw-access-*` | Demo/告警窗已验证 | 同上 |

要把 701018 / 904003 纳入 T7 窗口，需要**重新走合同准备**扩候选，而不是在本推荐模板里硬填。

## 2. 「谁填哪一格」字段核对表

对每一条 `selectedForWindow=true` 的 `ownerContract`：

| 字段 | 谁填 | 本切片可预填草稿？ | 填什么 / 禁什么 |
|---|---|---|---|
| `ownerTeam` | **Owner** | 否 | 责任团队名；勿写个人账号密钥 |
| `ownerLevel` | **Owner** | 可参考 `sourceHints.levels` | 只能是 `P0`/`P1`/`P2`，须 owner 核实后定稿 |
| `ownerScenario` | **Owner** | 可参考 `sourceHints.scenarios` | owner 核实后的场景表述，不是源材料原文堆砌 |
| `verifiedRuntimeService` | **Owner**（开发可建议） | 仅 §1 的 wechat 四条可草稿 `csdp-wechat` | 必须是真实运行 service ID；与 Guance `service` 标签一致 |
| `candidateReference` | **Owner + 开发**约定稳定 ID | 否 | 指向受评审候选材料的**唯一**安全引用；20 条互不重复 |
| `serverQueryContractReference` | **开发**起草，**Owner**签字确认 | 否 | 指向服务端查询合同；禁止贴 DQL / URL / Key |
| `safeSearchTerm` | **Owner** | 可参考 `signatureErrorCodes[0]` | 单一安全检索键；禁止原始日志、禁止敏感业务正文 |
| `window` | **Owner**（开发按真源稀疏度建议） | 草稿可写 `-6h`（SendMsg 竖线经验）或 `-15m`（CTI/ITGW 告警窗经验） | 须落在合同允许的相对窗；最终以历史窗可命中为准 |
| `anomalyCriterionReference` | **开发**起草，**Owner**确认 | 否 | 确定性判据引用，条间唯一 |
| `diagnosisRuleReference` | **开发**起草，**Owner**确认 | 否 | 诊断规则引用，条间唯一 |
| `bindingRefs.log_search` | **开发**（写进 runtime 前） | **本切片一律不预填 pilot 名** | 必须是将要冻结进 catalog 的真实 binding id；语义须匹配本条 `safeSearchTerm` |
| `bindingRefs.log_trace_bundle` | **开发** | 同上 | 与 log_search 同故障族，且 PS ID 只来自上一步 |
| `bindingRefs.contrast_sample` | **开发** | 同上 | 对照特征不得与其它条撞车 |
| `historicalOccurredAt` | **Owner**（可找 SRE/告警） | 否（除非该 selector 自己有保留期内告警） | UTC RFC3339 **整秒**；禁止未来时间；须仍在保留期 |
| `historicalSourceReference` | **Owner** | 否 | 唯一故障记录引用（告警 ID / 工单号等安全引用），条间不重复 |

### 角色分工（一轮最小动作）

| 角色 | 本切片立刻做什么 | 做完仍不能做什么 |
|---|---|---|
| **Owner** | 复制 recommended → `t7-owner-contract-intake.local.json`；先啃 §1 的 9–10 条；填齐上表 Owner 格 | 点验收；写 runtime catalog |
| **开发** | 为每条规划**新的** binding id（可参考三套 pilot 的写法，但 id/检索语义必须新）；准备 `candidate` / 判据 / 规则引用名 | 把 `csdp-message-send-*` / `csdp-cti-*` / `csdp-itgw-*` 填进非同语义行 |
| **双方** | 用 `t7_owner_contract_intake.py --validate` 迭代到 `selectedCount>=20` 且 `PREPARED_NOT_EXECUTABLE` | `canAcceptT7` 仍为 false，直到 catalog + preflight + 内网 owner 七勾 |

## 3. 凑满 20 的现实路径（本切片之外）

建议顺序（与 `t7-owner-contract-intake.md` 一致，但叠加 binding 现实）：

1. 先完成 §1 的 **9–10 条**（wechat 四条 + 消息两条 + CTI 域四条）——服务/域最清晰。  
2. 继续吃掉建议 20 里剩余 `A_HINTED`（如 `101004`/`201003`/`201011`/`901002`/`301002` 等）——**全部按「新 binding」对待**。  
3. 补 2 条 `B_CONTEXT_ONLY`，再补 3 条已选的 `C_SOURCE_GAPS`。  
4. 若要坚持把 **701018 / 904003** 算进窗口：先扩合同准备候选，再换入 selected 集；**不要**在现模板里伪造 selector。

当前进度记账：

| 指标 | 数 |
|---|---:|
| 验收最低条数 | 20 |
| 本切片可开工条数 | 9–10 |
| 可直接复用 pilot bindingRefs 的条数 | **0** |
| 距离可点验收 | 仍差完整 20 份合同 + catalog + preflight + owner 窗口 |

## 4. 本地起步命令

```bash
cp docs/intelligent-troubleshooting/t7-owner-contract-intake.recommended.template.json \
  /path/to/controlled/t7-owner-contract-intake.local.json

# 先只改 §1 的几条 ownerContract，再全量校验（少于 20 完整合同会失败——预期行为）
python3 docs/intelligent-troubleshooting/l0/t7_owner_contract_intake.py \
  --validate /path/to/controlled/t7-owner-contract-intake.local.json
```

**完成后的 local JSON 不要提交仓库。**
