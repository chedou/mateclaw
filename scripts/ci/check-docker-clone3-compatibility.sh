#!/usr/bin/env bash

set -euo pipefail

MINIMUM_DOCKER_VERSION="20.10.10"
REVIEWED_LEGACY_DOCKER_VERSION="18.06.0"

fail() {
  printf 'DOCKER_RUNTIME_PRECHECK_FAILED: %s\n' "$1" >&2
  exit 1
}

[[ "$#" -eq 3 ]] || fail \
  "usage: $0 <docker-server-version> <docker-security-options> <runtime-dockerfile>"

raw_version="$1"
security_options="$2"
runtime_dockerfile="$3"
if [[ "${raw_version}" =~ ^([0-9]+\.[0-9]+\.[0-9]+)([-+].*)?$ ]]; then
  normalized_version="${BASH_REMATCH[1]}"
else
  fail "无法解析 Docker Server 版本：${raw_version}"
fi

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

lowest_version="$(
  printf '%s\n%s\n' "${MINIMUM_DOCKER_VERSION}" "${normalized_version}" \
    | sort -V \
    | head -n 1
)"
if [[ "${lowest_version}" == "${MINIMUM_DOCKER_VERSION}" ]]; then
  printf 'docker_runtime_compatibility=NATIVE_CLONE3_SECCOMP\n'
  printf 'legacy_runtime_probe=NOT_REQUIRED\n'
  printf 'docker_server_version=%s\n' "${raw_version}"
  exit 0
fi

[[ "${normalized_version}" == "${REVIEWED_LEGACY_DOCKER_VERSION}" ]] || fail \
  "Docker Server ${raw_version} 低于 ${MINIMUM_DOCKER_VERSION} 且不属于已审核的 ${REVIEWED_LEGACY_DOCKER_VERSION} 兼容例外"
[[ -f "${runtime_dockerfile}" ]] || fail \
  "生产运行 Dockerfile 不存在：${runtime_dockerfile}"

runtime_image="$(
  awk 'toupper($1) == "FROM" { image = $2 } END { print image }' \
    "${runtime_dockerfile}"
)"
case "${runtime_image}" in
  mcr.microsoft.com/playwright:v*-noble) ;;
  *) fail "旧 Docker 兼容探针只允许使用生产 Dockerfile 最终固定的 Playwright Noble 镜像，实际为：${runtime_image:-EMPTY}" ;;
esac

command -v docker >/dev/null || fail "docker 命令不存在，无法执行旧版本兼容探针"
docker pull "${runtime_image}" >/dev/null \
  || fail "无法拉取生产运行基础镜像 ${runtime_image}"
runtime_image_id="$(docker inspect --format '{{.Id}}' "${runtime_image}")"
[[ -n "${runtime_image_id}" ]] || fail "无法确认生产运行基础镜像 ID"

docker run --rm \
  --entrypoint node \
  "${runtime_image}" \
  -e 'const {Worker}=require("worker_threads");const w=new Worker("process.exit(0)",{eval:true});w.once("error",()=>process.exit(1));w.once("exit",code=>process.exit(code));' \
  || fail "Docker ${raw_version} 未能在默认 seccomp 下运行生产基础镜像的线程创建探针"

printf 'docker_runtime_compatibility=LEGACY_RUNTIME_PROBE_PASSED\n'
printf 'legacy_runtime_probe=PASSED\n'
printf 'legacy_runtime_probe_image=%s\n' "${runtime_image}"
printf 'legacy_runtime_probe_image_id=%s\n' "${runtime_image_id}"
printf 'docker_server_version=%s\n' "${raw_version}"
