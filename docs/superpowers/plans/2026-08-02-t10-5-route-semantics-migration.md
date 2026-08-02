# T10.5 Route Semantics Read Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `investigationMode` and `routeAuthority` the authoritative read dimensions for diagnosis projections and queue filtering while preserving `RouteMode` only as a legacy persistence field and keeping derived 1.3/1.4 history visibly distinct.

**Architecture:** Persist the two v4 route dimensions into nullable indexed diagnosis columns. Migrations copy only exact values already present in 1.5+ aggregate JSON and leave 1.3/1.4 rows null, so history is never guessed. The domain exposes `RouteSemanticsProvenance`, server projections and queue summaries carry it, and the Vue workbench requests/labels filters by `investigationMode`; deterministic derivation is selected by playbook investigation mode rather than legacy `RouteMode`.

**Tech Stack:** Java 21 records, Spring MVC, MyBatis-Plus, Flyway SQL for H2/MySQL/Kingbase, JUnit 5/AssertJ/Mockito, Vue 3/Pinia/TypeScript/Vitest.

---

### Task 1: Make route-semantics provenance explicit at the domain boundary

**Files:**
- Create: `mateclaw-server/src/main/java/vip/mate/troubleshooting/model/RouteSemanticsProvenance.java`
- Modify: `mateclaw-server/src/main/java/vip/mate/troubleshooting/model/Diagnosis.java`
- Modify: `mateclaw-server/src/test/java/vip/mate/troubleshooting/model/DiagnosisContractTest.java`

- [x] **Step 1: Write failing provenance tests**

Add assertions to the existing 1.4 compatibility test and current scenario round-trip test:

```java
assertEquals(
        RouteSemanticsProvenance.LEGACY_DERIVED,
        restored.routeSemanticsProvenance());
```

and:

```java
assertEquals(
        RouteSemanticsProvenance.PERSISTED,
        restored.routeSemanticsProvenance());
```

- [x] **Step 2: Run the tests and verify RED**

Run:

```bash
mvn --offline --batch-mode --no-transfer-progress \
  -pl mateclaw-server \
  -Dtest=vip.mate.troubleshooting.model.DiagnosisContractTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: test compilation fails because `RouteSemanticsProvenance` and `routeSemanticsProvenance()` do not exist.

- [x] **Step 3: Add the minimal provenance contract**

Create:

```java
package vip.mate.troubleshooting.model;

/** Whether v4 route dimensions were stored or derived only for legacy reads. */
public enum RouteSemanticsProvenance {
    PERSISTED,
    LEGACY_DERIVED
}
```

In `Diagnosis`, centralize the existing legacy-version check and add:

```java
public RouteSemanticsProvenance routeSemanticsProvenance() {
    return isLegacyContractVersion(contractVersion)
            ? RouteSemanticsProvenance.LEGACY_DERIVED
            : RouteSemanticsProvenance.PERSISTED;
}

