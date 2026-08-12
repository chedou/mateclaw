# Quickstart：从零到走完一个案子

> Guance 真源路演六用法见 [`demo-runbook.md`](./demo-runbook.md)；
> 复验：`./scripts/troubleshooting-demo-verify.sh all`。
>
> 目标：**新克隆的仓库 → 明确的启动步骤 → 一份可读的诊断、一条可评审的知识、
> 一个走到关闭的案子**，全程 fixture。
>
> 这份文档存在的原因不是"缺文档"。此前每一道闸门都是刻意 fail-closed 的，
> 每一道单独看都对；但它们的**合取**是——默认状态下没有任何一条路径可走：
> 两个证据源默认关闭，仓库不随带任何 Playbook（迁移里 0 条 INSERT），
> 所以任何报障都必然 route miss。
>
> **一个自己都跑不起来一次的系统，"它是否安全"其实还没有被真正检验过**——
> fail-closed 只在有人真的去开门时才会被测试。

---

## 1. 跑一次

```bash
# 终端 A：先把父 POM 与 plugin API 装入本地库，再带 demo profile 启动
mvn -pl mateclaw-plugin-api -am -DskipTests install

mvn -pl mateclaw-server -DskipTests test-compile
fixture_jar="$(./scripts/package-troubleshooting-demo-fixture.sh)"

mvn -pl mateclaw-server -DskipTests \
    -Dspring-boot.run.additional-classpath-elements="${fixture_jar}" \
    -Dspring-boot.run.profiles=dev,troubleshooting-demo \
    spring-boot:run

# 终端 B：走一次完整路径并断言结果
MATECLAW_USERNAME=admin MATECLAW_PASSWORD=admin123 \
    ./scripts/troubleshooting-smoke.sh
```

也可以改用 `MATECLAW_TOKEN=<mc_ 前缀的 PAT>`；两种凭据走的是同一个过滤器。

实测输出（H2 默认库、demo profile）：

```text
  ✓ 服务可达，身份通过
  ✓ READY 的证据源：recorded-replay
  ✓ Playbook 已就绪：csdp:IM1010 (approved)
  ✓ 已产出诊断：diag-…
  ✓ 结论类型：LOCATED
  ✓ 结论：已通过受控证据定位到异常环节
  ✓ 开发证据步数：4
  ✓ 北极星：补问=PT0.029S 调查=PT0.004S（三段分别计量，不合成总时长）
```

IM1010 的真实历史聚合正例是失败样本 2/2 命中特征、成功样本 0/14047 命中特征；同一判据形状还会
确定性生成排除例和全 `MISSING` 弃权例。HTTP 结果为 `LOCATED / MEDIUM / fixtureMode=true`，
并明确写明“消息发送或 MQ 生产者路径异常待核查”，不足以证明 Kafka Broker 故障。

任何一道闸门没过，它会指出是哪一道、以及唯一的下一步动作。

不启服务也能先看清路径：

```bash
./scripts/troubleshooting-smoke.sh --gates
```

---

## 1.5 再跑一次：看见一条知识被**生产**出来

上面那条是**命中路**——已知错误码路由到已批准 Playbook，零 LLM 调用。它**消费**知识。

蓝图 §11.1 唯一点名"必须先通过"的验收案例是相反的那条：**无 error_code**，
靠 `log_search → PS ID → log_trace_bundle` 调查，最后产出一份可评审的 `PlaybookDraft`
并与人工解法逐项对照。它**生产**知识——而新 Playbook 只能从这里来。

```bash
MATECLAW_USERNAME=admin MATECLAW_PASSWORD=admin123 \
    ./scripts/troubleshooting-miss-path-smoke.sh
```

实测输出：

```text
  ✓ 三次取证完成，PS ID=synthetic-ps-message-send-001，调用链服务数=3，成功样本对照=true
  ✓ 归纳完成：stage=CANDIDATE_CREATED
  ✓ 候选已写入：reviewStatus=CANDIDATE，归纳来源 provider=recorded
  ✓ 晋升资格：NOT_ELIGIBLE（这一道是反向断言——它必须失败才算通过）
  ✓ 与人工解法对照：passed=true
  ✓ 幂等：重跑复用同一条候选（stage=CANDIDATE_REUSED）
```

