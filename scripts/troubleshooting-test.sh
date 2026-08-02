#!/usr/bin/env bash
#
# Tiered test runner for the troubleshooting domain.
#
# Why tiers. Measured on this repo (warm build, incremental compile):
#
#   scenario   intake + synthesis            ~13s     the first-scenario chain
#   domain     vip.mate.troubleshooting.**   ~67s     612 tests
#   all        whole module                  >10min   741 tests
#
# The interesting number is the last one: 129 extra tests cost more than ten
# times the wall clock. The cost is not test count, it is the handful of
# heavy-context classes outside this domain. So "run everything, every time"
# is not carefulness — it is a 10-minute tax on feedback that mostly re-proves
# code the change never touched.
#
# Pick the smallest tier that can actually observe your change:
#
#   ./scripts/troubleshooting-test.sh scenario   # editing intake / synthesis
#   ./scripts/troubleshooting-test.sh domain     # before committing (default)
#   ./scripts/troubleshooting-test.sh all        # shared code, contracts, or
#                                                # anything outside the domain
#
# Tier choice is a claim about blast radius, and a wrong claim is silent. When
# a change touches a shared contract (Diagnosis, EvidenceResult, a Flyway
# migration, anything under vip.mate.common / vip.mate.channel), the domain
# tier can pass while the repo is broken — use `all`, or let CI catch it.

set -euo pipefail

TIER="${1:-domain}"
MODULE_ARGS=(-o -pl mateclaw-server -Dsurefire.failIfNoSpecifiedTests=false)

case "${TIER}" in
  scenario)
    PATTERN='vip.mate.troubleshooting.intake.**.*Test,vip.mate.troubleshooting.synthesis.**.*Test'
    LABEL='场景层：第一个场景那条链（补问 → 取证 → 归纳 → 候选）'
    ;;
  domain)
    PATTERN='vip.mate.troubleshooting.**.*Test'
    LABEL='领域层：整个排障域'
    ;;
  all)
    PATTERN=''
    LABEL='全仓：包含域外共享代码，慢，只在动了契约/共享件时用'
    ;;
  -h|--help)
    sed -n '3,28p' "$0" | sed 's/^# \{0,1\}//'
    exit 0
    ;;
  *)
    printf '未知层级：%s（可选 scenario / domain / all）\n' "${TIER}" >&2
    exit 2
    ;;
esac

printf '\033[34m%s\033[0m\n' "${LABEL}"
started=$(date +%s)

if [[ -n "${PATTERN}" ]]; then
  mvn "${MODULE_ARGS[@]}" test -Dtest="${PATTERN}"
else
  mvn -o -pl mateclaw-server test
fi
status=$?

elapsed=$(( $(date +%s) - started ))
printf '\033[90m用时 %ss\033[0m\n' "${elapsed}"

# The tier only proves what it covers. Say so rather than letting a green
# scenario run read as "the repo is fine".
if [[ ${status} -eq 0 && "${TIER}" != "all" ]]; then
  printf '\033[90m注意：本层只覆盖 %s；动到共享契约时请跑 all 或依赖 CI。\033[0m\n' \
    "${PATTERN}"
fi
exit ${status}
