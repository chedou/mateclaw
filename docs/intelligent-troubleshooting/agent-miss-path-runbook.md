# P4 未命中路只读 Agent 运行手册

> 状态（2026-07-31）：**工程链路已实现，本地 Workspace 已完成专用 Agent 配置与首次受限
> miss-path 演练；默认仍关闭，其他环境必须独立配置和验收。**
> 本手册只允许开启只读分诊；它不授权生产写，也不改变 `fixtureMode=true`，
> 本地 miss-path 演练通过不代表 Guance T7/T8 验收。

## 1. 安全合同

确定性命中路与未命中路是两条不同的执行面：

```text
(system,errorCode) 命中 approved SOP
  → DeterministicDiagnosisService
  → 0 次 Agent / 0 次 LLM

无 errorCode / SYMPTOM / 无 SOP
  → TroubleshootingAgentTriageService
  → 专用 Agent 配置闸
  → AgentService.chatWithToolAllowlist
  → 仅 collect_troubleshooting_evidence
  → 证据引用校验
  → Diagnosis(routeMode=LLM_FALLBACK)
```

未命中路同时依赖五层约束：

1. 专用 Agent 必须属于当前 workspace，启用、类型为 `react`、显式绑定唯一 enabled 模型、skills/wiki 关闭、
   迭代数在上限内；受限调用不经过全局默认模型或 capability primary routing；
2. 直接工具绑定必须且只能是 `TroubleshootingEvidenceTool`；
3. 调用级硬白名单在普通绑定、system-level tools 和 MCP 自动扩展之后再做最终交集，模型实际只能看到
   `collect_troubleshooting_evidence`；provider 原生搜索必须显式关闭，否则在模型请求前返回 409；
4. 受限图不读会话历史、workspace path、memory、wiki、skill catalog、progress ledger、goal 或运行时通知，
   不启用 provider fallback chain；主 provider 未配置时直接 409，运行时调用失败则保守弃权，两者都不自动选择
   备用 provider。进入硬作用域前会清空请求级 `ThinkingLevelHolder`，结束后恢复调用方值；受限图和推理节点也会忽略该
   环境覆盖，因此不会偷偷增加迭代上限或开启额外 thinking/reasoning。受限图还会禁用通用
   `ToolResultStorage` 原始结果 spill；已有/新采集的
   canonical EvidenceResult 在进入模型和 Diagnosis 前统一脱敏，脱敏结果仍随 Diagnosis 持久化以供展示与审计。
   本次 Incident 和路由原因也会先脱敏、转义，
   再按独立字符预算确定性截断；
5. 工具只接受活动的服务端 triage 会话，同时匹配 conversationId 和 workspaceId；
   Incident 上下文不由模型指定，并限制单次会话的取证请求数；queryId 必须安全且会话内唯一，重复调用不会覆盖
   已被引用的证据。工具原始参数只传给 Guard 判定和最终 callback，不复制到 graph event、SSE、运行日志、
   ToolGuard audit 或 approval payload；受限调用若命中 `NEEDS_APPROVAL` 会直接拦截，不会持久待审请求。

受限入口仅保留 Agent 自身 identity system prompt 与本次分诊 prompt，并绕过普通 Agent 的
memory recall/lifecycle hook；模型输出的摘要与假设在持久化前再次脱敏。

普通 `AgentToolBinding` **不是最终安全边界**：平台为兼容性会自动加入 system-level tools，部分配置还会自动加入
MCP 工具。P4 的调用级最终交集才是 miss-path 的权威工具面；ToolGuard BLOCK 是纵深防御，不能替代它。

模型输出还要通过领域闸门：仅本次会话实际采集、状态非 `MISSING` 的 queryId 才算有效引用。解析失败、Agent
异常、空摘要/假设、低置信、主动弃权或无有效引用都会强制 `LOW + abstain`；即使模型返回 `HIGH`，
可验证 fallback 也最高校准为 `MEDIUM`。`LLM_FALLBACK` 的
`recommendedActions` 和 `pendingWrites` 恒为空，人工确认仍只推进状态机、执行 0 个工具。

## 2. 启用前置条件

- Flyway 已应用 V173，工具表中存在 id `1000000028`、名称 `TroubleshootingEvidenceTool`、bean
  `troubleshootingEvidenceTool`，且 bean 已被 Spring 扫描；
- workspace 有一个名称唯一的 enabled 主模型，专用 Agent 的 `modelName` 显式指向它；所属 provider 必须启用、
  配置完整，且模型/所属 provider 的 `enableSearch` 显式为 `false`；
- 只读证据源按 `evidence-adapter-runbook.md` 配置。没有可用源也能安全弃权，但不能形成有证据支撑的结论；
- ToolGuard 已启用，并确认 shell/file/写类规则为 `BLOCK`，没有为排障 Agent 配置
  `NEEDS_APPROVAL`；
