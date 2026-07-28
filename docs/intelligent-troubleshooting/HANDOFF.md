# HANDOFF · IT 智能排障 on MateClaw

> 更新时间：2026-07-28
>
> 仓库：`webonne/mateclaw`
>
> 分支：`claude/intelligent-troubleshooting-design`
>
> 当前架构：`rfcs/intelligent-troubleshooting-architecture-v4.md`
>
> 架构评审：**APPROVED FOR P1 IMPLEMENTATION**
>
> 第一性原理评价与修订：`architecture-critique-v4.md` —— 用户已认可，v4 现为 **v4.1 / 蓝图 v0.11**

## 1. 一句话

MateClaw 智能排障的中心是一条“报障上下文 → 只读取证 → 可引用诊断”的证据脊柱；它同时服务在线排障和
知识生产两个闭环。当前第一枪是会议指定的无错误码案例“会话消息发送失败”，不是继续扩 903001 页面。

## 2. 为什么重新定架构

2026-07-27 的 28:30 录音明确：团队真正的差异化是能使用观测云日志，经 PS ID 还原全链路，再由 AI 形成
根因假设和排查步骤。旧 v3 把日志→SOP 降成辅助能力、只把错误码当主轴、把 Web 当主要入口，和录音不符。

现已建立两个现行事实文件：

1. `recording-product-baseline.md`：F1–F11，区分会议事实和讨论脑暴；
2. `intelligent-troubleshooting-architecture-v4.md`：把事实推成工程合同。

旧 `architecture-v2.md`、`v3.md` 和 `meeting-change-plan.md` 仅用于追溯。

## 3. 已锁定架构决定

| # | 决定 |
|---|---|
| D1 | 产品中心是一条共享 Evidence Spine；在线诊断与知识生产是两个一等闭环 |
| D2 | 权威 Playbook 只分 ERROR_CODE / SCENARIO；OPEN_DISCOVERY 用独立 DiscoveryPolicy |
| D3 | `investigationMode` 与 `routeAuthority` 分开；模型提议不能伪装成确定性命中 |
| D4 | 错误码 approved Playbook 命中路保持零 LLM |
| D5 | PlaybookDraft 可在 outcome 前产生，但不满足按 origin 定义的资格不得 approved |
| D6 | 在线诊断与知识合成复用同一 Evidence Router/Adapter，不建第二套取证 |
| D7 | 企微群 @ 是主要一线入口；Web 用于开发证据、处置和知识审核 |
| D8 | 一份 Diagnosis 生成 BusinessSummary 与 DeveloperEvidenceView，不建两套事实 |
| D9 | 自动化永久止于只读；生产写只在系统外由人完成并登记 outcome |
| D10 | 所有能力继续在当前 Java MateClaw 运行，不引入第二运行时 |
| D11 | Agent 仍只看到唯一只读证据门面；内部按语义 Tool 与来源 Adapter 两层 SPI 插拔 |
| D12 | Loop Engineering 是一等控制机制；调查内循环与知识外循环都有显式状态、预算、验证和停止原因 · **PENDING-EVIDENCE** |
| D13 | 多 Agent 只做固定角色、固定一轮的结构化反证；先影子后治理，永不以共识/投票取得裁决权 · **PENDING-EVIDENCE** |
| **D5′** | `EVIDENCE_DERIVED` 晋升分校准期 / 运行期两档；退出校准期靠样本数据而非日期 |
| **D14** | 北极星用四个时间戳度量，三段差值分开统计 |
| **D15** | 证据合成必须取成功样本对照；缺失只降级不失败，且锁定校准期档 |
| **D16** | 未被真实失败检验过的设计分支标 `PENDING-EVIDENCE`，不得据以新增实现、接口或表结构 |

修改 D4、D5/D5′、D9 必须单独 RFC 并由用户明确确认。
D12/D13 当前为 `PENDING-EVIDENCE`：在 P2 真实样本给出失败模式之前，不得据其新增实现。

**红线不在本文维护。** 唯一权威清单是 v4 §9；本文与 TODO 只引用，不复述条目
（此前四处各写一遍且条数措辞不一，见 `architecture-critique-v4.md` §2.5）。

蓝图已升级到 v0.8。该增量不扩大 P1：当前仍是一轮 PlaybookDraft 归纳 + 确定性校验；P2 才在历史样本上
影子运行 Evidence Challenger / Safety Challenger，P4 才为 SCENARIO / OPEN_DISCOVERY 引入 Loop Control。

## 4. 当前代码真实状态

### 已完成