**第 7 道闸门是这条路里最值得看的一格**：它断言候选**不可**被自动晋升。
产出知识很容易，产出"不会被误当权威的知识"才难——证据型草稿必须先补齐
owner / 正例回放 / 负例回放，才谈得上晋升资格。

`--gates` 同样可用。

---

## 1.6 第三次：把一个案子真正走完

前两条各自证明了一个环，但都停在环的前半段——一个停在"诊断可读"，一个停在"候选已产出"。
**交接、批准、外部登记、恢复验证、关闭**，才是服务经理和处置人真正做的那部分，
也是北极星里「可交接」所在的地方。

```bash
MATECLAW_USERNAME=admin MATECLAW_PASSWORD=admin123 \
    ./scripts/troubleshooting-scenario-smoke.sh
```

实测输出：

```text
  ✓ 诊断已产出：diag-…（LOCATED）
  ✓ 生产写动作就位：A2（BLOCKED）
  ✓ 已确认：READY_FOR_HUMAN → CONFIRMED
  ✓ 已交接：数据库平台组
  ✓ 批准前登记被拒（409）：manual write must be approved before recording an external outcome
  ✓ 批准生效但什么都没发生：approval=APPROVED_NOT_EXECUTED，execution=BLOCKED
  ✓ 批准之后执行依旧被拒（409）：production write executor is not connected
  ✓ 外部结果已登记：1 条，recoveryVerified=true
  ✓ 已关闭并沉淀候选：candidate-…（owner=工单平台组）
  ✓ 新知识候选：NOT_ELIGIBLE（阻塞原因：POSITIVE_REPLAY_REQUIRED）
```

### 闸门 6 是这条脚本存在的理由

**「人工批准只推进状态机，不触发执行」是整个产品安全论证的支点。**
在这条脚本之前，它只有**拒绝**那一半被演示过——`POST /execute` 返回 409。
**肯定**那一半——批准之后动作变成 `APPROVED_NOT_EXECUTED`，而 `executionStatus`
仍然是 `BLOCKED`，什么都没跑——在 HTTP 边界上从来没有被走过一次，
因为**两条 Playbook 都没有 `MANUAL_WRITE` 动作**。

现在 `csdp:903001` 带上了一个。它的定位也因此变了：不再是跟真实数据的 IM1010
争可信度的"第二条知识"，而是**专门用来行走这条红线的夹具**。
（`ManualPlaybookReplaySuiteCatalogTest` 同时锁住反面：IM1010 不得被塞进
手写的生产写动作——那等于往唯一一条证据来源的知识里掺进一条编的指令。）

---

## 1.7 第四次：从一句现象走到可确认的结论

前三条都从一个 **errorCode** 或从知识生产开始。真正常见的报障不带错误码，
而那条 lane 中间有个洞：选了场景会开出一个「等取证」的 Diagnosis，
却没有任何东西去跑它的取证计划。部署拓扑因为有自己的探针入口，
成了**唯一**能走完的场景，也正因此这个洞一直看不见。

```bash
MATECLAW_USERNAME=admin MATECLAW_PASSWORD=admin123 \
    ./scripts/troubleshooting-scenario-evidence-smoke.sh
```

实测输出：

```text
  ✓ 已开案：diag-…（INSUFFICIENT_EVIDENCE / NEEDS_INVESTIGATION）
  ✓ 取证前确认被拒（409）：abstained diagnosis requires new evidence before confirmation
  ✓ 取证已执行：3 条证据
  ✓ 取证后已确认：READY_FOR_HUMAN → CONFIRMED
  ✓ 结论出自 Playbook：消息发送路径异常待核查
  ✓ 引用恰好等于取到的证据：SYNTH-CONTRAST-SAMPLE, SYNTH-LOG-SEARCH, SYNTH-TRACE-BUNDLE
  ✓ 重跑被拒（409）：this investigation is no longer waiting for evidence…
```

### 闸门 2 是这条脚本存在的理由

**取证之前 `confirm` 必须被拒。** 没有这一格，第 3、4 格就是在一个从未卡住的系统上
通过的——那和"修好了"在输出上完全一样，修复也就无从证伪。

