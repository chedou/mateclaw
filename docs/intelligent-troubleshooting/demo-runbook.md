# 演示操作卡（黑客松 / 对外路演）

> 口径：受控试点能力演示，**不承诺投产**。  
> 环境：后端 `http://127.0.0.1:18088` + 前端 `http://127.0.0.1:5173`，账号 `admin` / `admin123`，`X-Workspace-Id: 1`。  
> 启动：加载 `.env.guance.local` 与 `SPRING_PROFILES_INCLUDE=csdp-guance-evidence-pilot`。

统一纪律：

- `fixtureMode=false` 才能说「真源」；否则说「回放夹具」。
- LOCATED / MEDIUM =「定位到环节待核查」，不是「根因已定」。
- 系统**不**自动改生产；人确认后才交接。
- 上场前先跑：`./scripts/troubleshooting-guance-sendmsg-demo.sh --preflight`

---

## U1 · SendMsg 无错误码竖线（主菜）

| 项 | 内容 |
|---|---|
| 入口 | Web：`/troubleshooting` → 场景排障 →「会话消息发送失败」→ 开案 → **跑取证** → 确认 |
| 脚本 | `MATECLAW_USERNAME=admin MATECLAW_PASSWORD=admin123 ./scripts/troubleshooting-guance-sendmsg-demo.sh` |
| 期望 | `LOCATED` / `MEDIUM` / `fixtureMode=false`；confirm 200；projection 可读 |
| 失败下一刀 | `--preflight` 看 spine：`failureMatchCount=0` 则对照特征漂了，先核 `message_length` 再改 pilot 合同 |

## U2 · 错误码命中（零 LLM）

| 项 | 内容 |
|---|---|
| 入口 | Web：通用报障填 `errorCode`；或脚本 |
| 真源优先 | ITGW **904003**（`csdp-wechat`，`occurredAt=2026-08-07T09:12:00Z`） |
| fixture 备用 | `troubleshooting-smoke.sh`（IM1010；需 `troubleshooting-demo` + recorded-replay，Guance pilot 档可能不可用） |
| 期望 | `ERROR_CODE_PLAYBOOK` + `LOCATED` + `fixtureMode=false`；confirm 200 |
| 失败下一刀 | 历史窗空 → 核对保留期；勿用「现在」当 occurredAt |

## U3 · 开放调查兜底

| 项 | 内容 |
|---|---|
| 入口 | Web：通用事件排障，**无错误码、不选已注册场景**；看就绪条 |
| 检查 | `GET /api/v1/troubleshooting/open-discovery/readiness?system=CSDP` |
| 脚本 | `./scripts/troubleshooting-demo-verify.sh u3` |
| 期望 | readiness ≠ `BLOCKED`；结论 ≤MEDIUM / 假设或弃权；需人工 |
| 失败下一刀 | `DISABLED`/`BLOCKED` → 按 `open-discovery-p0-checklist.md` 配 Agent |

## U4 · 取证接入一页讲完

| 项 | 内容 |
|---|---|
| 入口 | `/troubleshooting` → 取证接入 → 模块 `csdp-session-service` |
| 动作 | 展开日志检索 → 窗口 `-6h` → 只读试跑 |
| 脚本 | `./scripts/troubleshooting-demo-verify.sh u4` |
| 期望 | `OBSERVED`；旁路提一句 Pod/拨测，主竖线仍是日志脊柱 |
| 失败下一刀 | `NO_EVIDENCE` → 加长窗；资产未绑 → 核对 Workspace 信号绑定 |

## U5 · 排除也是结论

| 项 | 内容 |
|---|---|
| 入口 | 同 U1；对照特征不支撑定位时 |
| 脚本 | `./scripts/troubleshooting-demo-verify.sh u5`（复验历史 EXCLUDED 案或当前对照率为 0 时新开） |
| 期望 | `EXCLUDED` + 可确认；话术「排除也是结论」 |
| 失败下一刀 | 当前窗已是 LOCATED → 用历史案或临时把对照拧回会失败的特征（演示完恢复） |

## U6 · 知识生产（候选，不自动批准）

| 项 | 内容 |
|---|---|
| 入口 | 审核箱 / miss-path |
| 脚本 | demo fixture 档：`./scripts/troubleshooting-miss-path-smoke.sh`；本机 Guance 档：`./scripts/troubleshooting-demo-verify.sh u6` |
| 期望 | `CANDIDATE`；`NOT_ELIGIBLE` 不可自动晋升 |
| 失败下一刀 | 无录制模型响应 → 只口头讲，或切 `troubleshooting-demo` profile |

---

## 建议讲序（10–15 分钟）

1. U4（30s，证明能查到真源）→ 2. U1（主菜）→ 3. U5（对照，30s）→ 4. U2 或 U3 选一条 → 5. U6 口头或快速一闪。

一键复验（HTTP）：

```bash
MATECLAW_USERNAME=admin MATECLAW_PASSWORD=admin123 \
  ./scripts/troubleshooting-demo-verify.sh all
```

## 打通状态（2026-08-12 复验）

| ID | 状态 | 证据 |
|---|---|---|
| U1 | 已通 | `troubleshooting-guance-sendmsg-demo.sh`；Web 详情可见「已定位 / 已确认」 |
| U2 | 已通 | ITGW 904003 @ `2026-08-07T09:12:00Z`，`fixtureMode=false` |
| U3 | 已通 | readiness=`READY_FOR_BOUNDED_FALLBACK`；OPEN_DISCOVERY / LOW |
| U4 | 已通 | log_search / pod / 拨测试跑 OBSERVED |
| U5 | 已通 | 历史 EXCLUDED 案 `diag-1930716105…` + projection |
| U6 | 口头可讲 | Guance pilot 无 recorded inducer；路演不依赖本档强跑 |