#!/usr/bin/env bash

set -euo pipefail

MINIMUM_DOCKER_VERSION="20.10.10"
REVIEWED_LEGACY_DOCKER_VERSION="18.06.0"
MINIMUM_LEGACY_LIBSECCOMP_VERSION="2.5.0"
REVIEWED_LEGACY_SECCOMP_SHA256="959c7b5f83f4fa6f0bec17dab25434fafa399b11e84661a30c725bece3d5473d"
DOCKER_METADATA_TIMEOUT_SECONDS="15"
DOCKER_PULL_TIMEOUT_SECONDS="300"
DOCKER_PROBE_TIMEOUT_SECONDS="30"
DOCKER_CLEANUP_TIMEOUT_SECONDS="15"
DOCKER_TIMEOUT_KILL_AFTER_SECONDS="5"

fail() {
  printf 'DOCKER_RUNTIME_PRECHECK_FAILED: %s\n' "$1" >&2
  exit 1
}

progress() {
  printf 'DOCKER_RUNTIME_PRECHECK_STAGE: %s\n' "$1" >&2
}

bounded_docker() {
  local timeout_seconds="$1"
  shift
  timeout \
    --signal=TERM \
    --kill-after="${DOCKER_TIMEOUT_KILL_AFTER_SECONDS}s" \
    "${timeout_seconds}s" \
    docker "$@"
}

[[ "$#" -eq 4 ]] || fail \
  "usage: $0 <docker-server-version> <docker-security-options> <runtime-dockerfile> <legacy-seccomp-profile>"

raw_version="$1"
security_options="$2"
runtime_dockerfile="$3"
legacy_seccomp_profile="$4"
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
checker_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
libseccomp_detector="${checker_dir}/detect-libseccomp-version.sh"
[[ -x "${libseccomp_detector}" ]] || fail \
  "缺少可执行的实际 libseccomp 版本检测器：${libseccomp_detector}"
legacy_libseccomp_version="$("${libseccomp_detector}")" || fail \
  "无法核验 Docker 18 宿主实际加载的 libseccomp"
lowest_libseccomp_version="$(
  printf '%s\n%s\n' "${MINIMUM_LEGACY_LIBSECCOMP_VERSION}" "${legacy_libseccomp_version}" \
    | sort -V \
    | head -n 1
)"
[[ "${lowest_libseccomp_version}" == "${MINIMUM_LEGACY_LIBSECCOMP_VERSION}" ]] || fail \
  "Docker 18 宿主 libseccomp ${legacy_libseccomp_version} 不认识 clone3；至少需要 ${MINIMUM_LEGACY_LIBSECCOMP_VERSION}，禁止用无效 profile 或 seccomp=unconfined 绕过"
[[ -f "${legacy_seccomp_profile}" ]] || fail \
  "Docker 18 clone3 seccomp profile 不存在：${legacy_seccomp_profile}"
legacy_seccomp_sha256="$(sha256sum "${legacy_seccomp_profile}" | awk '{print $1}')"
[[ "${legacy_seccomp_sha256}" == "${REVIEWED_LEGACY_SECCOMP_SHA256}" ]] || fail \
  "Docker 18 clone3 seccomp profile 哈希未通过审核：${legacy_seccomp_sha256}"
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
command -v timeout >/dev/null || fail "宿主缺少 timeout，无法有界执行旧版本兼容探针"
runtime_image_source="LOCAL_CACHE"
progress "image-cache-inspect=START timeout=${DOCKER_METADATA_TIMEOUT_SECONDS}s image=${runtime_image}"
cache_inspect_status=0
bounded_docker "${DOCKER_METADATA_TIMEOUT_SECONDS}" inspect "${runtime_image}" \
  >/dev/null 2>&1 || cache_inspect_status=$?
if [[ "${cache_inspect_status}" -eq 0 ]]; then
  progress "image-cache-inspect=PASSED result=HIT"
else
  if [[ "${cache_inspect_status}" -eq 124 || "${cache_inspect_status}" -eq 137 ]]; then
    progress "image-cache-inspect=FAILED exit=${cache_inspect_status}"
    fail "生产运行基础镜像缓存检查超过 ${DOCKER_METADATA_TIMEOUT_SECONDS} 秒"
  fi
  progress "image-cache-inspect=PASSED result=MISS exit=${cache_inspect_status}"
  progress "image-pull=START timeout=${DOCKER_PULL_TIMEOUT_SECONDS}s image=${runtime_image}"
  pull_status=0
  bounded_docker "${DOCKER_PULL_TIMEOUT_SECONDS}" pull "${runtime_image}" \
    >/dev/null || pull_status=$?
  if [[ "${pull_status}" -ne 0 ]]; then
    progress "image-pull=FAILED exit=${pull_status}"
    fail "宿主不存在生产运行基础镜像，且未能在 ${DOCKER_PULL_TIMEOUT_SECONDS} 秒内拉取 ${runtime_image}"
  fi
  progress "image-pull=PASSED"
  runtime_image_source="PULLED"
