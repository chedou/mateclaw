# Backend

**Analysis date:** 2026-05-19

`mateclaw-server` is the runtime core. It is a Spring Boot 3.5 application using Java 21, Spring AI, Spring AI Alibaba Graph, MyBatis Plus, Flyway, Spring Security, Spring MVC/SSE, and many provider SDKs.

## Entry Points

| File | Role |
|---|---|
| `src/main/java/vip/mate/MateClawApplication.java` | Spring Boot entrypoint, `@MapperScan`, scheduling, MyBatis pagination interceptor. |
| `src/main/resources/application.yml` | Default H2/dev config, Flyway H2 migrations, JWT, skill/plugin roots, LLM/cache/tool/wiki settings. |
| `src/main/resources/application-mysql.yml` | MySQL profile, JDBC URL, and Flyway MySQL migrations. |
| `src/main/java/vip/mate/config/DatabaseBootstrapRunner.java` | Locale-aware seed data loader after Flyway. |
| `src/main/java/vip/mate/config/SecurityConfig.java` | Stateless security filter chain and anonymous route list. |
| `src/main/java/vip/mate/config/JwtAuthFilter.java` | JWT, PAT, and query-token authentication. |

## Package Map

The package layout is domain-first. Approximate Java file counts show where complexity sits:

| Package | Purpose |
|---|---|
| `tool` | Built-in tools, document/image/music/video/model3d providers, MCP runtime, Tool Guard, generated files. |
| `wiki` | Knowledge bases, raw materials, page generation, chunks, embeddings, citations, hot cache, transformations, SSE progress. |
| `llm` | Model/provider config, protocol-specific chat builders, OAuth, routing, failover, cache, multimodal sidecars. |
| `channel` | Channel SPI and adapters for web, webchat, DingTalk, Feishu, WeCom, Weixin, Telegram, Discord, QQ, Slack. |
| `skill` | SKILL.md runtime, manifest parsing, security scan, install/update, workspace materialization, lessons, MCP/ACP skill bridges. |
| `memory` | Workspace memory files, facts, dreams, recall, lifecycle, archive, nudge, providers, memory tools. |
| `workflow` | Draft/publish lifecycle, compiler, runtime, payload persistence, draft generation. |
| `agent` | Agent CRUD, graph building, StateGraph runtime, delegation, context compaction, prompt assembly. |
| `workspace` | Workspaces, members, RBAC capabilities, conversations, messages, workspace files. |
| `trigger` | Pattern matching, event ingest, dispatch to workflows/agents, cron/channel/lifecycle/completion triggers. |
| `cron` | Scheduled jobs, run tracking, delivery to channels, ShedLock integration. |
| `auth` | Users, login, JWT issuance, Personal Access Tokens. |
| `approval` | Approval workflow for sensitive tool calls and workflow pauses. |
| `plugin` | Java plugin manager, plugin context, plugin bridges. |
| `acp` | ACP endpoint config and stdio client/delegation support. |
| `system` | Settings, setup/onboarding, health, feature flags. |

## HTTP API Surface

All regular APIs use `/api/v1`. Most responses are wrapped in the project `R<T>` envelope and the frontend interceptor treats body `code !== 200` as an error.

| Area | Controller Prefix |
|---|---|
| Auth/users/PAT | `/api/v1/auth`, `/api/v1/auth/tokens` |
| Chat/conversation | `/api/v1/chat`, `/api/v1/conversations`, `/api/v1/token-usage` |
| Agents/bindings/runtime | `/api/v1/agents`, `/api/v1/agents/{agentId}/skills`, `/api/v1/admin/agent-runtime`, `/api/v1/subagents` |
| Skills/templates/install/secrets | `/api/v1/skills`, `/api/v1/skill-templates`, `/api/v1/skills/install`, `/api/v1/skills/{skillId}/secrets` |
| Channels/webhooks/webchat | `/api/v1/channels`, `/api/v1/channels/webhook`, `/api/v1/channels/webchat`, `/api/v1/channels/qrcode` |
| Wiki | `/api/v1/wiki/*`, `/api/v1/wiki/research`, `/api/v1/wiki/transformations`, `/api/v1/wiki/hot-cache` |
| Workflow/triggers/cron | `/api/v1/workflows`, `/api/v1/triggers`, `/api/v1/cron-jobs` |
| Security/audit/tools | `/api/v1/tools`, `/api/v1/audit`, security controllers under `tool/guard` |
| Settings/workspaces/system | `/api/v1/settings`, `/api/v1/setup`, `/api/v1/workspaces`, `/api/v1/system`, `/api/v1/feature-flags` |
| Multimodal | `/api/v1/tts`, `/api/v1/stt`, provider-backed tool APIs through agent tools |

## Agent Runtime Internals

`AgentService` is the runtime entry. It manages agent CRUD and a cache of built `BaseAgent` instances. The cache key includes model pin information so a conversation-level model choice does not mutate the default agent runtime.