private static boolean isLegacyContractVersion(String version) {
    return "1.3".equals(version) || "1.4".equals(version);
}
```

Use `isLegacyContractVersion(contractVersion)` in the compact constructor instead of duplicating the version expression. Do not add a record component or bump the persisted contract version: provenance is a lossless interpretation of versioned storage, not a new stored fact.

- [x] **Step 4: Run the test and verify GREEN**

Run the Step 2 command. Expected: `DiagnosisContractTest` passes with 0 failures and 0 errors.

- [x] **Step 5: Commit the domain slice**

Commit only the enum, `Diagnosis.java`, and `DiagnosisContractTest.java` with a Lore message and the required `Co-authored-by: OmX <omx@oh-my-codex.dev>` trailer.

### Task 2: Index exact v4 route fields without guessing legacy history

**Files:**
- Create: `mateclaw-server/src/main/resources/db/migration/h2/V191__troubleshooting_route_semantics.sql`
- Create: `mateclaw-server/src/main/resources/db/migration/mysql/V191__troubleshooting_route_semantics.sql`
- Create: `mateclaw-server/src/main/resources/db/migration/kingbase/V191__troubleshooting_route_semantics.sql`
- Modify: `mateclaw-server/src/main/java/vip/mate/troubleshooting/model/TroubleshootingDiagnosisEntity.java`
- Modify: `mateclaw-server/src/main/java/vip/mate/troubleshooting/service/DiagnosisSummary.java`
- Modify: `mateclaw-server/src/main/java/vip/mate/troubleshooting/service/TroubleshootingPersistenceService.java`
- Modify: `mateclaw-server/src/test/java/vip/mate/troubleshooting/persistence/TroubleshootingMigrationTest.java`
- Modify: `mateclaw-server/src/test/java/vip/mate/troubleshooting/persistence/TroubleshootingPersistenceServiceTest.java`

- [x] **Step 1: Write a failing H2 migration test**

Add `h2V191IndexesOnlyPersistedRouteSemantics()` that applies V172, inserts one 1.4 aggregate without the two fields and one 1.8 aggregate containing:

```json
{"investigationMode":"SCENARIO_PLAYBOOK","routeAuthority":"RULE_MATCHED"}
```

Apply V191 and assert:

```java
assertTrue(columns.contains("investigation_mode"));
assertTrue(columns.contains("route_authority"));
assertEquals(1, countIndexes(connection, "idx_ts_diagnosis_investigation"));
assertEquals(1, countIndexes(connection, "idx_ts_diagnosis_authority"));
// 1.4 row: both columns null; 1.8 row: exact SCENARIO_PLAYBOOK/RULE_MATCHED.
```

- [x] **Step 2: Verify the migration test fails before V191 exists**

Run:

```bash
mvn --offline --batch-mode --no-transfer-progress \
  -pl mateclaw-server \
  -Dtest=vip.mate.troubleshooting.persistence.TroubleshootingMigrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: failure loading `V191__troubleshooting_route_semantics.sql`.

- [x] **Step 3: Add cross-database migrations**

Each migration adds nullable `investigation_mode VARCHAR(48)` and `route_authority VARCHAR(48)`, plus indexes `(workspace_id, investigation_mode, id)` and `(workspace_id, route_authority, id)`. The second index is required for the later same-cohort `RULE_MATCHED` / `MODEL_PROPOSED` count; this task does not fabricate those missing production paths.

H2 uses `REGEXP_LIKE` with all known enum literals; for example:

```sql
UPDATE mate_troubleshooting_diagnosis
SET investigation_mode = CASE
        WHEN REGEXP_LIKE(aggregate_json, '"investigationMode"\\s*:\\s*"ERROR_CODE_PLAYBOOK"') THEN 'ERROR_CODE_PLAYBOOK'
        WHEN REGEXP_LIKE(aggregate_json, '"investigationMode"\\s*:\\s*"SCENARIO_PLAYBOOK"') THEN 'SCENARIO_PLAYBOOK'
        WHEN REGEXP_LIKE(aggregate_json, '"investigationMode"\\s*:\\s*"OPEN_DISCOVERY"') THEN 'OPEN_DISCOVERY'
    END,
    route_authority = CASE
        WHEN REGEXP_LIKE(aggregate_json, '"routeAuthority"\\s*:\\s*"EXPLICIT"') THEN 'EXPLICIT'
        WHEN REGEXP_LIKE(aggregate_json, '"routeAuthority"\\s*:\\s*"RULE_MATCHED"') THEN 'RULE_MATCHED'
        WHEN REGEXP_LIKE(aggregate_json, '"routeAuthority"\\s*:\\s*"MODEL_PROPOSED"') THEN 'MODEL_PROPOSED'
    END
WHERE contract_version NOT IN ('1.3', '1.4');
```

MySQL copies `JSON_UNQUOTE(JSON_EXTRACT(aggregate_json, '$.investigationMode'))` and `$.routeAuthority`; Kingbase copies `aggregate_json::jsonb ->> 'investigationMode'` and `routeAuthority`. Both restrict the update to contracts other than 1.3/1.4 and non-null JSON members. No migration may infer values from `routeMode`.

