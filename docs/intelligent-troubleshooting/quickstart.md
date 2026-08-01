# Quickstart：从零到看见一次诊断

> 目标：**新克隆的仓库 → 一条命令 → 一份可读的诊断**，全程 fixture。
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
# 终端 A：带 demo profile 启动（默认关闭，必须显式打开）
./mvnw -pl mateclaw-server spring-boot:run \
    -Dspring-boot.run.profiles=troubleshooting-demo

# 终端 B：走一次完整路径并断言结果
export MATECLAW_TOKEN=<具备 operate:troubleshooting 的 PAT，mc_ 前缀>
./scripts/troubleshooting-smoke.sh
```

通过时它会打印结论类型、结论、开发证据步数，以及**北极星三段耗时**。
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
| 种一条 `csdp:903001` 的 Playbook 并批准 | 不改 `fixtureMode`：产出仍全程标记 fixture |

种子走的是**同一个** `TroubleshootingSopPersistenceService`，注册与晋升的全部不变量照常生效；
Playbook 以 `candidate` 注册再显式推进到 `approved`，动作里没有任何 `MANUAL_WRITE`。

**这不是降低安全标准，是把两件事分开**：「有没有一条可走的路」和「真实证据可不可信」
本来就是两个独立判断，此前被同一批开关捆在了一起。

---

## 3. 这次跑通证明了什么、没证明什么

**证明了**：接入 → 路由命中 → 取证 → 判据求值 → 规则裁决 → 结论 → 双投影 → 三段耗时，
这条链路在真实的 HTTP 边界上可走，且 fail-closed 的门是可以被正常打开的。

**没有证明**：证据可信。全程 Recorded Replay，`fixtureMode` 恒 `true`。
真实观测云的 measurement / 字段 / 阈值核实仍是 **T7**，需要内网窗口。

到 T7 那天，操作员的动作是**把 demo 的绑定换成真实 Guance 绑定**——一次替换，
而不是在有时限的内网环境里从零配置。这正是这条 quickstart 想省下的那次失败。

---

## 4. 建议纳入的一个指标

我们量了"客户从报障到拿到结论要多久"（北极星），却从没量过
**自己从 clone 到看见一次诊断要多久**。

现在这个数第一次变得可测。它会持续暴露"闸门合取"这类问题——
每加一道需要人工配置的门，这个数就会变长，而 `troubleshooting-smoke.sh`
会立刻变红。建议把它挂进 CI，作为"默认路径没有被堵死"的回归。

---

## 5. 相关文档

- 闸门与失败原因：`./scripts/troubleshooting-smoke.sh --gates`
- 真实 Guance 接入：`evidence-adapter-runbook.md`（T6/T7）
- 未命中路 Agent：`agent-miss-path-runbook.md`
- 现行架构：`../../rfcs/intelligent-troubleshooting-architecture-v4.md`
