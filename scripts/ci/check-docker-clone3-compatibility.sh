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

[[ "$#" -eq 5 ]] || fail \
  "usage: $0 <docker-server-version> <docker-security-options> <runtime-dockerfile> <legacy-seccomp-profile> <legacy-runtime-image-record>"

raw_version="$1"
security_options="$2"
runtime_dockerfile="$3"
legacy_seccomp_profile="$4"
legacy_runtime_image_record="$5"
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
runtime_probe_image="${runtime_image}"
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
  if [[ -e "${legacy_runtime_image_record}" || -L "${legacy_runtime_image_record}" ]]; then
    [[ ! -L "${legacy_runtime_image_record}" ]] || fail \
      "Docker 18 基础镜像维护记录不允许是符号链接：${legacy_runtime_image_record}"
    [[ -f "${legacy_runtime_image_record}" ]] || fail \
      "Docker 18 基础镜像维护记录不是普通文件：${legacy_runtime_image_record}"
    record_identity_before="$(LC_ALL=C stat -c '%d:%i:%u:%a:%F' "${legacy_runtime_image_record}")" || fail \
      "无法读取 Docker 18 基础镜像维护记录元数据"
    IFS=: read -r record_device record_inode record_uid record_mode record_type \
      <<<"${record_identity_before}"
    [[ "${record_type}" == "regular file" ]] || fail \
      "Docker 18 基础镜像维护记录不是普通文件：${record_type}"
    [[ "${record_uid}" == "0" ]] || fail \
      "Docker 18 基础镜像维护记录必须归 root 所有，当前 uid=${record_uid}"
    case "${record_mode}" in
      400|600) ;;
      *) fail "Docker 18 基础镜像维护记录权限必须为 0400 或 0600，当前为 ${record_mode}" ;;
    esac

    record_runtime_image_count=0
    record_runtime_image_id_count=0
    recorded_runtime_image=""
    recorded_runtime_image_id=""
    while IFS= read -r record_line || [[ -n "${record_line}" ]]; do
      case "${record_line}" in
        runtime_image=*)
          record_runtime_image_count=$((record_runtime_image_count + 1))
          recorded_runtime_image="${record_line#runtime_image=}"
          ;;
        runtime_image_id=*)
          record_runtime_image_id_count=$((record_runtime_image_id_count + 1))
          recorded_runtime_image_id="${record_line#runtime_image_id=}"
          ;;
      esac
    done < "${legacy_runtime_image_record}"
    record_identity_after="$(LC_ALL=C stat -c '%d:%i:%u:%a:%F' "${legacy_runtime_image_record}")" || fail \
      "无法复核 Docker 18 基础镜像维护记录元数据"
    [[ "${record_identity_after}" == "${record_identity_before}" ]] || fail \
      "Docker 18 基础镜像维护记录在读取期间发生变化"
    [[ "${record_runtime_image_count}" -eq 1 ]] || fail \
      "Docker 18 基础镜像维护记录必须且只能包含一个 runtime_image"
    [[ "${record_runtime_image_id_count}" -eq 1 ]] || fail \
      "Docker 18 基础镜像维护记录必须且只能包含一个 runtime_image_id"
    [[ "${recorded_runtime_image}" == "${runtime_image}" ]] || fail \
      "Docker 18 基础镜像维护记录引用与 Dockerfile 不一致：${recorded_runtime_image:-EMPTY}"
    [[ "${recorded_runtime_image_id}" =~ ^sha256:[0-9a-f]{64}$ ]] || fail \
      "Docker 18 基础镜像维护记录 ID 格式非法：${recorded_runtime_image_id:-EMPTY}"
    recorded_actual_image_id=""
    recorded_image_inspect_status=0
    recorded_actual_image_id="$(
      bounded_docker "${DOCKER_METADATA_TIMEOUT_SECONDS}" inspect --format '{{.Id}}' \
        "${recorded_runtime_image_id}"
    )" || recorded_image_inspect_status=$?
    [[ "${recorded_image_inspect_status}" -eq 0 ]] || fail \
      "Docker 18 基础镜像维护记录指向的不可变镜像不存在"
    [[ "${recorded_actual_image_id}" == "${recorded_runtime_image_id}" ]] || fail \
      "Docker 18 基础镜像维护记录 ID 与 Docker inspect 结果不一致"
    runtime_image_source="MAINTENANCE_RECORD"
    runtime_probe_image="${recorded_runtime_image_id}"
    progress "maintenance-record-recovery=PASSED image_id=${recorded_runtime_image_id}"
  else
    progress "maintenance-record-recovery=SKIPPED result=ABSENT"
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
fi
progress "image-id-inspect=START timeout=${DOCKER_METADATA_TIMEOUT_SECONDS}s image=${runtime_probe_image}"
runtime_image_id=""
image_id_inspect_status=0
runtime_image_id="$(
  bounded_docker "${DOCKER_METADATA_TIMEOUT_SECONDS}" inspect --format '{{.Id}}' "${runtime_probe_image}"
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
  "${runtime_image_id}" \
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