- [x] **Step 4: Verify the migration test passes**

Run the Step 2 command. Expected: all `TroubleshootingMigrationTest` tests pass.

- [x] **Step 5: Write failing persistence/index tests**

Extend `TroubleshootingPersistenceServiceTest` to assert a newly created current diagnosis writes:

```java
assertEquals("ERROR_CODE_PLAYBOOK", entity.getValue().getInvestigationMode());
assertEquals("EXPLICIT", entity.getValue().getRouteAuthority());
```

Add a legacy 1.4 create case from JSON and assert both indexed columns remain null. Add a list-query test that calls `list(7L, null, null, InvestigationMode.SCENARIO_PLAYBOOK, 100)` and verifies the captured MyBatis parameters contain `SCENARIO_PLAYBOOK` without inspecting aggregate JSON.

- [x] **Step 6: Verify the persistence tests fail for missing fields/signature**

Run:

```bash
mvn --offline --batch-mode --no-transfer-progress \
  -pl mateclaw-server \
  -Dtest=vip.mate.troubleshooting.persistence.TroubleshootingPersistenceServiceTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: test compilation fails until entity fields and the typed list parameter exist.

- [x] **Step 7: Implement indexed persistence and summary provenance**

Add `String investigationMode` and `String routeAuthority` to the entity. On create/update, write enum names only when `diagnosis.routeSemanticsProvenance() == PERSISTED`; write null for `LEGACY_DERIVED`. Extend `DiagnosisSummary` with nullable typed fields and mandatory `RouteSemanticsProvenance`. `DiagnosisSummary.from()` parses exact indexed enum names; it returns `LEGACY_DERIVED` only for a legacy contract with both index columns null and fails closed on incomplete/non-legacy indexed state.

Change the list signature to:

```java
public List<DiagnosisSummary> list(
        long workspaceId,
        String status,
        String system,
        InvestigationMode investigationMode,
        int limit)
```

and add an indexed equality condition when the typed filter is non-null. Keep this build-preserving delegate only until Task 3 migrates the controller:

```java
public List<DiagnosisSummary> list(
        long workspaceId, String status, String system, int limit) {
    return list(workspaceId, status, system, null, limit);
}
```

- [x] **Step 8: Verify migration and persistence suites GREEN**

Run both tests from Steps 2 and 6 in one Maven invocation. Expected: 0 failures and 0 errors.

- [x] **Step 9: Commit the index slice**

Commit the three migrations, entity, summary, persistence service, and their tests with a Lore message and required co-author trailer.

### Task 3: Move server projections and list API off `RouteMode`

**Files:**
- Modify: `mateclaw-server/src/main/java/vip/mate/troubleshooting/model/Diagnosis.java`
- Modify: `mateclaw-server/src/main/java/vip/mate/troubleshooting/projection/DiagnosisExperienceProjection.java`
- Modify: `mateclaw-server/src/main/java/vip/mate/troubleshooting/projection/DiagnosisExperienceProjectionService.java`
- Modify: `mateclaw-server/src/main/java/vip/mate/troubleshooting/controller/TroubleshootingController.java`
- Modify: `mateclaw-server/src/test/java/vip/mate/troubleshooting/model/DiagnosisContractTest.java`
- Modify: `mateclaw-server/src/test/java/vip/mate/troubleshooting/projection/DiagnosisExperienceProjectionServiceTest.java`
- Modify: `mateclaw-server/src/test/java/vip/mate/troubleshooting/controller/TroubleshootingControllerProjectionTest.java`

- [x] **Step 1: Write failing projection/API tests**

Add projection assertions:

```java
assertThat(result.developerEvidence().routeSemanticsProvenance())
        .isEqualTo(RouteSemanticsProvenance.PERSISTED);
