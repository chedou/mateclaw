# Development Guide

**Analysis date:** 2026-05-19

This page is for changing the repo safely after you know which module owns the behavior.

## Local Setup

Requirements:

- Java 21
- Maven 3.9+
- Node 22+ or a compatible current Node
- pnpm 10+

Run backend and frontend separately:

```bash
cd mateclaw-server
mvn spring-boot:run

cd ../mateclaw-ui
pnpm install
pnpm dev --host 127.0.0.1
```

Open `http://127.0.0.1:5173`. Default login: `admin` / `admin123`.

If Maven uses Java 8 on macOS, run with Java 21 explicitly:

```bash
cd mateclaw-server
env JAVA_HOME=$(/usr/libexec/java_home -v 21) PATH="$JAVA_HOME/bin:$PATH" mvn spring-boot:run
```

## Common Verification Commands

```bash
# Backend compile/test
mvn -pl mateclaw-server -am compile
mvn -pl mateclaw-server test
mvn -pl mateclaw-server -Dtest=SomeTest test

# Plugin API and sample
mvn -pl mateclaw-plugin-api -am install -DskipTests
mvn -pl mateclaw-plugin-sample -am package -DskipTests

# Frontend
cd mateclaw-ui
pnpm exec vue-tsc --noEmit --pretty false
pnpm build
pnpm lint:precision

# Webchat widget
cd mateclaw-webchat
pnpm build

# Runtime smoke checks
curl http://127.0.0.1:18088/actuator/health
curl -I http://127.0.0.1:5173/
```

## Add a Backend API

1. Put controller under the owning domain package, usually `vip.mate.<domain>/controller`.
2. Use `/api/v1/<resource>` prefix.
3. Return `R<T>` unless the endpoint is intentionally raw SSE/file/binary.
4. Enforce workspace scope in service lookups, not only in the frontend.
5. Add mapper/service/model classes in the same domain package.
6. Add tests near the domain package under `src/test/java/vip/mate`.
7. Add frontend API wrapper in `mateclaw-ui/src/api/index.ts` or a domain split that preserves the shared Axios instance.

## Add a Database Change

1. Add H2 migration: `mateclaw-server/src/main/resources/db/migration/h2/V{next}__description.sql`.
2. Add MySQL migration: `mateclaw-server/src/main/resources/db/migration/mysql/V{next}__description.sql`.
3. Keep seed data idempotent in `db/data-*.sql` when needed.
4. For new tables, follow local conventions: `mate_` prefix, `create_time`, `update_time`, `deleted` where applicable.
5. Add or update MyBatis Plus entity/mapper classes.

Do not rely on Spring SQL init; `DatabaseBootstrapRunner` and Flyway own schema/data startup.

## Add an Agent Behavior

Prefer graph primitives:

- New ReAct node: `agent/graph/node`.
- New Plan-and-Execute node: `agent/graph/plan/node`.
- New edge/condition: `agent/graph/edge` or `agent/graph/plan/edge`.
- New state key: `agent/graph/state/MateClawStateKeys`.
- Builder wiring: `AgentGraphBuilder`.

Then cover the behavior with focused tests under `agent/graph`, `agent/graph/node`, `agent/graph/edge`, or `agent/context`.

## Add a Tool

1. Create a Spring `@Component`.
2. Add one or more `@Tool` methods.
3. Add `@ToolParam` descriptions for every parameter.
4. Consider Tool Guard rules for dangerous actions.
5. Add tests for parameter normalization, result shape, and security behavior.
6. If the tool returns large output, think about tool result spill and preview behavior.

The tool becomes visible through `ToolRegistry`, then may be filtered by DB enabled state, Tool Guard deny rules, and per-agent bindings.

## Add a Skill

1. Add or materialize a `SKILL.md`.
2. Declare required tools and model needs precisely.
3. Put bundled skills under `mateclaw-server/src/main/resources/skills/<name>/`.
4. Add runtime tests if manifest parsing, security scan, lessons, secrets, or MCP/ACP wrapping is involved.
5. Update UI/i18n only if a new admin surface is needed.

## Add a Frontend Page

1. Create a view under `mateclaw-ui/src/views`.
2. Register route and capability metadata in `src/router/index.ts`.
3. Add navigation entry through `useNavItems` / layout conventions.
4. Add API wrapper in `src/api/index.ts`.
5. Add i18n strings in both `zh-CN.ts` and `en-US.ts`.
6. Use Element Plus, existing common components, and CSS variables from `src/assets/main.css`.
7. For shared state, create or extend a Pinia store. For local state, use `ref`/`computed` in the component.

## Add an LLM Provider or Model Capability

Start in `llm`:

- model/provider entities and seed migrations
- `ModelProtocol`
- a `ChatModelBuilder`
- `ProviderChatModelFactory`
- `ModelCapabilityService`
- provider health/failover tests
- frontend provider catalog/config UI under `Settings/Models`

Provider keys should be managed through Settings -> Models, not new hardcoded environment variables, unless the deployment path genuinely needs a runtime secret.

## Add a Channel

1. Implement `ChannelAdapter` or a streaming adapter under `channel/<type>`.
2. Register config handling and lifecycle in `ChannelManager`.
3. Add webhook or long-connection handling through channel controllers.
4. Define whether it requires single leader.
5. Add verifier/preflight where appropriate.
6. Add UI config support under `Channels.vue` and channel composables.
7. Add tests for message routing, errors, retries, text splitting, and media behavior.

## Known Gotchas

- The backend currently expects Java 21. If the shell default is Java 8, Maven will fail or compile the wrong target.
- Frontend `pnpm build` writes into `mateclaw-server/src/main/resources/static`.
- Vite dev proxy must keep `ws: true` for `/api`, or Talk Mode WebSocket fails silently.
- `mateclaw-ui/src/api/index.ts` unwraps `R<T>` and rejects body-level errors. Do not change backend error envelopes casually.
- The backend disables Spring AI MCP auto-config because `McpClientManager` owns client lifecycle.
- For browser automation with a logged-in human session, use CDP (`MATECLAW_BROWSER_CDP_URL` or `browser_use(action="connect_cdp")`) rather than a fresh automated browser.
- Schema migrations must be duplicated for H2 and MySQL.
- For generated/binary files, keep auth and TTL behavior in mind; some downloads bypass Axios.
- The repo may contain local H2 data/logs after running; do not confuse those with source changes.

