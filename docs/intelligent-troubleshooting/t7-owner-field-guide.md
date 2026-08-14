# T7 Owner 逐字段填写指引

面向要填那 20 行、15 个字段的 workspace owner。

[`t7-owner-contract-intake.md`](./t7-owner-contract-intake.md) 讲的是**流程和分工**：从哪儿拿工作表、
校验通过意味着什么、之后交给谁。本文只讲**每一格填什么**：值从哪里查、什么格式、填错会看到哪句话。
两份配合着看，不重复。

> 本文列出的所有校验规则和报错原文，都是对
> `l0/t7_owner_contract_intake.py` 实跑得到的，不是从代码推断的。

---

## 先明确一件事：你填的不是"配置"，是"证词"

这 15 个字段里，只有格式是机器管的；**值对不对，机器一概不知道**。

服务端会检查 `verifiedRuntimeService` 是不是合法标识符，但它无法知道这个 service 在观测云里
是否真的存在。检索键同理——填一个语法正确但查不到东西的词，校验照样通过。

这就是为什么这一步必须由 owner 做，而不是开发者猜。**编出来的映射会一路通过所有闸门，
然后在真实故障上给出看起来很合理的错误答案**——那比系统直接回答"证据不足"危险得多。

查不到就别勾选那一行。宁可停在 `T7 BLOCKED · 18 / 20`，也不要凑够 20 条。

---

## 三种格式，先认清

15 个字段只用到三种格式校验，认清了就不用逐个记。

| 名字 | 允许的字符 | 长度 | 用在哪 |
|---|---|---|---|
| **安全标识符** | 字母数字开头，之后可含 `. _ : / -` | ≤128 | service、检索键、三个 binding |
| **安全引用** | 同上，额外允许 `#` | ≤256 | 六个 `*Reference` 字段 |
| **自由文本** | 中文可以，不能有控制字符 | 见各字段 | 团队、场景 |

前两种**不允许中文、空格、`@`、`?`、`=`**。第三种可以写中文。

所有字段都拒绝这些内容，不论格式：`http://` 或 `https://`、`D::`（DQL 片段）、`DF-API-KEY`、
`Bearer xxx`、形如 `api_key=` / `password:` / `secret=` 的片段、JWT。
**不要粘贴原始日志正文，也不要贴链接。**

---

## 15 个字段

### 一、身份类（3 个）——查内部工单系统和值班记录

#### 1. `ownerTeam` 责任团队

- **从哪查**：这个错误码归谁修。看历史工单的处理团队，或值班表。
- **格式**：自由文本，≤128 字符，**中文可以**。
- **例**：`客服平台组`

#### 2. `ownerLevel` 故障等级

- **从哪查**：工作表每行的 `sourceHints.levels` 已经给了源材料里的等级，**但需要你确认**它符合
  你们现行的定级标准。
- **格式**：只能是 `P0`、`P1`、`P2` 三个值之一。大小写敏感，别写成 `p1`。
- **填错**：`ownerLevel must be P0, P1, or P2`

#### 3. `ownerScenario` 故障场景

- **从哪查**：`sourceHints.scenarios` 和 `modules` 给了线索（如"客户登录"+"首页"），
  你要把它确认成一句能让人看懂的业务场景。
- **格式**：自由文本，≤160 字符，中文可以。
- **例**：`客户登录后首页数据加载失败`

---

### 二、查询语义类（3 个）——**必须登观测云核对**

这三个字段加上下面的三个 binding，共同构成"查询语义"，六项组合起来在 20 行里**必须唯一**。
这是防止把一份合同复制 20 次冒充一个批次，改引用名也绕不过去。

#### 4. `verifiedRuntimeService` 真实运行服务

- **从哪查**：**观测云里的 service 标签实际值**，不是业务称呼。
- ⚠️ 最容易错的一格：`sourceHints.sourceServices` 里写的是"客服侧"这种业务归属，
  **那不是 service ID**。必须去观测云查这个模块的日志实际打在哪个 service 上。
- **格式**：安全标识符，**不能有中文**。
- **例**：`csdp-session-service`
- **填错**：填中文得到 `verifiedRuntimeService must be a safe identifier`

#### 5. `safeSearchTerm` 安全检索键

- **从哪查**：**在观测云里实际搜一次，确认能命中失败日志。**
- ⚠️ `sourceHints.signatureErrorCodes` 里那些像 `Customer_IMLoginDataFail_001` 的值是**线索，
  不是已核实的检索键**。可能拼写不同、可能已经改过、可能根本没打进日志。
- **格式**：安全标识符，单个词，不能是一段查询语句。
- **例**：`Customer_IMLoginDataFail_001`（前提是你搜过、有结果）

#### 6. `window` 查询时间窗

- **怎么定**：从故障发生时刻往前推多久能覆盖到足够的失败样本。
- **格式**：`-数字` 加单位 `s`/`m`/`h`/`d`，**最长 24 小时**。
- **例**：`-15m`、`-2h`、`-1d`
- **填错**：`-48h` 得到 `window exceeds 24 hours`；写 `15m`（少了减号）得到
  `window must be a bounded relative window`

---

### 三、绑定类（3 个）——找开发者要，但要你确认

#### 7–9. `bindingRefs.log_search` / `log_trace_bundle` / `contrast_sample`

- **是什么**：分别对应"查失败日志"、"还原整条调用链"、"成功失败样本对照"三种取证方法的绑定 ID。
- **从哪来**：开发者按你核实的 measurement / 字段写出绑定，**你确认它查的是对的东西**。
- ⚠️ **试点用的那套绑定一条都不能复用**（对齐清单里可复用数是 **0**）。每一行都需要新的。
- **格式**：安全标识符。
- **例**：`csdp-101004-log-search` / `csdp-101004-trace` / `csdp-101004-contrast`