```

Add an OPEN_DISCOVERY test that verifies `derivationService.explain` is never called, and retain the SCENARIO_PLAYBOOK test that verifies it is called. Add a controller GET test for:

```text
/api/v1/troubleshooting/diagnoses?investigationMode=SCENARIO_PLAYBOOK
```

and verify persistence receives the typed enum.

- [x] **Step 2: Verify RED**

Run the three named test classes. Expected: failures for the missing projection field, list parameter, and the old route-mode derivation boundary.

- [x] **Step 3: Implement authoritative v4 reads**

Add `RouteSemanticsProvenance routeSemanticsProvenance` to `DeveloperEvidenceView` and require it in validation. Build it from `diagnosis.routeSemanticsProvenance()`.

Change derivation eligibility to:

```java
if (diagnosis.investigationMode() == InvestigationMode.OPEN_DISCOVERY
        || diagnosis.sopKey() == null) {
    // no deterministic Playbook chain
}
```

Change current-domain safety judgments in `Diagnosis` to read `investigationMode` and `routeAuthority`: exact Playbook versions are required for playbook modes; OPEN_DISCOVERY cannot claim a Playbook version or actions; MODEL_PROPOSED remains capped at MEDIUM. Keep `RouteMode` reads only in the 1.3/1.4 compatibility mapping and persisted record component.

Add typed `@RequestParam(required = false) InvestigationMode investigationMode` to the list controller and pass it to persistence.
Delete the temporary four-argument persistence delegate added in Task 2 after the controller compiles against the typed five-argument method.

- [x] **Step 4: Verify GREEN and grep the server read boundary**

Run the Step 2 tests, then:

```bash
rg -n 'routeMode\\(\\)|routeMode ==|routeMode !=|RouteMode\\.(DETERMINISTIC|LLM_FALLBACK)' \
  mateclaw-server/src/main/java/vip/mate/troubleshooting
```

Expected: remaining matches are only compatibility mapping/factory persistence writes, never projection, filtering, confidence, action, derivation, or lifecycle business reads.

- [x] **Step 5: Commit the server read slice**

Commit the domain/projection/controller changes and tests with a Lore message and co-author trailer.

### Task 4: Filter and render the workbench by v4 investigation mode

**Files:**
- Modify: `mateclaw-ui/src/api/index.ts`
- Modify: `mateclaw-ui/src/stores/useTroubleshootingStore.ts`
- Modify: `mateclaw-ui/src/views/Troubleshooting/formalProjection.ts`
- Modify: `mateclaw-ui/src/views/Troubleshooting/derivationPresentation.ts`
- Modify: `mateclaw-ui/src/views/Troubleshooting/DerivationChain.vue`
- Modify: `mateclaw-ui/src/views/Troubleshooting/DiagnosisListView.vue`
- Modify: `mateclaw-ui/src/views/Troubleshooting/FormalWorkbench.vue`
- Modify: `mateclaw-ui/src/views/Troubleshooting/__tests__/formalProjection.test.ts`
- Modify: `mateclaw-ui/src/views/Troubleshooting/__tests__/derivationPresentation.test.ts`

- [x] **Step 1: Write failing pure TypeScript tests**

Add tests proving:

```ts
expect(supportsDeterministicDerivation('ERROR_CODE_PLAYBOOK')).toBe(true)
expect(supportsDeterministicDerivation('SCENARIO_PLAYBOOK')).toBe(true)
expect(supportsDeterministicDerivation('OPEN_DISCOVERY')).toBe(false)
expect(diagnosisSummaryRouteLabel(null, null, 'LEGACY_DERIVED'))
  .toBe('旧合同推导 · 详情可见兼容值')
expect(diagnosisSummaryRouteLabel('SCENARIO_PLAYBOOK', 'RULE_MATCHED', 'PERSISTED'))
  .toBe('场景 Playbook · 规则命中')
```

- [x] **Step 2: Verify RED**

Preferred command when dependencies are installed:

```bash
npm test -- \
  src/views/Troubleshooting/__tests__/derivationPresentation.test.ts \
  src/views/Troubleshooting/__tests__/formalProjection.test.ts
