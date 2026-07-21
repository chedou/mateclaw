---
name: loop-fix-failing-test
description: Loop Engineering superpower for repairing one reproducible failing test with evidence, diff review, and a human gate before push.
version: 0.1.0
type: prompt
category: superpower
author: MateClaw
tags: [loop-engineering, superpower, code-refix, test]
superpower:
  domain: code_refix
  scenario: fix_failing_test
  trigger:
    type: manual
    sources: [manual, ci_failure, pr_comment]
  workspace:
    isolation: git_worktree
    allowedPaths: [src, test, tests, mateclaw-server/src, mateclaw-ui/src]
  policy:
    maxIterations: 3
    maxChangedFiles: 8
    requireHumanBeforePush: true
    allowedCommands:
      - mvn test
      - mvn -pl mateclaw-server -Dtest=vip.mate.skill.manifest.SkillManifestParserTest test
      - npm test
      - pnpm test
      - npm run test
    allowedRepairCommands:
      - npm run loop:repair
      - pnpm run loop:repair
      - python3 .mate/loop/repair.py
  verification:
    required: [baseline_failure_reproduced, target_tests_pass, diff_review_passed]
    recommended: [lint_passed, build_passed]
  outputs: [evidence.md, diff.patch, review.md, result.json]
  owner: engineering-platform
  reviewCycleDays: 90
---
# Fix Failing Test Loop

## Applicability

Use this superpower only when the task has one reproducible failing test or a
small test command that fails before any change is made. Do not use it for broad
feature work, unclear refactors, or production troubleshooting.

## Loop Contract

1. Reproduce the baseline failure and capture the command, exit code, and key log lines.
2. Create or use an isolated worktree so the loop cannot mutate the caller's active branch.
3. Make the smallest code change that could explain the failure.
4. Re-run the target test command.
5. Review the diff and evidence independently from the writer step.
6. Stop after at most three repair attempts or when evidence is insufficient.

## Stop Conditions

Stop with `needs_human` when the baseline failure cannot be reproduced, the
change would exceed the file limit, the test command needs a destructive action,
or the reviewer cannot approve the diff.

## Report Shape

Return strict JSON containing `conclusion`, `status`, `attempts`,
`changedFiles[]`, `verification[]`, `artifactIds[]`, `nextAction`, and
`humanGateRequired`.
