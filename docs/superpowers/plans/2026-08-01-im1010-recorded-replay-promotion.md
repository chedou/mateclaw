# IM1010 Recorded Replay Promotion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Generate safe manual-Playbook replay suites from one sanitized recorded positive case, then prove the real `csdp:IM1010` business scenario through promotion and the eight-gate HTTP diagnosis path.

**Architecture:** Keep existing fixed suites as fail-fast platform baselines. Add an isolated recorded-evidence seed lane whose pure criterion-shape generator creates negative and missing cases, then reuse the existing evaluator, fingerprint, attestation, review, approval, evidence router, diagnosis, and projection boundaries without adding another authority path.

**Tech Stack:** Java 21 records and sealed-pattern switches, Jackson, Spring Boot, JUnit 5/AssertJ, Maven, JSON classpath resources, Bash, curl, jq

---

### Task 1: Lock criterion-shape generation and catalog isolation

**Files:**
- Create: `mateclaw-server/src/test/java/vip/mate/troubleshooting/synthesis/ManualPlaybookRecordedEvidenceSeedTest.java`
- Modify: `mateclaw-server/src/test/java/vip/mate/troubleshooting/synthesis/ManualPlaybookReplaySuiteCatalogTest.java`

- [ ] **Step 1: Write failing tests for all six criterion shapes**

  Build one-candidate fixtures for `NumericGte`, `MissingOrLte`, `RatioOfSumGt`, `MultipleGt`, `ContainsAndIn`, and `BooleanEquals`. For each generated suite, assert:

  ```java
  assertThat(suite.cases())
          .extracting(ManualPlaybookReplaySuite.ReplayCase::expectedDisposition)
          .containsExactly(MATCHED, EXCLUDED, ABSTAINED);
  assertThat(evaluator.evaluate(seed.exampleCandidate(), suite).passed()).isTrue();
  ```

  Add a same-field conflict case and assert generation throws rather than producing an ambiguous negative.

- [ ] **Step 2: Write a failing catalog-isolation test**

  Load a v2 `ByteArrayResource` containing one valid fixed suite and one invalid recorded seed. Assert the fixed selector remains available and `catalog.rejectedSeeds()` contains exactly one bounded rejection with code `INVALID_RECORDED_EVIDENCE_SEED`.

- [ ] **Step 3: Verify RED**

  Run:

  ```bash
  mvn --settings mateclaw-server/settings.xml --batch-mode --no-transfer-progress \
    -pl mateclaw-server \
    -Dtest=ManualPlaybookRecordedEvidenceSeedTest,ManualPlaybookReplaySuiteCatalogTest test
  ```

  Expected: compilation fails because the seed contract, generator, and rejection API do not exist.

### Task 2: Implement the recorded-evidence seed lane

**Files:**
- Create: `mateclaw-server/src/main/java/vip/mate/troubleshooting/synthesis/ManualPlaybookRecordedEvidenceSeed.java`
- Create: `mateclaw-server/src/main/java/vip/mate/troubleshooting/synthesis/ManualPlaybookReplaySuiteTemplateFactory.java`
- Modify: `mateclaw-server/src/main/java/vip/mate/troubleshooting/synthesis/ManualPlaybookReplaySuiteCatalog.java`

- [ ] **Step 1: Add the bounded seed contract**

  Define `manual-playbook-recorded-evidence-seed.v1` with `suiteId`, `suiteVersion`, `selectorKey`, `requiredEvidenceRequestId`, `sourceReference`, `exampleCandidate`, and one positive `ReplayCase`. Its compact constructor must require an unverified candidate, an exact selector, `POSITIVE/MATCHED`, a safe source reference, bounded canonical aggregate values, and no secret-shaped string.

- [ ] **Step 2: Implement deterministic counterexamples**

  `ManualPlaybookReplaySuiteTemplateFactory.generate(seed)` must preserve the recorded positive case, clone its observed maps and replace every criterion field with a definitive counterexample, then add an all-`MISSING` case. Conflicting writes call:

  ```java
  throw new IllegalArgumentException(
          "criterion-shape template cannot produce one deterministic negative case");
  ```

  Resolve `requiredEvidenceRequest` from the candidate by exact request ID so target contracts are not duplicated in JSON.