闸门 6 有过一次真实的教训：它最初查的是 `citedEvidence`，而响应里的字段叫
`evidenceCitations`。查错字段返回空清单，"没有 MISSING 被引用"于是永远成立，
这道闸门空转了一整轮才在实跑响应里被抓到。现在它两个方向都查：
引用必须**恰好等于**非 MISSING 的取证，空清单直接判失败。


## 2. 测试专用的 `troubleshooting-demo` profile 做了什么（以及没做什么）

这套 Seeder、录制模型响应和 profile 已全部移入 `src/test`，**不会进入生产 Jar**。
本地验收与 CI 会把 3 个夹具组件、1 个自动配置入口和 3 个资源打成专用
`troubleshooting-demo-fixture.jar` 后加载；
不会把整个 `target/test-classes` 暴露给组件扫描。
正式运行时仍只保留 Recorded Replay 适配器本身，不会自动种数据，也不会替代真实模型。

| 做了 | 没做 |
|---|---|
| 打开 **Recorded Replay** 证据源 | **不碰** Guance：demo 绝不能意外访问真实观测源 |
| 把 CSDP 的六个 signalKind 路由到 recorded-replay | 不改任何真实 workspace 的配置 |
| 从服务端目录种 `csdp:903001` 与 `csdp:IM1010` 并逐条走完晋升 | 不改 `fixtureMode`：产出仍全程标记 fixture |
| 用**录制的模型响应**替换无码路那一次模型调用 | 不跳过归纳、不假装模型在线：只替换"模型说了什么" |

### 晋升是走出来的，不是改状态位改出来的

第一版种子做的是"注册后把 status 改成 approved"，**它在运行时被直接拒了**：

```text
[ts-demo] demo seeding skipped:
  candidate approval requires the eligibility gate and must create a new version
```

这个拒绝是对的。现在种子做的是评审人做的那套动作：

1. 从 server-owned replay catalog 读取候选并以 `candidate` 注册（Seeder 不再复制 Java Playbook）；
2. 903001 跑固定套件；IM1010 从安全有界的录制正例生成判据形状排除例和缺证据弃权例，
   两者执行同一套正/负回放门，不降低错误码路资格；
3. 回放 `PASSED` 后，资格快照才变成 `ELIGIBLE_FOR_APPROVAL`，
   再 `start` + `approve` 一次知识评审，晋升出 Playbook v1。

**回放不过就不晋升**，路由保持缺失，冒烟脚本会照实报告。

审计台账里的 `approvedBy` 是 `ts-demo-seeder`，不是任何人的名字——
读台账的人一眼就能看出这条知识没有人审过。

**这不是降低安全标准，是把两件事分开**：「有没有一条可走的路」和「真实证据可不可信」
本来就是两个独立判断，此前被同一批开关捆在了一起。

---

## 3. 这次跑通证明了什么、没证明什么

**证明了**：接入 → 路由命中 → 取证 → 判据求值 → 规则裁决 → 结论 → 双投影 → 三段耗时，
这条链路在真实的 HTTP 边界上可走，且 fail-closed 的门是可以被**正常打开**的——
包括那道最难的：手工 Playbook 必须先有回放证明才能晋升。

**此前暴露并已由 D19 关闭的机制缺口**：最初仓库里唯一的回放套件是
`csdp:scenario:deployment_topology_probe`。也就是说**任何错误码 Playbook 都没有晋升路径**——
产品的主干形态恰好是那个走不通的。现在每条错误码只需一份安全聚合正例，反例/弃权按封闭判据
词汇生成；坏生成种子按 selector 隔离，固定套件仍 fail-fast。其余 145 条错误码仍待分批导入，
不能把机制完成冒充全量知识覆盖。

**还暴露了一个更隐蔽的**：第一次跑通时前七道闸门全绿，结论却是 `INSUFFICIENT_EVIDENCE`。
报障里的 service 写成了 `csdp-order-service`，而回放样本按
`(system, errorCode, service, requestId)` 精确匹配，于是三条证据全部 MISSING、
四条判据全部 UNEVALUATED。**"证据不足"同时也是系统在真实缺证据时的正确输出**，
从外面完全分不出是链路断了还是真没证据。第 8 道闸门就是为此加的。

**没有证明**：证据可信。全程 Recorded Replay，`fixtureMode` 恒 `true`。
真实观测云的 measurement / 字段 / 阈值核实仍是 **T7**，需要内网窗口。

