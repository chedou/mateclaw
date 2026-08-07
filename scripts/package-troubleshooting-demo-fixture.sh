#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TEST_CLASSES="${ROOT_DIR}/mateclaw-server/target/test-classes"
OUTPUT="${1:-${ROOT_DIR}/mateclaw-server/target/troubleshooting-demo-fixture.jar}"

if [[ "${OUTPUT}" != /* ]]; then
  OUTPUT="${ROOT_DIR}/${OUTPUT}"
fi

FIXTURE_ENTRIES=(
  "vip/mate/troubleshooting/demo/TroubleshootingDemoProperties.class"
  "vip/mate/troubleshooting/demo/TroubleshootingDemoSeeder.class"
  "vip/mate/troubleshooting/demo/TroubleshootingDemoFixtureAutoConfiguration.class"
  "vip/mate/troubleshooting/synthesis/RecordedPlaybookDraftInducer.class"
  'vip/mate/troubleshooting/synthesis/RecordedPlaybookDraftInducer$Recorded.class'
  "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports"
  "application-troubleshooting-demo.yml"
  "troubleshooting/synthesis/recorded-draft-proposals.json"
)

for entry in "${FIXTURE_ENTRIES[@]}"; do
  if [[ ! -f "${TEST_CLASSES}/${entry}" ]]; then
    printf 'Missing demo fixture artifact: %s\n' "${entry}" >&2
    printf 'Run: mvn -pl mateclaw-server -DskipTests test-compile\n' >&2
    exit 1
  fi
done

mkdir -p "$(dirname "${OUTPUT}")"
(
  cd "${TEST_CLASSES}"
  jar cf "${OUTPUT}" "${FIXTURE_ENTRIES[@]}"
)

printf '%s\n' "${OUTPUT}"