- Java 领域模块 `vip.mate.troubleshooting`、REST、RBAC、三方言 Flyway、状态机和持久化。
- 903001 确定性错误码竖线，命中路零 LLM。
- 受限 Agent miss-path：唯一只读证据工具、服务端会话、硬白名单、引用校验、abstain。
- `EvidenceSourceRouter`，Guance 与 Recorded Replay 两个 Adapter，canonical schema 和脱敏。
- 后续扩展已锁定为域内 `ReadOnlyEvidenceToolRegistry → Tool SPI → EvidenceSourceAdapter SPI`；当前尚未实现 Registry，不能把目标设计写成已完成代码。
- `log_search` / `log_trace_bundle`，PS ID 一致性、时间排序、行数/字符/时间窗边界。
- `DeterministicLogTraceCompressor`。
- `SopSynthesisService.preview()`：fixture scope 中跑到 `READY_FOR_MODEL`，不调模型、不入 candidate。
- Diagnosis 人工处置闭环与 Vue 工作台。
- KnowledgeCandidate 与 Outbox 发布语义；尚无独立审核语义。
- 三套只读 Demo 原型，均显式显示 Recorded Replay、MODEL_PROPOSED、MEDIUM、CANDIDATE。

### 尚未完成

- PlaybookDraft 结构化模型归纳。
- 引用/selector/动作/secret/DQL 的确定性 Validator。
- 与人工参考解法的结构化比较和固定 replay eval。
- Candidate generationKey 幂等与独立 review status。
- 真实 Guance measurement/字段/PS ID/阈值内网验证；`fixtureMode` 仍应为 true。
- 企微 IntakeSession 和原路闭环。
- Scenario Playbook Registry 与 DiscoveryPolicy。

## 5. Demo

开发环境路由只在 Vite dev 模式存在，不影响生产构建和真实 `/troubleshooting` 权限：

- A 服务经理摘要：`http://127.0.0.1:5173/prototype/troubleshooting?variant=A`
- B 开发证据台：`http://127.0.0.1:5173/prototype/troubleshooting?variant=B`
- C 企微协同流：`http://127.0.0.1:5173/prototype/troubleshooting?variant=C`

推荐组合：A 做默认摘要，B 做折叠后的开发证据，C 用于说明真实入口和补问/闭环。最终仍由用户看过后选择。

原型文件：

- `mateclaw-ui/src/views/Troubleshooting/prototype/TroubleshootingExperiencePrototype.vue`
- `/prototype/troubleshooting` 是 dev-only publicPrototype 路由；生产构建不注册。
- 正式 `/troubleshooting` 鉴权和 capability gate 未放宽。

## 6. 当前主攻 P1

```text
SopSynthesisService.preview()              已有
  → PlaybookDraftInducer                   待做，最多一个模型调用
  → PlaybookDraftValidator                 待做，确定性信任边界
  → ReferenceSolutionComparator            待做，纯函数优先
  → candidate + generationKey              待做，不可 approved

并行补两件 v4.1 要求的小事（T4.5）：
  contrast_sample 成功样本对照           待做，缺失只降级不失败
  四个北极星时间戳                        待做，fixture 样本也要记
```

P1 最多新增两个 service seam：模型归纳、确定性校验。不要一次创建 Planning、Projection、WeCom、新状态机、
消息队列、Loop Controller、Challenger 或八个目标模块。

验收案例必须是“会话消息发送失败（无 error_code）”。比较采用 requiredStepIntents、forbiddenStepIntents、
orderingConstraints、requiredEvidenceKinds，不做逐字相似度。

## 7. 安全与信任边界

**唯一权威清单：`rfcs/intelligent-troubleshooting-architecture-v4.md` §9。**
本文不再复述条目——同一批约束此前在 v4 §1.2、v4 §9、本文和 TODO 各写一遍且互不一致
（见 `architecture-critique-v4.md` §2.5）。动手前读 v4 §9；要改红线也只改那里。

## 8. 验证现状

本轮三套原型已通过：

- `vue-tsc --noEmit`
- 直接 Vite production build
- 浏览器 A/B/C DOM 与视觉冒烟

注意：`npm run build` 的前置脚本引用缺失的 `../scripts/check-snowflake-precision.sh`，因此 wrapper 会在执行
Vite 之前失败；直接 `vue-tsc` 和 Vite build 均通过。这是仓库已有构建脚本缺口，不是原型代码错误。

后端定向测试命令：

```bash
mvn -pl mateclaw-server -am \
  -Dtest='vip.mate.troubleshooting.**.*Test' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

## 9. 接手顺序

1. 先读 `recording-product-baseline.md`、架构 v4、架构评审、TODO。
2. 确认用户对 A/B/C 的选择；未选择前不吸收正式页面。
3. 顺序做 P1 T1→T5（含 T4.5 对照与时间戳）；合成模块共享文件，不建议并行 worktree。
4. P1 eval 通过后，P2 真实 Guance 与 P3 企微可并行。
5. 真实样本稳定后再实现 Scenario Registry/Planning；不要先搭空平台。

## 10. 不要做

- 不再引用已确认属于其他项目的旧架构材料，后续只使用 MateClaw。
- 不把 v2/v3 或下载目录里的旧蓝图当现行设计。
- 不把五类 FaultClass 写成录音已定要求。
- 不让模型猜 error code 后进入 deterministic route。
- 不把内部思维链展示给开发，只展示证据、判据和可复算推导。
- 不擅自开 PR，不提交包含真实 token/IP/人名的源表。