- [ ] **Step 3: Load v1 and v2 catalogs without widening trust**

  Parse the document as a Jackson tree. Fixed `suites` retain fail-fast validation. For v2 `recordedEvidenceSeeds`, deserialize and generate each item inside its own try/catch; accept it only when the existing evaluator passes. Store only `RejectedSeed(reference, "INVALID_RECORDED_EVIDENCE_SEED")` for failures and never expose exception text or evidence.

- [ ] **Step 4: Verify GREEN**

  Re-run Task 1 tests. Expected: all criterion shapes pass, the conflict fails closed, and a bad generated seed does not remove the valid fixed suite.

### Task 3: Add the IM1010 sanitized recorded sample

**Files:**
- Rename: `mateclaw-server/src/main/resources/troubleshooting/evidence/recorded-replay-903001.json` → `mateclaw-server/src/main/resources/troubleshooting/evidence/recorded-replay-catalog.json`
- Modify: `mateclaw-server/src/main/resources/troubleshooting/replay/manual-playbook-replay-suites.json`
- Modify: `mateclaw-server/src/main/java/vip/mate/troubleshooting/evidence/EvidenceProperties.java`
- Modify: `mateclaw-server/src/main/resources/application.yml`
- Modify: tests referring to the old resource path under `mateclaw-server/src/test/java/vip/mate/troubleshooting/**`

- [ ] **Step 1: Add failing bundled-catalog assertions**

  Assert the evidence catalog can collect exact `CSDP / csp-rpc-msg / IM1010` `log_search` and `contrast_sample` records with the recorded aggregate values:

  ```java
  assertThat(search.observed()).containsEntry("match_count", 2);
  assertThat(contrast.observed())
          .containsEntry("failure_sample_count", 2)
          .containsEntry("failure_match_count", 2)
          .containsEntry("success_sample_count", 14047)
          .containsEntry("success_match_count", 0);
  ```

  Assert the generated suite `csdp:IM1010` covers `MATCHED`, `EXCLUDED`, and `ABSTAINED`, and its example has only non-write actions.

- [ ] **Step 2: Add the v2 seed and evidence records**

  Use `sourceReference=guance-spine-2026-07-31-message-send-failed`. The candidate rule requires `message_send_failure_present` plus `failure_feature_dominates`, reports a `MEDIUM` conclusion that the MQ producer path requires investigation, and recommends only Kafka health/network read checks plus human contact.

- [ ] **Step 3: Rename the generic evidence resource**

  Update the application default, `EvidenceProperties`, tests, and comments to the generic catalog path. Keep `version: 1` for the evidence-record schema because only its contents and filename change.

- [ ] **Step 4: Run focused evidence and catalog tests**

  Run:

  ```bash
  mvn --settings mateclaw-server/settings.xml --batch-mode --no-transfer-progress \
    -pl mateclaw-server \
    -Dtest=RecordedReplayAdapterTest,PlaybookSynthesisReplayEvalTest,SopSynthesisReplayTest,ManualPlaybookReplaySuiteCatalogTest test
  ```

  Expected: all tests pass and the adapter remains `verified=false`.

### Task 4: Promote IM1010 through the existing demo governance path

**Files:**
- Modify: `mateclaw-server/src/main/java/vip/mate/troubleshooting/demo/TroubleshootingDemoSeeder.java`
- Modify: `mateclaw-server/src/test/java/vip/mate/troubleshooting/demo/TroubleshootingDemoSeederTest.java`

- [ ] **Step 1: Write failing seeder tests**

  Assert `TroubleshootingDemoSeeder.selectors()` contains exactly `csdp:903001` and `csdp:IM1010`. For each selector, obtain the server-owned example, verify all evidence request IDs exist in the recorded catalog, run the fixed/generated replay, and assert the result passes with at least one positive and one negative-or-abstain case.

- [ ] **Step 2: Remove the Java-side duplicate 903001 Playbook**

  Make the seeder loop over server-owned selectors. For each example: skip only when that exact route is already approved; otherwise register by `sopId`, run replay, start review, and approve with `ts-demo-seeder`. One scenario failure logs and continues so it cannot suppress the other sample.

