# Troubleshooting Smoke CI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a deterministic GitHub Actions regression that boots the troubleshooting demo, runs all eight HTTP smoke gates, and reports checkout-to-diagnosis time.

**Architecture:** Keep `scripts/troubleshooting-smoke.sh` as the single operator-facing acceptance path. A dedicated workflow owns runner setup, bounded server lifecycle, timing, and log artifacts; a dependency-free Shell contract test locks the workflow semantics before implementation.

**Tech Stack:** GitHub Actions, Bash, Java 21, Maven, curl, jq, Spring Boot demo profile

---

### Task 1: Lock the workflow contract

**Files:**
- Create: `scripts/ci/test-troubleshooting-smoke-workflow.sh`

- [ ] **Step 1: Write a failing Shell contract test**

  The test must fail when `.github/workflows/troubleshooting-smoke.yml` is absent and then assert the Java version, plugin installation ordering, demo profiles, bounded readiness loop, existing smoke script invocation, five-minute timing target, cleanup, and unconditional log upload.

- [ ] **Step 2: Verify the RED state**

  Run: `bash scripts/ci/test-troubleshooting-smoke-workflow.sh`

  Expected: exit 1 with `missing workflow` because the workflow does not exist yet.

### Task 2: Implement the bounded smoke workflow

**Files:**
- Create: `.github/workflows/troubleshooting-smoke.yml`

- [ ] **Step 1: Add relevant PR, dev-push, and manual triggers**

  Limit automatic runs to the root POM, plugin API, server, smoke script, contract test, or workflow itself.

- [ ] **Step 2: Add deterministic runner setup and build ordering**

  Use Temurin Java 21 with Maven caching. Install `jq` only when absent. Run `mvn --batch-mode --no-transfer-progress -pl mateclaw-plugin-api -DskipTests install` before server startup.

- [ ] **Step 3: Add bounded server lifecycle and smoke execution**

  Start Spring Boot with `dev,troubleshooting-demo`, retain PID and log paths in `GITHUB_ENV`, and poll the login HTTP boundary for at most 120 seconds before running the existing smoke script with the ephemeral demo administrator.

- [ ] **Step 4: Report timing and retain diagnostics**

  Write checkout-complete-to-diagnosis seconds and the 300-second goal to `GITHUB_STEP_SUMMARY`. Emit a warning rather than a timing-only failure above 300 seconds. Stop the server and upload logs under `if: always()`.

- [ ] **Step 5: Verify the GREEN state**

  Run: `bash scripts/ci/test-troubleshooting-smoke-workflow.sh`

  Expected: all workflow contract assertions pass.

### Task 3: Update operator documentation and run focused verification

**Files:**
- Modify: `docs/intelligent-troubleshooting/TODO.md`
- Modify: `docs/intelligent-troubleshooting/quickstart.md`

- [ ] **Step 1: Mark the CI wiring complete without overstating T7**

  Check off only the T0.7 CI item. Keep clone-to-diagnosis tracking open until the workflow has real run history, and document that the CI metric begins after checkout.

- [ ] **Step 2: Document the CI contract**

  Add the workflow path, triggers, eight-gate behavior, timing semantics, and log artifact to Quickstart.

- [ ] **Step 3: Run static and script verification**

  Run:

  ```bash
  bash -n scripts/ci/test-troubleshooting-smoke-workflow.sh
  bash scripts/ci/test-troubleshooting-smoke-workflow.sh
  bash -n scripts/troubleshooting-smoke.sh
  ./scripts/troubleshooting-smoke.sh --gates
  ```

  Expected: syntax checks exit 0, the contract test passes, and `--gates` prints all eight gates.

- [ ] **Step 4: Run focused Java tests**

  Run:

  ```bash
  mvn --batch-mode --no-transfer-progress -pl mateclaw-server \
    -Dtest=TroubleshootingDemoSeederTest,ManualPlaybookReplaySuiteCatalogTest,ManualPlaybookReplayServiceTest \
    test
  ```

  Expected: Maven exits 0 with no failing focused tests.

- [ ] **Step 5: Review the final diff**

  Confirm the workflow never enables Guance, never removes fixture markers, introduces no credentials beyond the ephemeral demo defaults, and does not mark the timing-history item complete.