```

If registry DNS still prevents installation, run the dependency-free boundary module through Node 22 type stripping and record the full Vitest/typecheck gap; do not claim the Vue build passed.

- [x] **Step 3: Implement API types and UI route helpers**

Add:

```ts
export type RouteSemanticsProvenance = 'PERSISTED' | 'LEGACY_DERIVED'
```

Extend both `DiagnosisSummary` and `DiagnosisExperienceProjection.developerEvidence` with required provenance; the summary's `investigationMode` and `routeAuthority` remain nullable for legacy rows. Extend the list API params with `investigationMode?: InvestigationMode`.

Implement `supportsDeterministicDerivation(mode)` as true only for the two Playbook modes. Implement `diagnosisSummaryRouteLabel()` so legacy rows are visibly labeled and incomplete persisted rows fail closed as `路由字段缺失` rather than guessed.

- [x] **Step 4: Move component/store reads to investigationMode**

In `DerivationChain.vue`, replace both the computed and watcher dependencies on `routeMode` with `investigationMode` and the new helper.

Add `investigationModeFilter` to the Pinia store, pass it to `troubleshootingApi.list`, expose it, and bind an investigation-mode select in both the full list toolbar and queue toolbar. Add a route column to the full list using `diagnosisSummaryRouteLabel`; legacy rows remain visible when no filter is active.

- [x] **Step 5: Verify GREEN and remove UI RouteMode business reads**

Run the Step 2 tests plus, when dependencies are available:

```bash
npm run build
```

Always run:

```bash
rg -n 'routeMode' mateclaw-ui/src/views/Troubleshooting mateclaw-ui/src/stores/useTroubleshootingStore.ts
```

Expected: zero business reads. The API compatibility field may remain in `Diagnosis` typing only.

- [x] **Step 6: Commit the UI slice**

Commit the API/store/component/helper/test changes with a Lore message and co-author trailer.

### Task 5: Close the completed T10.5 steps with runnable evidence

**Files:**
- Modify: `docs/intelligent-troubleshooting/TODO.md`
- Modify: `docs/intelligent-troubleshooting/HANDOFF.md`

- [x] **Step 1: Run the focused server suite**

```bash
mvn --offline --batch-mode --no-transfer-progress \
  -pl mateclaw-server \
  -Dtest=vip.mate.troubleshooting.model.DiagnosisContractTest,vip.mate.troubleshooting.persistence.TroubleshootingMigrationTest,vip.mate.troubleshooting.persistence.TroubleshootingPersistenceServiceTest,vip.mate.troubleshooting.projection.DiagnosisExperienceProjectionServiceTest,vip.mate.troubleshooting.controller.TroubleshootingControllerProjectionTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: 0 failures and 0 errors with BUILD SUCCESS.

- [x] **Step 2: Run UI and contract gates**

Run the two focused UI tests and `npm run build` when dependencies are available. Independently run:

```bash
bash -n scripts/troubleshooting-scenario-smoke.sh
bash -n scripts/troubleshooting-scenario-evidence-smoke.sh
./scripts/troubleshooting-scenario-smoke.sh --gates
./scripts/troubleshooting-scenario-evidence-smoke.sh --gates
./scripts/ci/test-troubleshooting-smoke-workflow.sh
```

Expected: script syntax and CI smoke-contract checks pass.

- [x] **Step 3: Update the ledger truthfully**

Mark the T10.5 downstream-read and history-provenance checkboxes complete. Keep the scenario-source statistics and final deprecation items unchecked until both RULE_MATCHED and MODEL_PROPOSED production paths exist and are counted in the same sample batch. Record the nullable-index migration and any frontend dependency verification gap in HANDOFF.

- [x] **Step 4: Run final staged checks and commit**

Run `git diff --check`, the RouteMode grep gates, and `git status --short`. Commit docs with a Lore message and required co-author trailer. Do not push until GitHub credentials are available; never force-update the remote branch.
