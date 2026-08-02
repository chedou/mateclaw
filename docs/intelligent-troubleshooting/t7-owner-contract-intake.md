# T7 Owner 查询合同输入说明

> 目标：在进入内网窗口前，由 owner 从当前 28 条候选中完成 **20–28 条**安全合同输入。
> 校验通过只表示 `PREPARED_NOT_EXECUTABLE`，不能写运行目录、不能授权 T7，也不能替 owner 提交
> `ACCEPTED`。

## 当前分层

| 档位 | 数量 | 已有材料 | 仍然必须由 owner 做什么 |
|---|---:|---|---|
| `A_HINTED` | 15 | 等级、场景、日志提示 | 核实真实 service、查询合同、判据/规则、binding 与历史时间 |
| `B_CONTEXT_ONLY` | 2 | 等级、场景 | 额外补安全检索键或日志合同 |
| `C_SOURCE_GAPS` | 11 | 只有部分上下文 | 还要补等级/场景缺口和检索合同 |

首批 20 条的低成本顺序建议是：15 条 `A_HINTED` 全部核实，加入 2 条 `B_CONTEXT_ONLY`，再从
`C_SOURCE_GAPS` 优先核实已有场景提示的 `csdp:101017`、`csdp:101062`、`csdp:301045`。
这只是分工顺序，不是查询合同或可执行目标；owner 可以基于真实资产调整选择。

`csdp:101014` 因源材料冲突不进入模板，必须先回源解决；`csdp:IM1010` 已有录制权威，不能重复计数。

## 填写方法

1. 将 [`t7-owner-contract-intake.template.json`](./t7-owner-contract-intake.template.json)
   复制到受控本地目录。**完成后的文件不要提交到仓库**。
2. 需要进入首批窗口的行设置 `selectedForWindow: true`，把 `ownerContract: null` 替换为下面的完整对象。
3. 未选择的行必须保持 `selectedForWindow: false` 和 `ownerContract: null`；不能删除候选、修改 selector、
   档位或来源提示。

```json
{
  "ownerTeam": "<责任团队>",
  "ownerLevel": "<P0|P1|P2>",
  "ownerScenario": "<owner 核实后的故障场景>",
  "verifiedRuntimeService": "<真实运行 service ID>",
  "candidateReference": "<候选材料的稳定安全引用>",
  "serverQueryContractReference": "<服务端查询合同的稳定安全引用>",
  "safeSearchTerm": "<单一安全检索键>",
  "window": "<-15m 等 1 秒至 24 小时相对窗口>",
  "anomalyCriterionReference": "<确定性异常判据引用>",
  "diagnosisRuleReference": "<确定性诊断规则引用>",
  "bindingRefs": {
    "log_search": "<当前 bindingRef>",
    "log_trace_bundle": "<当前 bindingRef>",
    "contrast_sample": "<当前 bindingRef>"
  },
  "historicalOccurredAt": "<仍在保留期内的 UTC RFC3339 整秒时间>",
  "historicalSourceReference": "<唯一安全故障记录引用>"
}
```

上面的尖括号是说明文字，故意不能通过校验。引用只指向受评审的服务端材料，不粘贴其正文。
所有被选合同的 candidate、查询合同、判据、规则和历史来源引用都必须各自唯一，防止把一份 SendMsg
合同复制 20 次冒充批次。仅改引用名也不能绕过：
`verifiedRuntimeService + safeSearchTerm + window + 三份 bindingRef` 组成的实际查询语义同样必须唯一。

## 校验

```bash
python3 docs/intelligent-troubleshooting/l0/t7_owner_contract_intake.py \
  --validate <受控本地目录>/t7-owner-contract-intake.local.json
```

成功输出仍应包含：

```json
{
  "status": "PREPARED_NOT_EXECUTABLE",
  "selectedCount": 20,
  "canAcceptT7": false,
  "canWriteRuntimeCatalog": false
}
```

以下内容可确定拒绝：少于 20 条、陈旧准备指纹、未知/重复 selector、篡改来源提示、未来时间、额外字段
（包括 `rawLog`）、DQL、HTTP(S) URL、API Key/Token/密码。人类自由文本字段仍不得粘贴原始日志；
该工具不声称能识别任意日志文本，但不会回显或写入 owner 输入正文。

## 校验之后

1. 开发者按 owner 已核实的稳定引用编写完整 `SopEntry` 和
   `guance-recording-targets.json`；本工具不会替它生成候选或查询。
2. Java 目录启动校验重算 candidate/request 指纹，并执行完整 Playbook 安全合同。
3. 重启目标环境，用运行接口返回的 `targetId` 准备 20–30 条历史计划，再跑 T7 只读预检。
4. 只有预检通过，才进入内网窗口由 owner 提交 `ACCEPTED`，随后一次采集 20–30 份 D19 聚合正例。