fi
progress "image-id-inspect=START timeout=${DOCKER_METADATA_TIMEOUT_SECONDS}s image=${runtime_image}"
runtime_image_id=""
image_id_inspect_status=0
runtime_image_id="$(
  bounded_docker "${DOCKER_METADATA_TIMEOUT_SECONDS}" inspect --format '{{.Id}}' "${runtime_image}"
)" || image_id_inspect_status=$?
if [[ "${image_id_inspect_status}" -ne 0 ]]; then
  progress "image-id-inspect=FAILED exit=${image_id_inspect_status}"
  fail "未能在 ${DOCKER_METADATA_TIMEOUT_SECONDS} 秒内确认生产运行基础镜像 ID"
fi
[[ -n "${runtime_image_id}" ]] || fail "无法确认生产运行基础镜像 ID"
progress "image-id-inspect=PASSED image_id=${runtime_image_id}"

probe_container="mateclaw-clone3-probe-$$"
probe_cleanup_attempted="false"
cleanup_status=0
cleanup_probe() {
  if [[ "${probe_cleanup_attempted}" == "true" ]]; then
    return "${cleanup_status}"
  fi
  probe_cleanup_attempted="true"
  progress "exact-name-cleanup=START timeout=${DOCKER_CLEANUP_TIMEOUT_SECONDS}s container=${probe_container}"
  cleanup_status=0
  bounded_docker "${DOCKER_CLEANUP_TIMEOUT_SECONDS}" rm -f "${probe_container}" \
    >/dev/null 2>&1 || cleanup_status=$?
  if [[ "${cleanup_status}" -eq 0 ]]; then
    progress "exact-name-cleanup=PASSED container=${probe_container}"
    return 0
  fi
  progress "exact-name-cleanup=FAILED exit=${cleanup_status} container=${probe_container}"
  return "${cleanup_status}"
}
trap 'cleanup_probe || true' EXIT
progress "runtime-probe-run=START timeout=${DOCKER_PROBE_TIMEOUT_SECONDS}s container=${probe_container}"
probe_status=0
bounded_docker "${DOCKER_PROBE_TIMEOUT_SECONDS}" run \
  --name "${probe_container}" \
  --security-opt "seccomp=${legacy_seccomp_profile}" \
  --entrypoint node \
  "${runtime_image}" \
  -e 'const {Worker}=require("worker_threads");const w=new Worker("process.exit(0)",{eval:true});w.once("error",()=>process.exit(1));w.once("exit",code=>process.exit(code));' \
  || probe_status=$?
if [[ "${probe_status}" -eq 0 ]]; then
  progress "runtime-probe-run=PASSED"
else
  progress "runtime-probe-run=FAILED exit=${probe_status}"
fi
cleanup_probe || cleanup_status=$?
trap - EXIT
if [[ "${probe_status}" -ne 0 && "${cleanup_status}" -ne 0 ]]; then
  fail "Docker ${raw_version} 未能在 ${DOCKER_PROBE_TIMEOUT_SECONDS} 秒内通过已审核 clone3 seccomp 的线程创建探针，且精确名称清理失败（run=${probe_status}, cleanup=${cleanup_status}）"
fi
[[ "${cleanup_status}" -eq 0 ]] \
  || fail "Docker 18 探针完成后的精确名称清理失败（exit=${cleanup_status}）"
[[ "${probe_status}" -eq 0 ]] \
  || fail "Docker ${raw_version} 未能在 ${DOCKER_PROBE_TIMEOUT_SECONDS} 秒内通过已审核 clone3 seccomp 的线程创建探针"

printf 'docker_runtime_compatibility=LEGACY_CUSTOM_SECCOMP\n'
printf 'legacy_runtime_probe=PASSED\n'
printf 'legacy_runtime_probe_image=%s\n' "${runtime_image}"
printf 'legacy_runtime_probe_image_id=%s\n' "${runtime_image_id}"
printf 'legacy_runtime_probe_image_source=%s\n' "${runtime_image_source}"
printf 'legacy_seccomp_profile_sha256=%s\n' "${legacy_seccomp_sha256}"
printf 'legacy_libseccomp_version=%s\n' "${legacy_libseccomp_version}"
printf 'docker_server_version=%s\n' "${raw_version}"