到 T7 那天，操作员的动作是**把 demo 的绑定换成真实 Guance 绑定**——一次替换，
而不是在有时限的内网环境里从零配置。这正是这条 quickstart 想省下的那次失败。

---

## 4. 持续回归与耗时指标

我们量了"客户从报障到拿到结论要多久"（北极星），却从没量过
**自己从 clone 到看见一次诊断要多久**。

`.github/workflows/troubleshooting-smoke.yml` 已把这条路径挂进 CI，作为
"默认路径没有被堵死"的回归。它在相关 PR、`dev`、主干、当前设计分支推送或手工触发时：

1. 配置 Java 21，以 Maven 标准配置和 `-am` 把父 POM 与
   `mateclaw-plugin-api` 一并安装进本地 Maven 仓库；
2. 只打包并加载专用 Demo fixture Jar，用 H2 默认库和 `dev,troubleshooting-demo` 启动服务，最多等待 120 秒，直到
   `csdp:IM1010` 已通过真实 demo 晋升链成为 approved Playbook；
3. 运行同一份 `scripts/troubleshooting-smoke.sh`（命中路八道闸门）；
4. 再运行 `scripts/troubleshooting-miss-path-smoke.sh`（学习环九道闸门）。
   **一条绿的诊断环配一条死的学习环不算绿**：新 Playbook 只能从无码路来，
   这一步掉了就等于把知识供给悄悄退回"人读表格手写"；
5. 再运行 `scripts/troubleshooting-scenario-smoke.sh`（单案十道闸门）。
   前两条各自停在环的前半段；这一条把一个案子走到关闭，
   并且是唯一一处真正行走「批准≠执行」肯定半边的地方；
6. 最后运行 `scripts/troubleshooting-scenario-evidence-smoke.sh`（现象 lane 七道闸门）。
   前三条都从错误码或知识生产开始；这一条从一句现象开始，
   证明**任意**场景都能跑完取证并给出可确认的结论，而不只是自带探针入口的拓扑；
7. 把服务日志和四份冒烟输出作为 `troubleshooting-smoke-logs-*` artifact 保留；
8. 在 checkout 前启动计时，并在脚本首次读到 `diagnosisId` 时落下终点；Step Summary 记录
   `clone → 首次诊断` 耗时，超过 300 秒发 warning，不把后续投影校验耗时冒充为首诊耗时。

超过五分钟只表示启动速度回退，不伪装成产品正确性失败。链路闸门失败才让任务失败；脚本还会
fail-closed 校验开发证据非空、北极星补问/调查已记录，以及未发生人工采纳时第三段仍为 `null`。

---

## 5. 本地验证 workflow 合同

不启动服务也可以检查 CI 关键合同有没有被改坏：

```bash
bash scripts/ci/test-troubleshooting-smoke-workflow.sh
```

它会检查触发范围、Java 版本、Maven 不强制仓库级镜像、plugin API 构建顺序、demo profile、
有限等待、四条冒烟的入口与先后顺序、300 秒目标、清理步骤和无条件日志上传，并断言无码路脚本
仍带着 `NOT_ELIGIBLE` 与 `CANDIDATE_REUSED` 两道反向断言、场景脚本仍断言
`APPROVED_NOT_EXECUTED` 之后 `executionStatus` 必须仍是 `BLOCKED`、
现象 lane 脚本仍保留「取证前不得确认」这一格与 `evidenceCitations` 的双向比对。
这个静态合同不能代替 GitHub runner 实跑。

---

## 6. 相关文档

- 命中路闸门：`./scripts/troubleshooting-smoke.sh --gates`
- 无码路闸门：`./scripts/troubleshooting-miss-path-smoke.sh --gates`
- 单案场景闸门：`./scripts/troubleshooting-scenario-smoke.sh --gates`
- 现象 lane 闸门：`./scripts/troubleshooting-scenario-evidence-smoke.sh --gates`
- 真实 Guance 接入：`evidence-adapter-runbook.md`（T6/T7）
- 未命中路 Agent：`agent-miss-path-runbook.md`
- 现行架构：`../../rfcs/intelligent-troubleshooting-architecture-v4.md`
