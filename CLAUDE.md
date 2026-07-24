# CLAUDE.md

本仓库是 **MateClaw**（太一 · Agent Harness，Spring Boot 3.5 / Java 21 / Vue 3）。
当前的**活跃工作**：在 MateClaw 之上落地 **IT 智能排障系统**（首个域 CSDP 工单/客服链路），
形态为 mateclaw-server 内的确定性领域模块 `vip.mate.troubleshooting`。

## 接续这项工作，先读（两份即可上手）

1. **`docs/intelligent-troubleshooting/HANDOFF.md`** —— 会话记忆：8 个已锁定决策（D1–D8）、
   四条红线、当前阶段矛盾分析与刷新后的代办、指针与安全口径。
2. **`rfcs/intelligent-troubleshooting-design.md`** —— 现行架构设计（逐条 mateclaw 源码核对通过；
   含源码位置索引 §12、实施清单 §13、实施战略 §14）。

## 关键约束（细节见 HANDOFF §3）

- 命中路零 LLM（Workflow 每步调 LLM，故命中路必须是领域模块，不能是 native Workflow）。
- 生产写工具永不注册；ToolGuard 批准=回放执行，与"批准但不执行"语义相反，人工确认只推进领域状态机。
- 写操作永远外部人工 + 结果登记；未命中路 agent 锁死只读。
- `l0/sop_kb.json` 已脱敏；源表 xlsx 含真实 token/IP/人名，未入库、不得入库。

## 方法论 skills

`.claude/skills/` 装有 qiushi-skill（矛盾分析/集中兵力/持久战/群众路线/批评与自我批评等），`/<name>` 调用。

## 纪律

- 排障工作当前分支 `claude/intelligent-troubleshooting-design`；以用户当前明确选择的分支为准。
- 不擅自开 PR；改 RFC 保持 § 编号连续。
- 旧仓库 **webonne/MetaClaw** 已归档为只读参考（Python MVP 参考实现在其 `zhinengpaizhang-dev` 分支），
  一切新工作只在本仓库进行。