---

### 四、引用类（4 个）——指向评审过的材料，不是贴正文

这四个是**编号**，指向服务端已评审的材料。**每一个在 20 行里都必须唯一。**

| # | 字段 | 指向什么 | 例 |
|---|---|---|---|
| 10 | `candidateReference` | 候选 SOP 材料 | `sop/csdp/101004#candidate-v1` |
| 11 | `serverQueryContractReference` | 服务端查法编号 | `query-contract/csdp/101004#v1` |
| 12 | `anomalyCriterionReference` | 异常判据 | `criterion/csdp/101004#error-rate` |
| 13 | `diagnosisRuleReference` | 诊断规则 | `rule/csdp/101004#root-cause` |

- **格式**：安全引用（可以用 `#` 和 `/` 组织层次）。
- **填错**：贴 wiki 链接得到 `candidateReference is blank, unsafe, or too long`
  ——因为 `https://` 在禁止列表里。要贴就贴编号，不贴 URL。

---

### 五、历史事实类（2 个）——查工单系统

#### 14. `historicalOccurredAt` 故障发生时间

- **从哪查**：工单或告警记录里的实际发生时刻。
- ⚠️ **必须还在观测云的日志保留期内**，否则窗口期采集时查不到数据。这一点校验器**查不出来**，
  只有到内网窗口才会暴露——所以现在就要确认保留期。
- **格式**：UTC 整秒 RFC3339，形如 `2026-08-01T03:22:10Z`。注意是 **UTC**，
  北京时间要减 8 小时。
- **填错**：`2026-08-01 03:22:10`（少了 T 和 Z）得到
  `must be UTC RFC3339 whole seconds`；填未来时间得到 `is in the future`

#### 15. `historicalSourceReference` 告警或工单号

- **从哪查**：工单号或告警 ID。
- **格式**：安全引用。**20 行里必须唯一**——20 条不同的故障，不可能共用一个工单号。
- **例**：`ticket/CSDP-101004-20260801`

---

## 一行填好的完整样例

⚠️ **这是格式示例，不是可用的值。** 里面每一个都必须换成你在观测云和工单系统里核实过的真值。

```json
{
  "ownerTeam": "客服平台组",
  "ownerLevel": "P1",
  "ownerScenario": "客户登录后首页数据加载失败",
  "verifiedRuntimeService": "csdp-session-service",
  "candidateReference": "sop/csdp/101004#candidate-v1",
  "serverQueryContractReference": "query-contract/csdp/101004#v1",
  "safeSearchTerm": "Customer_IMLoginDataFail_001",
  "window": "-15m",
  "anomalyCriterionReference": "criterion/csdp/101004#error-rate",
  "diagnosisRuleReference": "rule/csdp/101004#root-cause",
  "bindingRefs": {
    "log_search": "csdp-101004-log-search",
    "log_trace_bundle": "csdp-101004-trace",
    "contrast_sample": "csdp-101004-contrast"
  },
  "historicalOccurredAt": "2026-08-01T03:22:10Z",
  "historicalSourceReference": "ticket/CSDP-101004-20260801"
}
```

这份格式实跑校验器验证过，20 行都按此填写会得到 `PREPARED_NOT_EXECUTABLE / selectedCount: 20`。

---

## 常见错法与对应报错

下面每一条都是实跑出来的原文。

| 错法 | 报错 |
|---|---|
| 占位符没换完 | `<selector>.<字段> contains an unresolved placeholder` |
| service 填了中文 | `verifiedRuntimeService must be a safe identifier` |
| 引用填成 URL | `candidateReference is blank, unsafe, or too long` |
| 时间写成 `2026-08-01 03:22:10` | `historicalOccurredAt must be UTC RFC3339 whole seconds` |
| 时间填成未来 | `historicalOccurredAt is in the future` |
| 窗口 `-48h` | `window exceeds 24 hours` |
| 两行引用重名 | `candidateReference must be unique across selected contracts` |
| 两行查询语义相同 | `query semantics must be unique across selected contracts` |
| 不足 20 条 | `owner input requires 20 to 28 selected contracts` |

### 一个容易踩的坑：换掉某一行的正确做法

想把某行换出首批，**要同时做两件事**：`selectedForWindow` 改成 `false`，
**并且**把 `ownerContract` 整个设为 `null`。

只改前者会得到一句看起来无关的报错：

```
csdp:101004 unselected row must not carry ownerContract
```

换入的行则相反：`selectedForWindow` 设为 `true`，并补齐完整的 15 个字段。

---

## 校验方法

不需要联网，不需要起服务，不需要观测云凭据：

```bash
python3 docs/intelligent-troubleshooting/l0/t7_owner_contract_intake.py \
  --validate <你的受控目录>/t7-owner-contract-intake.local.json
```

校验器**一次列出所有问题**，格式是 `选择器.字段 + 原因`，**不回显你填的值**。
所以按整份清单改完再重跑，不要逐格试探。

未填写的模板直接跑会得到 300 条问题（20 行 × 15 字段），这是正常的——
工作表被刻意设计成无法自己通过。

成功的输出：

```json
{
  "status": "PREPARED_NOT_EXECUTABLE",
  "selectedCount": 20,
  "canAcceptT7": false,
  "canWriteRuntimeCatalog": false
}
```

**注意后两个 false。** 校验通过不等于 T7 通过，也不能写运行目录——后面还有开发者写候选、
运维准备窗口计划、owner 提交七项核对三步。详见
[`t7-owner-contract-intake.md`](./t7-owner-contract-intake.md) 的"校验之后"。

---

## 填好的文件不要提交到仓库

它包含真实的服务名、检索键和工单号。放在受控本地目录，交给开发者时走内部渠道。