- 用于报障的 PAT/JWT 只具备所需 workspace 权限，不在请求或日志中保存真实 Token。

## 3. 创建专用 Agent

推荐在 Agent 管理页创建一个**只用于后端 miss-path 调用**的 Agent。关键字段必须是：

| 字段 | 要求 |
|---|---|
| `workspaceId` | 与报障请求的 `X-Workspace-Id` 一致 |
| `agentType` | `react` |
| `modelName` | 必填；精确指向唯一 enabled 模型，不得依赖全局默认 |
| `enabled` | `true` |
| `skillsDisabled` | `true` |
| `wikiDisabled` | `true` |
| `toolsDisabled` | `false`，否则直接绑定不会生效 |
| `maxIterations` | `1..MATECLAW_TROUBLESHOOTING_AGENT_MAX_ITERATIONS`，建议先用 4 |

系统提示词可再次声明“只读取证、不得给出或执行生产变更”，但提示词不是安全边界。

也可通过现有 API 创建，下面只展示关键字段；认证头和 workspace ID 使用部署环境的安全注入方式：

```http
POST /api/v1/agents
X-Workspace-Id: <workspace-id>
Content-Type: application/json

{
  "name": "troubleshooting-readonly-triage",
  "description": "仅供智能排障 miss-path 后端调用",
  "agentType": "react",
  "modelName": "<unique-enabled-model-name>",
  "maxIterations": 4,
  "enabled": true,
  "skillsDisabled": true,
  "toolsDisabled": false,
  "wikiDisabled": true
}
```

创建后以返回的 Agent ID 做替换式工具绑定：

```http
PUT /api/v1/agents/<agent-id>/tools
X-Workspace-Id: <workspace-id>
Content-Type: application/json

["TroubleshootingEvidenceTool"]
```

用 `GET /api/v1/agents/<agent-id>` 与 `GET /api/v1/agents/<agent-id>/tools` 复核字段和绑定；工具列表必须恰好
一项。不要给该 Agent 绑定 Skill、Wiki、MCP、shell、file、浏览器或任何写工具。

当前硬白名单只在 `TroubleshootingAgentTriageService` 的调用入口生效。**不要把这个专用 Agent 绑定到聊天频道，
也不要把通用 `/agents/{id}/chat*` 当作 P4 的测试入口**；普通聊天调用不属于本运行手册的安全合同。

## 4. 配置与启用顺序

保持开关关闭，先设置 ID 和预算：

```bash
MATECLAW_TROUBLESHOOTING_AGENT_ENABLED=false
MATECLAW_TROUBLESHOOTING_AGENT_ID=<agent-id>
MATECLAW_TROUBLESHOOTING_AGENT_MAX_ITERATIONS=6
MATECLAW_TROUBLESHOOTING_AGENT_MAX_EVIDENCE_REQUESTS=6
MATECLAW_TROUBLESHOOTING_AGENT_MAX_PROMPT_CHARS=32000
```

完成第 2、3 节复核后，最后一步才改为：

```bash
MATECLAW_TROUBLESHOOTING_AGENT_ENABLED=true
```

这些配置在应用启动时绑定；修改后按部署方式重启或滚动发布。任一代码可校验的 Agent/模型配置条件不满足时，
服务会在模型请求前返回 `err.troubleshooting.agent_misconfigured`（HTTP 409），不会自动放宽配置。
证据源可用性、PAT/JWT 权限与 ToolGuard 部署策略仍需按验收矩阵另行核对；没有可用证据源时是安全弃权，不是 409。

### 4.1 本地开发启动

Spring Boot 不会自动读取仓库根目录的 `.env.guance.local`。直接运行
`mvn spring-boot:run` 会丢失其中的 Agent ID 与开关，表现为页面返回
`troubleshooting miss-path Agent is disabled`。本地联调统一使用下面的启动器：

```bash
# 只校验配置，不启动进程，也不输出 Agent ID 或凭据
scripts/run-troubleshooting-dev.sh --check

# 加载已忽略的 .env.guance.local 并启动后端
scripts/run-troubleshooting-dev.sh
```

启动器会在 `MATECLAW_TROUBLESHOOTING_AGENT_ENABLED=true` 时提前检查 Agent ID、
迭代数、证据请求数和 prompt 预算；任一值越界即拒绝启动。它不打印或复制
`MATECLAW_TROUBLESHOOTING_GUANCE_API_KEY`，真实 Agent ID 与 API Key 仍只能放在已忽略的
本地文件或部署密钥系统。

## 5. 验收矩阵

