# Integrations

**Analysis date:** 2026-05-19

MateClaw has several extension paths. They are intentionally separate at the edge but converge in backend registries so agents can use them consistently.

## LLM Providers

Provider and model configuration is database/admin-UI driven, not environment-driven. `application.yml` keeps a DashScope placeholder only to satisfy auto-configuration during fresh startup.

Key classes:

- `llm/model` - provider/model entities and protocol metadata.
- `llm/service/ModelProviderService.java` - provider configuration and liveness.
- `llm/service/ModelConfigService.java` - enabled/default model resolution.
- `llm/chatmodel/ProviderChatModelFactory.java` - dispatches protocol to a `ChatModelBuilder`.
- `llm/routing/ProviderRouter.java` - uses bound skill model requirements to select/reorder providers.
- `llm/failover` - provider health tracker, available provider pool, init probes, fallback tests.
- `llm/routing/MultimodalRouter.java` - routes image/video/audio sidecar needs.

Protocols/builders include DashScope native, OpenAI-compatible, Anthropic/Claude, Gemini, Claude Code OAuth, ChatGPT OAuth, and provider-specific decorators.

## Tools

The primary tool path is a Spring `@Component` with one or more `@Tool` methods. `ToolRegistry` discovers enabled tool beans, wraps descriptions with i18n where possible, adds MCP callbacks, and adds plugin tools.

Large built-in tool families:

| Family | Handles |
|---|---|
| Browser | `BrowserUseTool`, `BrowserLauncher`, `BrowserDiagnosticsService`, `skills/browser_cdp/SKILL.md`, `skills/browser_visible/SKILL.md` |
| Files/shell/source | `ReadFileTool`, `WriteFileTool`, `EditFileTool`, `ShellExecuteTool`, `SkillFileTool` |
| Search/data | `WebSearchTool`, `WebSearchService`, `DatasourceTool`, `SqlQueryTool` |
| Delegation | `DelegateAgentTool`, `DelegateAsyncTool`-style task tests, subagent controllers |
| Documents | `DocxRenderTool`, `XlsxRenderTool`, `PptxRenderTool`, `PdfRenderTool`, renderers under `tool/document` |
| Multimodal | `ImageGenerateTool`, `VideoGenerateTool`, `MusicGenerateTool`, `Model3dGenerateTool` plus provider registries |
| Workspace memory | `WorkspaceMemoryTool` |

Dangerous or sensitive tools should be protected by Tool Guard rules and approval workflows.

## Skills

Built-in skills live under `mateclaw-server/src/main/resources/skills`. Dynamic/user skills are materialized under `${user.home}/.mateclaw/skills` by default.

Skill runtime responsibilities:

- Parse SKILL.md manifests and frontmatter.
- Resolve active skill prompt/tool declarations.
- Check dependencies and security scan status.
- Track usage and recency.
- Support `LESSONS.md` and workspace file sync.
- Bridge MCP/ACP/wiki-backed skills into tool surfaces.
- Manage per-skill secrets with masked previews.

Good handles:

- `skill/runtime/SkillRuntimeService.java`
- `skill/manifest/SkillManifestParser.java`
- `skill/installer/*`
- `skill/workspace/*`
- `skill/mcp/*`
- `skill/acp/*`
- `skill/secret/*`

## MCP

Spring AI MCP auto-configuration is disabled in `MateClawApplication`; lifecycle is owned by `tool/mcp/runtime/McpClientManager.java`.

MCP server config is persisted in `mate_mcp_server` and managed through the UI (`/settings/mcp-servers`). The runtime supports multiple transport styles and wraps tool names to avoid collisions. Tests under `tool/mcp/runtime` cover URL splitting, name hashing, prefixed callbacks, return-direct behavior, and wrapper behavior.

## ACP

ACP lets external coding agents appear as MateClaw capabilities.

Handles:

- `acp/client/AcpStdioClient.java`
- `acp/service/AcpEndpointService.java`
- `acp/service/AcpDelegationService.java`
- `skill/acp/AcpSkillBridge.java`
- `skill/knowledge/AcpSkillWrapperToolFactory.java`
- Frontend route `/settings/acp` -> `AcpEndpoints.vue`

ACP endpoints are persisted and converted into skill/tool surfaces.

## Channels

Channels are managed by `ChannelManager`. It starts enabled channels on `ApplicationReadyEvent`, stops them on shutdown, and hot-replaces adapters when configuration changes.

Supported built-in channel types:

- `web`
- `webchat`
- `dingtalk`
- `feishu`
- `wecom`
- `weixin`
- `telegram`
- `discord`
- `qq`
- `slack`

Important patterns:

- `ChannelAdapter` / streaming adapter implementations own upstream protocols.
- `ChannelMessageRouter` routes inbound messages to agents.
- Some channels require a single active leader; `ChannelLeaderElection` and lease heartbeats prevent multiple nodes from opening duplicate WebSocket/long-poll connections.
- WeCom has richer handling for card approvals, keepalive placeholders, reply queues, group attribution, and upload limits.

## Browser and CDP

Browser automation uses Playwright Java. Launch precedence is configured in `BrowserProperties` and implemented in `BrowserLauncher`:

1. `mateclaw.browser.cdp-url` / `MATECLAW_BROWSER_CDP_URL`
2. explicit Chrome path / `CHROME_PATH`
3. Playwright channel
4. system browser auto-detection
5. bundled Chromium
6. external CDP fallback

The `browser_cdp` skill documents:

```text
browser_use(action="list_cdp_targets")
browser_use(action="connect_cdp", url="http://127.0.0.1:9222")
```

CDP is the right path when an agent needs an already-authenticated local Chrome session. Treat it as sensitive because any local process with CDP access can read pages and cookies.

## Java Plugins

`mateclaw-plugin-api` exposes the plugin SPI:

- `MateClawPlugin.onLoad/onEnable/onDisable`
- `PluginContext.registerTool`
- `PluginContext.registerProvider`
- `PluginContext.registerChannel`
- `PluginContext.registerMemoryProvider`
- `PluginContext.getConfig`

`mateclaw-plugin-sample` demonstrates registering a `hello_world` tool with `ToolCallbacks.from(this)`. Runtime plugin loading is handled by `PluginManager`, and plugin JARs are loaded from `${user.home}/.mateclaw/plugins` by default.

## Docker Sidecars

`docker-compose.yml` runs:

- MySQL 8.0 for production persistence.
- SearXNG for keyless search.
- `mateclaw-server`, built from the multi-stage Dockerfile.

The runtime image uses the Microsoft Playwright base image and adds JRE 21, CJK fonts, poppler, and Tesseract so browser screenshots and document/PDF extraction work in-container.

