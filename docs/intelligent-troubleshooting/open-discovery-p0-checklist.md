# 开放调查兜底 P0 · 启用清单

> 更新：2026-08-12
>
> 目标：**夜间未知告警能安全进入受限 OPEN_DISCOVERY**，不是多轮自主规划。
> 结论最高 MEDIUM；证据不足弃权；生产写仍为 0。

## 1. 先看就绪，再开开关

```http
GET /api/v1/troubleshooting/open-discovery/readiness?system=CSDP
X-Workspace-Id: <workspace-id>
```

| status | 含义 |
|---|---|
| `DISABLED` | 开关关着；按手册建 Agent 后再开 |
| `BLOCKED` | 开关开了但 Agent/预算/工具绑定不合规；看 `blockers` |
| `READY_FOR_REHEARSAL` | Agent 可用，计划仍只允许 `recorded-replay` |
| `READY_FOR_BOUNDED_FALLBACK` | 计划已允许真源平台（如 guance），可作夜间兜底 |

正式工作台「通用事件排障」在无错误码时也会展示同一投影。

## 2. 启用顺序（与 agent-miss-path-runbook 一致）

1. 创建专用 ReAct Agent：skills/wiki 关、唯一模型、只绑 `TroubleshootingEvidenceTool`
2. 配置：
   ```bash
   MATECLAW_TROUBLESHOOTING_AGENT_ID=<id>
   MATECLAW_TROUBLESHOOTING_AGENT_ENABLED=false   # 先关着复核
   ```
3. `scripts/run-troubleshooting-dev.sh --check`（本地）或部署配置复核
4. 最后才：
   ```bash
   MATECLAW_TROUBLESHOOTING_AGENT_ENABLED=true
   ```
5. 接真源窗口后（可选）：
   ```bash
   MATECLAW_TROUBLESHOOTING_AGENT_EXTRA_PERMITTED_PLATFORMS=guance
   ```
   会把 `guance` 合并进所有 approved scenario plan，无需改计划 key。

## 3. 已审核计划目录（当前仓库默认）

| key | system | 默认平台 |
|---|---|---|
| `message_send_failed` | CSDP | recorded-replay |
| `itgw_access_failed` | ITGW | recorded-replay |

模型**只能**从当前 Workspace + 系统可见的 key 里选一个；选不上或证据不足 → 弃权转人工。

## 4. 演练

发一条无 SOP / 无错误码的脱敏演练 Incident（见 `agent-miss-path-runbook.md` §5）。  
期望：`OPEN_DISCOVERY` + `MODEL_PROPOSED`，置信度 ≤ MEDIUM，或明确弃权；开关关闭时 409 fail-closed。

## 5. 明确不做

- DiscoveryPolicy / 多轮 Loop / 自由组合 K8s·HCI·DQL
- 把 OPEN_DISCOVERY 注册成 Playbook 或抬到 HIGH
- 用本清单冒充 T7/T8 已通过