`AgentGraphBuilder` assembles the graph:

1. Loads enabled tools through `ToolRegistry`.
2. Filters tools through Tool Guard deny rules.
3. Applies per-agent skill/tool bindings from `AgentBindingService`.
4. Resolves runtime model with precedence: conversation pin, agent override, global default.
5. Uses `ProviderRouter` to satisfy bound skill model needs when possible.
6. Builds a protocol-specific `ChatModel` through `ProviderChatModelFactory`.
7. Builds ReAct or Plan-and-Execute StateGraph.
8. Injects workspace base path, multimodal router, memory/wiki context, token settings, and stream helpers.

When changing agent behavior, prefer changing graph nodes/edges and the builder wiring rather than adding another agent subclass.

## Tools and Policy

`ToolRegistry` is the central tool assembly point. It collects:

- Spring beans with `@Tool` methods.
- `ToolCallbackProvider` instances, including MCP callbacks.
- Plugin-registered `ToolCallback`s.
- i18n-wrapped tool descriptions where message keys exist.

The agent does not see every registered tool. Tool availability is filtered by:

- `mate_tool.enabled`
- Tool Guard denied tools
- per-agent skill/tool bindings
- plugin availability checks

`ToolExecutionExecutor` then handles actual calls, including approval, timeouts, concurrency, return-direct behavior, result spill, and skill mis-call hints.

## Data Model Anchors

The baseline schema starts in `db/migration/h2/V1__baseline_schema.sql` and has MySQL equivalents. Important table families:

| Family | Tables |
|---|---|
| Identity/workspace | `mate_user`, `mate_personal_access_token`, `mate_workspace`, `mate_workspace_member` |
| Agents/chat | `mate_agent`, `mate_conversation`, `mate_message`, `mate_plan`, `mate_sub_plan` |
| Models/tools | `mate_model_provider`, `mate_model_config`, `mate_tool`, `mate_mcp_server` |
| Skills/plugins | `mate_skill`, `mate_agent_skill`, `mate_agent_tool`, `mate_plugin`, `mate_skill_file`, `mate_skill_secret` |
| Channels/cron | `mate_channel`, `mate_channel_session`, `mate_cron_job`, `mate_cron_job_run` |
| Security | `mate_tool_approval`, `mate_tool_guard_rule`, `mate_tool_guard_config`, `mate_tool_guard_audit_log`, `mate_audit_event` |
| Knowledge/memory | `mate_wiki_knowledge_base`, `mate_wiki_raw_material`, `mate_wiki_page`, `mate_wiki_chunk`, `mate_wiki_relation`, `mate_memory_recall`, `mate_memory_fact_projection`, `mate_dream_report` |
| Workflow/triggers | `mate_workflow`, `mate_workflow_revision`, `mate_workflow_run`, `mate_workflow_run_step`, `mate_workflow_run_pause`, `mate_trigger`, `mate_trigger_event` |

Schema changes must be added to both `db/migration/h2/` and `db/migration/mysql/`.

## Major Domain Services

| Domain | Service/Manager | Notes |
|---|---|---|
| Agent | `AgentService`, `AgentGraphBuilder` | Runtime cache, graph build, lifecycle events. |
| Channel | `ChannelManager`, `ChannelMessageRouter` | Starts adapters on `ApplicationReadyEvent`, hot-replaces configs, handles leader-required channels. |
| LLM | `ModelConfigService`, `ModelProviderService`, `ProviderChatModelFactory`, `ProviderRouter` | Admin-configured providers, model builders, capability routing, failover support. |
| Wiki | `WikiProcessingService`, `WikiPageService`, `WikiEmbeddingService`, `WikiHotCacheService` | Ingest raw material, chunk/embed, create/merge pages, emit progress, inject context. |
| Workflow | `WorkflowService`, compiler/runtime packages | Draft/publish revisions, compile checks, run/pause/payload lifecycle. |
| Skills | `SkillService`, `SkillRuntimeService`, installer/workspace packages | Built-in and dynamic skill catalog plus active prompt/tool resolution. |
| MCP | `McpClientManager` | Owns Spring AI MCP client lifecycle because auto-config is disabled. |
| Plugin | `PluginManager`, `PluginContextImpl` | Loads JARs from `~/.mateclaw/plugins`, registers tools/channels/providers/memory. |

## Tests

Backend tests are broad and domain-focused under `mateclaw-server/src/test/java/vip/mate`.

Common targeted commands:

```bash
# Full backend test suite
mvn -pl mateclaw-server test

# One backend test class
mvn -pl mateclaw-server -Dtest=ProviderHealthTrackerTest test

# Build backend and dependencies without tests
mvn -pl mateclaw-server -am package -DskipTests
```

High-signal test areas include `agent/graph`, `channel/wecom`, `llm/failover`, `wiki/service`, `tool/mcp`, `workflow`, `trigger`, `skill/runtime`, `memory`, `approval`, and `auth/pat`.

