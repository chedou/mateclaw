#!/usr/bin/env bash

set -euo pipefail

MINIMUM_DOCKER_VERSION="20.10.10"

fail() {
  printf 'DOCKER_RUNTIME_PRECHECK_FAILED: %s\n' "$1" >&2
  exit 1
}

[[ "$#" -eq 2 ]] || fail \
  "usage: $0 <docker-server-version> <docker-security-options>"

raw_version="$1"
security_options="$2"
if [[ "${raw_version}" =~ ^([0-9]+\.[0-9]+\.[0-9]+)([-+].*)?$ ]]; then
  normalized_version="${BASH_REMATCH[1]}"
else
  fail "无法解析 Docker Server 版本：${raw_version}"
fi

lowest_version="$(
  printf '%s\n%s\n' "${MINIMUM_DOCKER_VERSION}" "${normalized_version}" \
    | sort -V \
    | head -n 1
)"

[[ "${lowest_version}" == "${MINIMUM_DOCKER_VERSION}" ]] || fail \
  "Docker Server ${raw_version} 过旧；现代 glibc clone3 至少需要 ${MINIMUM_DOCKER_VERSION}，正式环境应使用组织仍在支持的更新版本"

seccomp_enabled="false"
while IFS= read -r security_option; do
  case "${security_option}" in
    name=seccomp,profile=default|name=seccomp,profile=builtin)
      seccomp_enabled="true"
      break
      ;;
  esac
done <<<"${security_options}"

[[ "${seccomp_enabled}" == "true" ]] || fail \
  "Docker daemon 未报告启用默认 seccomp profile；禁止在隔离关闭或 profile 未审核的宿主上发布"

printf 'PASS: Docker Server %s supports clone3-aware seccomp behavior with seccomp enabled\n' \
  "${raw_version}"
