# Quickstart：从零到看见一次诊断

> 目标：**新克隆的仓库 → 明确的启动步骤 → 一份可读的诊断**，全程 fixture。
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
mvn --settings mateclaw-server/settings.xml \
    -pl mateclaw-plugin-api -am -DskipTests install

mvn --settings mateclaw-server/settings.xml \
    -pl mateclaw-server -DskipTests spring-boot:run \
    -Dspring-boot.run.profiles=dev,troubleshooting-demo

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

## 2. `troubleshooting-demo` profile 做了什么（以及没做什么）

| 做了 | 没做 |
|---|---|
| 打开 **Recorded Replay** 证据源 | **不碰** Guance：demo 绝不能意外访问真实观测源 |
| 把 CSDP 的六个 signalKind 路由到 recorded-replay | 不改任何真实 workspace 的配置 |
| 从服务端目录种 `csdp:903001` 与 `csdp:IM1010` 并逐条走完晋升 | 不改 `fixtureMode`：产出仍全程标记 fixture |

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
"默认路径没有被堵死"的回归。它在相关 PR、`dev` 推送或手工触发时：

1. 配置 Java 21，通过仓库级 `mateclaw-server/settings.xml` 使用阿里云公共镜像，
   再以 `-am` 把父 POM 与 `mateclaw-plugin-api` 一并安装进本地 Maven 仓库；
2. 用 H2 默认库和 `dev,troubleshooting-demo` 启动服务，最多等待 120 秒，直到
   `csdp:IM1010` 已通过真实 demo 晋升链成为 approved Playbook；
3. 运行同一份 `scripts/troubleshooting-smoke.sh`，八道闸门任一道失败都会让 job 失败；
4. 把服务日志和冒烟输出作为 `troubleshooting-smoke-logs-*` artifact 保留；
5. 在 Step Summary 记录 `checkout 完成 → 首次诊断` 耗时，超过 300 秒发 warning。

这个 CI 数值是 clone→首次诊断的第一版代理指标，不包含 checkout 本身。待积累真实 Actions
运行历史后，再把完整 clone 时间纳入基线；在此之前不把五分钟目标写成已验收。

---

## 5. 本地验证 workflow 合同

不启动服务也可以检查 CI 关键合同有没有被改坏：

```bash
bash scripts/ci/test-troubleshooting-smoke-workflow.sh
```

它会检查触发范围、Java 版本、阿里云 Maven 镜像、plugin API 构建顺序、demo profile、
有限等待、八闸门入口、300 秒目标、清理步骤和无条件日志上传。这个静态合同不能代替
GitHub runner 实跑。

---

## 6. 相关文档

- 闸门与失败原因：`./scripts/troubleshooting-smoke.sh --gates`
- 真实 Guance 接入：`evidence-adapter-runbook.md`（T6/T7）
- 未命中路 Agent：`agent-miss-path-runbook.md`
- 现行架构：`../../rfcs/intelligent-troubleshooting-architecture-v4.md`
