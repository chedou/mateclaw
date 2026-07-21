# Frontend

**Analysis date:** 2026-05-19

`mateclaw-ui` is the admin console. It is a Vue 3 + TypeScript + Vite SPA using Element Plus, Pinia, Vue Router, Vue i18n, TailwindCSS 4, and several feature libraries for markdown, diagrams, graphs, charts, Monaco, and 3D previews.

## Entry Points

| File | Role |
|---|---|
| `src/main.ts` | Creates Vue app, initializes locale, registers Element Plus icons, Pinia, router, i18n, Element Plus, and `<model-viewer>`. |
| `src/App.vue` | Root `el-config-provider`, app title, theme bootstrap, global confirm host. |
| `src/router/index.ts` | All route declarations, auth guard, workspace capability guard, redirects. |
| `src/api/index.ts` | Central Axios client, auth/workspace/locale headers, `R<T>` envelope handling, API namespaces. |
| `src/views/layout/MainLayout.vue` | Shell layout, sidebar, workspace switcher, badges, theme/language/user controls, doctor drawer. |
| `vite.config.ts` | Vue/Tailwind plugins, `/api` proxy, `/skill-assets` proxy, chunk splitting, build output into backend static resources. |

## Visual System Rules

AI-assisted frontend changes must preserve MateClaw's existing warm product visual system. The project-level rules live in [`../../AGENTS.md`](../../AGENTS.md); the practical frontend rule is:

- Use theme tokens from `src/assets/main.css` (`--mc-bg`, `--mc-panel-*`, `--mc-border*`, `--mc-text-*`, `--mc-primary*`, `--mc-accent*`, semantic status tokens) instead of page-local palettes.
- Keep pages inside the standard shell `mc-page-shell > mc-page-frame > mc-page-inner` unless a feature has a documented exception.
- Do not introduce standalone blue/slate/gray SaaS palettes or unrelated dark console palettes for individual modules.
- Solve readability through type scale, line height, font weight, spacing, wrapping, and existing semantic tokens rather than replacing the product color system.
- Verify desktop and mobile widths, no document-level horizontal scrolling, and readable operational text such as IDs, commands, logs, chips, and status pills.

## Route and Capability Model

Routes declare `requiredCapability` metadata. The router guard:

1. Redirects unauthenticated users to `/login`.
2. Initializes `useWorkspaceStore`.
3. Fetches backend-provided workspace capabilities.
4. Blocks routes not allowed by the current workspace role.

Capability definitions live in `src/composables/capabilities.ts`, but the authoritative mapping is backend-owned.

Major routes:

| Route | View |
|---|---|
| `/chat` | `ChatConsole.vue` |
| `/dashboard` | `Dashboard.vue` |
| `/agents` | `Agents.vue`, including live runtime sub-view |
| `/wiki` | `Wiki/index.vue` |
| `/memory` | `Memory/index.vue` |
| `/channels` | `Channels.vue` |
| `/skills` | `SkillMarket.vue` |
| `/skills/templates` | `SkillTemplates.vue` |
| `/activity` | `Security/Activity/index.vue` |
| `/settings/*` | Models, system, multimodal settings, workspaces, members, workflows, triggers, datasources, MCP, tools, ACP, token usage, feature flags |
| `/security/*` | Tool Guard, File Guard, audit logs |

Backward-compatible redirects keep older paths working, for example `/tools` to `/settings/tools` and `/cron-jobs` to `/settings/cron-jobs`.

## API Client

`src/api/index.ts` creates one Axios instance:

- `baseURL: /api/v1`
- `Authorization: Bearer <token>` from localStorage
- `X-Workspace-Id` from `mc-workspace-id`
- `Accept-Language` from `mateclaw_locale`
- body envelope handling for backend `R<T> { code, msg, data }`
- `X-New-Token` sliding renewal support

Special cases use native `fetch` instead of Axios:

- SSE chat streaming in `chatApi.stream`
- authenticated binary/blob access in `fetchAuthenticatedBlob`

The API file is intentionally large and acts as a typed-ish catalog. If it grows further, split by domain only after preserving the single Axios instance and response semantics.

## Chat Streaming Frontend

The chat UI is built around composables:

| File | Role |
|---|---|
| `composables/chat/useChat.ts` | Unified chat controller: messages, stream, queue, interrupt/resume, regeneration, compact status, approvals. |
| `composables/chat/useStream.ts` | SSE parser and connection manager with event-id dedup/reconnect support. |
| `composables/chat/useMessages.ts` | Message list state operations. |
| `composables/chat/useMessageQueue.ts` | Queue behavior while a response is running. |
| `composables/chat/useTyping.ts` | Typing indicator behavior. |

The backend emits typed SSE events. Frontend event types include `content_delta`, `thinking_delta`, `phase`, `tool_call_started`, `tool_call_completed`, `tool_approval_requested`, `heartbeat`, `delegation_*`, `compact_status`, `message_complete`, and `done`.

## State Stores

Pinia stores under `src/stores` own cross-page state:

| Store | Role |
|---|---|
| `useWorkspaceStore.ts` | Workspace list, selected workspace, backend-provided capabilities. |
| `useThemeStore.ts` | Light/dark/system mode and `<html>` class. |
| `useAgentStore.ts` | Agent list and management state. |
| `useWikiStore.ts` | Wiki workspace state. |
| `useMemoryStore.ts` | Memory page state. |
| `useCronJobStore.ts` | Cron job state. |

Pattern: components call store actions; avoid mutating store state from outside the store.

## View Organization

The UI is feature-first:

- `views/Wiki/components` contains the knowledge-base workspace, page viewer, graph view, raw material panel, hot cache panel, transformations panel, citation drawer, and model config components.
- `views/Settings/Models` owns provider cards, provider catalog rows, provider config modal, OAuth/device code dialog, embedding and multimodal sidecar sections.
- `views/Memory/components` owns memory browser, sections, fact list/trust bar, morning card, and skeleton/empty states.
- `views/Security` owns Tool Guard, File Guard, audit logs, activity, workspaces, and members.
- `components/common` contains reusable local UI primitives such as drawers, pagination, confirm host, toast, model picker, and icon controls.

## Rendering Helpers

Composable helpers handle complex rendering:

- `useMarkdownRenderer.ts` for markdown.
- `useMermaidRenderer.ts` for diagrams.
- `useKatexRenderer.ts` for math.
- `useEChartsRenderer.ts` for charts.
- `useWorkflowGraph.ts` and workflow components for workflow graph editing/rendering.
- `useAuthenticatedAttachment.ts` for protected files in message content.

## Build Behavior

`pnpm build` runs:

1. `scripts/check-snowflake-precision.sh`
2. `vue-tsc --noEmit`
3. `vite build`

The Vite build outputs to `../mateclaw-server/src/main/resources/static`, so the backend JAR can serve the SPA. The dev server proxies:

- `/api` to `http://localhost:18088` with WebSocket forwarding.
- `/skill-assets` to `http://localhost:18088`.

Heavy chunks are named manually: Monaco, Vue Flow, Mermaid, ECharts, Element Plus, and markdown libraries.

## Webchat Widget

`mateclaw-webchat` is a separate Vite library. It exposes `MateClawWebChat.init({ apiKey, server, ... })`, injects styles, creates a floating bubble/panel, stores a visitor id in localStorage, and streams through the backend webchat channel. Its build copies `dist/` into `mateclaw-server/src/main/resources/static/webchat/`.