- [ ] **Step 3: Run governance tests**

  Run:

  ```bash
  mvn --settings mateclaw-server/settings.xml --batch-mode --no-transfer-progress \
    -pl mateclaw-server \
    -Dtest=TroubleshootingDemoSeederTest,ManualPlaybookReplayServiceTest,KnowledgeReviewWorkflowServiceTest test
  ```

  Expected: both demo candidates pass replay, and approval still creates an approved Playbook version through the existing review service.

### Task 5: Make IM1010 the executable HTTP acceptance scenario

**Files:**
- Modify: `scripts/ci/test-troubleshooting-smoke-workflow.sh`
- Modify: `scripts/troubleshooting-smoke.sh`
- Modify: `.github/workflows/troubleshooting-smoke.yml`

- [ ] **Step 1: Tighten the Shell contract first**

  Require the smoke defaults `SMOKE_SERVICE:-csp-rpc-msg` and `SMOKE_ERROR_CODE:-IM1010`; require the workflow readiness URL `/sops/csdp/IM1010`. Run the contract and observe failure before editing the script/workflow.

- [ ] **Step 2: Switch the default smoke payload**

  Use title `冒烟：客户 IM 消息发送失败`, severity `P0`, and retain `rehearsal=true`. Keep all eight gates and the explicit `fixtureMode=true` assertion unchanged.

- [ ] **Step 3: Point readiness at the new promoted route**

  The workflow must wait until `csdp:IM1010` is `approved`, then run the unchanged operator entry point.

- [ ] **Step 4: Run static acceptance**

  Run:

  ```bash
  bash -n scripts/troubleshooting-smoke.sh
  bash -n scripts/ci/test-troubleshooting-smoke-workflow.sh
  bash scripts/ci/test-troubleshooting-smoke-workflow.sh
  ./scripts/troubleshooting-smoke.sh --gates
  ```

  Expected: syntax and contract checks pass and all eight gates remain listed.

- [ ] **Step 5: Run the real local HTTP process acceptance**

  Start `dev,troubleshooting-demo`, wait for `/sops/csdp/IM1010` to be approved, then run:

  ```bash
  MATECLAW_USERNAME=admin MATECLAW_PASSWORD=admin123 \
    ./scripts/troubleshooting-smoke.sh
  ```

  Expected: exit 0, non-`INSUFFICIENT_EVIDENCE` conclusion, headline describing the message-send path, developer evidence steps greater than zero, and `fixtureMode=true`.

### Task 6: Publish the decision and verification evidence

**Files:**
- Create: `docs/intelligent-troubleshooting/versions/v0.19/**` as a new snapshot copied from v0.18
- Modify: `docs/intelligent-troubleshooting/versions/v0.19/intelligent-troubleshooting-architecture-v4.md`
- Modify: `docs/intelligent-troubleshooting/versions/v0.19/VERSION.md`
- Modify: `docs/intelligent-troubleshooting/versions/v0.19/MANIFEST.sha256`
- Modify: `docs/intelligent-troubleshooting/versions/README.md`
- Modify: `docs/intelligent-troubleshooting/versions/index.html`
- Modify: `docs/intelligent-troubleshooting/TODO.md`
- Modify: `docs/intelligent-troubleshooting/quickstart.md`

- [ ] **Step 1: Add D19 to RFC v4.5 without modifying v0.18**

  §5.7 must state: a sanitized recorded positive may seed deterministic criterion-shape negative/missing cases; generated cases do not lower D5′, replace review, or satisfy T7/T8. Add D19 to the decision table and a v4.5 revision note.

- [ ] **Step 2: Update operator docs and T0.8 status**

  Mark the mechanism and the first IM1010 slice complete, but leave bulk onboarding of the remaining 145 routes open. Document the exact demo commands and the historical/fixture boundary.

- [ ] **Step 3: Refresh the immutable snapshot manifest and index**

  Recalculate SHA-256 for every v0.19 file except `MANIFEST.sha256`; validate links and ensure v0.18 hashes/content are unchanged.

- [ ] **Step 4: Run final verification**

  Run focused troubleshooting tests, Shell checks, JSON/XML parsers where applicable, `git diff --check`, and a secret scan over changed files. Read every result before reporting completion; any failed verification remains explicit rather than being relabeled as success.