| 场景 | 预期 |
|---|---|
| 命中 approved SOP | `routeMode=DETERMINISTIC`，Agent 调用数 0 |
| miss + 开关关闭 | HTTP 409，Agent 调用数 0 |
| miss + Agent 跨 workspace/禁用/非 ReAct/未显式绑定唯一模型/工具绑定不精确 | HTTP 409，Agent 调用数 0 |
| miss + 模型原生搜索开启 | 模型请求前 HTTP 409，不自动切换其他 provider |
| miss + 主 provider 未配置（如缺少凭据） | 模型请求前 HTTP 409，不自动选择备用 provider |
| miss + 主 provider 运行时调用失败 | `LOW + abstain`，不自动选择备用 provider |
| 工具在无活动会话下被调用 | 返回 `EvidenceResult(status=MISSING, source=agent-tool:rejected)` |
| 工具会话 workspace 不匹配 | 返回 `MISSING/agent-tool:rejected`，不调用 Router |
| 输入或工具证据任一字符串字段/递归 key 含 Bearer/JWT/Token/JSON 密钥 | Agent 与 Diagnosis 只接收脱敏或安全重映射后的 canonical EvidenceResult |
| 环境请求携带 `ThinkingLevelHolder=high` | 受限调用仍使用 Agent 配置的迭代上限和模型默认推理级别，调用后原值恢复 |
| 工具参数含 Token/密钥 | Guard 仍可评估原参数且 callback 可执行，event/SSE/log/audit/approval 不含原参数 |
| Agent 返回畸形 JSON、空结论、异常、LOW 或无有效引用 | `NEEDS_INVESTIGATION`、`abstained=true`、动作为空 |
| Incident/已有证据/路由原因超过上下文预算 | prompt 不超过配置值并带 `[TRUNCATED]`；返回 warning，仍需人工复核 |
| Agent 引用本次非 MISSING 证据 | `READY_FOR_HUMAN`、置信度最高 `MEDIUM`、引用是证据子集、动作为空 |
| 人工确认 fallback | 只推进领域状态，执行工具数 0 |

建议先发一条无现有 SOP 的脱敏演练请求：

```http
POST /api/v1/troubleshooting/incidents
X-Workspace-Id: <workspace-id>
Content-Type: application/json

{
  "incidentId": "rehearsal-agent-miss-001",
  "system": "CSDP-REHEARSAL",
  "service": "synthetic-service",
  "errorCode": "UNREGISTERED-001",
  "title": "脱敏的未命中路演练",
  "severity": "P3",
  "impact": "rehearsal only",
  "intakeSource": "manual-rehearsal",
  "completeness": "STRUCTURED",
  "rawInput": "synthetic input; no credentials",
  "evidence": [],
  "rehearsal": true
}
```

检查返回聚合：`contractVersion=1.4`、`fixtureMode=true`、`routeMode=LLM_FALLBACK`、
`recommendedActions=[]`、`pendingWrites=[]`；若非弃权，`evidenceCitations` 必须全部对应返回证据中非
`MISSING` 的 queryId。再从 Vue 工作台确认页面展示“只读 Agent 建议”，且浏览器不请求该诊断的
`/derivation`。

## 6. 回归命令

```bash
JAVA_HOME=<JDK21> mvn -pl mateclaw-server -am \
  -Dtest='vip.mate.troubleshooting.**.*Test,vip.mate.agent.AgentServiceToolScopeTest,vip.mate.agent.HardScopedAgentPolicyTest,vip.mate.agent.graph.StateGraphReActAgentIsolationTest,vip.mate.agent.graph.NodeStreamingChatHelperToolCallArgsTest,vip.mate.agent.graph.node.ReasoningNodePtlPromptTest,vip.mate.agent.graph.executor.ToolExecutionExecutorSensitiveArgumentsTest,vip.mate.tool.guard.service.ToolGuardServiceSensitiveAuditTest,vip.mate.tool.guard.engine.ToolGuardEngineSensitiveLoggingTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test

cd mateclaw-ui
npx vitest run
npx vue-tsc --noEmit
npx vite build
```

重点回归：`TroubleshootingAgentTriageServiceTest`、`TroubleshootingEvidenceToolTest`、
`TroubleshootingIntakeServiceTest`、`AgentServiceToolScopeTest`、`HardScopedAgentPolicyTest`、
`StateGraphReActAgentIsolationTest`、`NodeStreamingChatHelperToolCallArgsTest`、
`ReasoningNodePtlPromptTest`、`ToolExecutionExecutorSensitiveArgumentsTest`、
`ToolGuardServiceSensitiveAuditTest`、`ToolGuardEngineSensitiveLoggingTest`、
`TroubleshootingSecretRedactorTest`、`DiagnosisContractTest` 和
`TroubleshootingMigrationTest`。

## 7. 回滚

把 `MATECLAW_TROUBLESHOOTING_AGENT_ENABLED` 改回 `false` 并重启/滚动发布即可。不要删除历史 Diagnosis，
也不需要删除 V173 工具行：工具在无活动 triage 会话时会拒绝，保留迁移记录有利于审计和再次启用。

回滚后再次发 miss 请求应返回 409；命中路、既有诊断读取与人工状态机不受影响。
