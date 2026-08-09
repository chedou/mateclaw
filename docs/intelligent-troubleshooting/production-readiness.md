# 投产清单 · IT 智能排障系统

> 更新时间：2026-08-09
>
> 这份清单只回答一个问题：**在第一条真实报障进来之前，哪些事必须为真。**
> 每一格都写明「谁做」「怎么验证」，以及**做不到时系统会怎么表现**——
> 后者比前者重要：一个悄悄给出错误答案的系统，比一个明确说自己没准备好的系统危险得多。

---

## 一句话现状

**挡住整个平台投产的不是 CTI 单场景代码，而是两件需要人的事**：20–30 条真实录制目标要由懂
Guance 的 owner 填，以及一次完成当前 binding 指纹验收的内网窗口。2026-08-08 已有一张正式 CTI
Diagnosis 持久化跑通三段 Guance-only 取证；它证明这条场景能运行，但不能替代批量目标和 owner 验收。

```
录制目标   0 / 20        ← 阻塞，离线可做，不用等窗口
默认部署   Guance DISABLED ← 阻塞，需要内网窗口显式配置
本地 CTI   1 条持久化真源竖线 ← 已观测，不等于 T7/T8 通过
其余       已就绪
```

---

## A. 必须在窗口之前完成（离线，不需要内网）

### A1. 填满 20–30 条录制目标 ← **唯一的关键路径**

- **谁**：懂 Guance schema 的 owner（measurement、字段、阈值）。
- **做什么**：填 `t7-owner-contract-intake.template.json`，服务端据此冻结成
  server-owned 可执行 target。
- **怎么验证**：
  ```bash
  bash scripts/troubleshooting-t7-preflight.sh   # 第 5 格
  ```
- **为什么不能由我们代填**：预检里写明「**不能自造查询映射**」。编出来的
  measurement/字段会一路通过所有闸门，然后在真实故障上给出看起来合理的错误答案
  ——那正是 A6 与 A13 要挡的事。
- **做不到会怎样**：owner 验收无法提交（`GuanceEvidenceAcceptanceService` 会拒），
  真源采样闸门保持关闭。**系统不会假装能取证**。

### A2. 决定首批上线的 selector 范围

- 目前有 8 条已审核 Playbook（`csdp:*`）。建议第一批只放**其中 2–3 条**最熟悉的。
- 新系统接入路径已通（无需改发布物）：注册 Playbook → 审核 → 声明取证路由。
  详见 TODO 的 P2.0 / P2.1 段。

---

## B. 内网窗口当天

按 `scripts/troubleshooting-t7-preflight.sh --gates` 的七格逐条走。**先跑预检再动手**
——把一次窗口浪费在配置上，比什么都贵。

| 格 | 内容 | 卡住时的表现 |
|---|---|---|
| 1 | 服务可达且能认证 | 预检自己跑不起来 |
| 2 | Guance adapter 已启用（端点 + 受控运行时 Key） | health `DISABLED`，不会被路由选中 |
| 3 | 三个核心 signal 已路由到 Guance | 取证回 `router:unconfigured`，并报出下一步 |
| 4 | binding 指纹可唯一计算 | 没有东西可供 owner 验收 |
| 5 | 20–30 条录制目标（见 A1） | 验收被拒，附当前数量 |
| 6 | owner 验收 `ACCEPTED` | `NOT_ACCEPTED` / `STALE` |
| 7 | 真源采样闸门（**验收前期望它关着**） | 它开着才是问题 |

> 第 2 格与第 7 格是反向的：本机跑，2 必须停、7 必须关。
> **一个在什么都没配的机器上还能全绿的预检，是空转的闸门。**

---

## C. 第一天的安全性（已实现，无需额外操作）

这些是「真源接通那一刻」自动生效的，列在这里是为了让你知道系统会怎么表现。

| 保障 | 行为 | 在哪 |
|---|---|---|
| **未标定知识不得声称 HIGH** | 阈值不是来自录制聚合时，`LOCATED` 封顶 `MEDIUM`，并附一条说明理由的 warning | `PlaybookEvidenceAssessment.cap` |
| 证据成色自动推导 | 接上真源自动变真；混进一条夹具则整批算夹具 | `EvidenceProvenance` |
| 知识成色独立显示 | 「真实数据校准」弃权**不看** fixtureMode，接上真源后仍然挂着 | `InvestigationProvenanceService` |
| 平台不执行生产写 | 批准只推进状态机，`executionStatus` 仍为 `BLOCKED` | 场景冒烟闸门 6 |
| 未命中路 Agent 默认关 | 开启后其建议也封顶 `MEDIUM` | `TroubleshootingAgentProperties.enabled` |
| 跨租户路由隔离 | workspace 声明优先于部署级 YAML（后者只按 system 名字索引） | `EvidenceSourceRouter.routeFor` |

**第一条特别重要**：证据成色会自己变真，**知识成色不会**。少了封顶，第一天系统就会
拿从没被任何真实故障检验过的阈值输出 `LOCATED / HIGH`，而服务经理看到 HIGH 会当成
系统有把握。同一个代码库对**模型**的建议早就封顶到 MEDIUM——一条从没被检验过的阈值，
没有理由比模型的猜测更有底气。

---

## D. 上线后第一周要盯的

1. **北极星读数**：`GET /api/v1/troubleshooting/evaluation-samples/north-star`
   目前 `sampleCount: 0`。三段（补问 / 调查 / 采纳）必须分开看，它自己会声明
   「机器耗时不含人复核时间」。
2. **结案候选**：每关一个案子会产出一条 `OUTCOME_BACKED` 候选。它们目前**不能**被
   批准成知识（`NO_ROUTEABLE_PLAYBOOK_PROJECTED`），这是已知且刻意的——见 TODO
   「A 方案进行中」。它们是未来的回放材料，先攒着。
3. **封顶警告的出现率**：只要还有 `MEDIUM` + 「从未标定」警告，就说明知识仍未被
   真实故障检验过。这条警告消失的那天，才是知识真正成熟的信号。

---

## E. 明确不在投产范围内

- 自动重启 / 扩容 / 切流 / 改配置 / 改数据 / 改代码（A5，架构 §1.3）
- 让模型猜错误码后进入确定性命中路
- 多 Agent 辩论后以票数决定根因
- 结案候选自动变成已生效知识（需要先有真实案例，见 TODO）
