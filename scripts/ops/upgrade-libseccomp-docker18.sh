#!/usr/bin/env bash

set -Eeuo pipefail
umask 077

VERSION="2.5.6"
SOURCE_URL="https://github.com/seccomp/libseccomp/releases/download/v${VERSION}/libseccomp-${VERSION}.tar.gz"
SOURCE_SHA256="04c37d72965dce218a0c94519b056e1775cf786b5260ee2b7992956c4ee38633"
RUNTIME_IMAGE="mcr.microsoft.com/playwright:v1.62.0-noble"
PROFILE_SHA256="959c7b5f83f4fa6f0bec17dab25434fafa399b11e84661a30c725bece3d5473d"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

if [[ "${MATECLAW_LIBSECCOMP_TEST_MODE:-0}" == "1" ]]; then
  LIBDIR="${MATECLAW_LIBSECCOMP_LIBDIR:?test libdir is required}"
  PREFIX="${MATECLAW_LIBSECCOMP_PREFIX:?test prefix is required}"
  STATE_DIR="${MATECLAW_LIBSECCOMP_STATE_DIR:?test state dir is required}"
  DETECTOR="${MATECLAW_LIBSECCOMP_DETECTOR:?test detector is required}"
  TEST_MODE=1
else
  LIBDIR="/lib64"
  PREFIX="/opt/mateclaw/libseccomp-${VERSION}"
  STATE_DIR="/opt/mateclaw/libseccomp-maintenance"
  DETECTOR="${ROOT_DIR}/scripts/ci/detect-libseccomp-version.sh"
  TEST_MODE=0
fi

NEW_LIBRARY="${PREFIX}/lib64/libseccomp.so.${VERSION}"
ACTIVE_LIBRARY_BASENAME="libseccomp.so.${VERSION}-mateclaw"
ACTIVE_LIBRARY="${LIBDIR}/${ACTIVE_LIBRARY_BASENAME}"
SONAME_LINK="${LIBDIR}/libseccomp.so.2"

fail() {
  printf 'LIBSECCOMP_MAINTENANCE_FAILED: %s\n' "$1" >&2
  exit 1
}

usage() {
  cat <<'EOF'
Usage:
  upgrade-libseccomp-docker18.sh --prepare
  upgrade-libseccomp-docker18.sh --activate <reviewed-seccomp-profile>
  upgrade-libseccomp-docker18.sh --all <reviewed-seccomp-profile>
EOF
}

require_host_baseline() {
  if [[ "${TEST_MODE}" != "1" ]]; then
    [[ "$(id -u)" == "0" ]] || fail "必须由 root 执行"
  fi
  command -v docker >/dev/null 2>&1 || fail "docker 命令不存在"
  docker info >/dev/null 2>&1 || fail "无法连接 Docker daemon"
  docker_version="$(docker version --format '{{.Server.Version}}')"
  [[ "${docker_version}" == 18.06.0* ]] || fail \
    "该维护动作只允许 Docker 18.06.0，实际为 ${docker_version}"
  [[ -x "${DETECTOR}" ]] || fail "libseccomp 检测器不可执行：${DETECTOR}"
  mkdir -p "${STATE_DIR}" "${PREFIX}/lib64"
}

detect_loaded_version() {
  "${DETECTOR}"
}

prepare_library() {
  require_host_baseline
  for command_name in curl sha256sum tar gcc make install readelf; do
    command -v "${command_name}" >/dev/null 2>&1 \
      || fail "缺少构建命令：${command_name}"
  done

  tarball="${STATE_DIR}/libseccomp-${VERSION}.tar.gz"
  if [[ -f "${tarball}" ]]; then
    printf '%s  %s\n' "${SOURCE_SHA256}" "${tarball}" | sha256sum --check --strict - \
      || fail "已存在源码包哈希不正确：${tarball}"
  else
    download="${tarball}.download.$$"
    curl --fail --location --retry 3 --proto '=https' --tlsv1.2 \
      --output "${download}" "${SOURCE_URL}" \
      || fail "下载官方 libseccomp 源码失败"
    printf '%s  %s\n' "${SOURCE_SHA256}" "${download}" | sha256sum --check --strict - \
      || fail "官方 libseccomp 源码 SHA-256 不匹配"
    mv "${download}" "${tarball}"
  fi

  build_dir="$(mktemp -d "${STATE_DIR}/build-${VERSION}.XXXXXX")"
  tar -xzf "${tarball}" -C "${build_dir}"
  source_dir="${build_dir}/libseccomp-${VERSION}"
  [[ -x "${source_dir}/configure" ]] || fail "发布源码缺少 configure"
  (
    cd "${source_dir}"
    ./configure --prefix="${PREFIX}" --libdir="${PREFIX}/lib64" --disable-python
    make -j2
    make install
  )

  [[ -f "${NEW_LIBRARY}" ]] || fail "编译结果缺少 ${NEW_LIBRARY}"
  readelf -d "${NEW_LIBRARY}" | grep -Fq 'Library soname: [libseccomp.so.2]' \
    || fail "编译结果的 SONAME 不是 libseccomp.so.2"
  prepared_version="$(LD_LIBRARY_PATH="${PREFIX}/lib64" "${DETECTOR}")" \
    || fail "无法加载隔离目录中的新版 libseccomp"
  [[ "${prepared_version}" == "${VERSION}" ]] || fail \
    "隔离目录加载版本不一致：${prepared_version}"

  python_bin=""
  for candidate in python3 python; do
    if command -v "${candidate}" >/dev/null 2>&1; then
      python_bin="${candidate}"
      break
    fi
  done
  [[ -n "${python_bin}" ]] || fail "缺少 Python，无法验证 clone3 解析"
  clone3_number="$(LD_LIBRARY_PATH="${PREFIX}/lib64" "${python_bin}" -c '
import ctypes
library = ctypes.CDLL("libseccomp.so.2")
library.seccomp_syscall_resolve_name.argtypes = [ctypes.c_char_p]
library.seccomp_syscall_resolve_name.restype = ctypes.c_int
print(library.seccomp_syscall_resolve_name(b"clone3"))
')" || fail "新版 libseccomp 无法解析 clone3"
  [[ "${clone3_number}" =~ ^[0-9]+$ ]] || fail \
    "新版 libseccomp 返回非法 clone3 编号：${clone3_number}"

  sha256sum "${NEW_LIBRARY}" > "${STATE_DIR}/prepared-library.sha256"
  {
    echo "prepared_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "source_url=${SOURCE_URL}"
    echo "source_sha256=${SOURCE_SHA256}"
    echo "library=${NEW_LIBRARY}"
    echo "library_sha256=$(sha256sum "${NEW_LIBRARY}" | awk '{print $1}')"
    echo "loaded_version=${prepared_version}"
    echo "clone3_syscall_number=${clone3_number}"
    echo "docker_server=${docker_version}"
  } > "${STATE_DIR}/prepare-report.txt"
  cat "${STATE_DIR}/prepare-report.txt"
  echo "LIBSECCOMP_PREPARE_OK"
}

activate_library() {
  profile="${1:-}"
  [[ -n "${profile}" ]] || fail "--activate 必须提供审核后的 seccomp profile"
  require_host_baseline
  [[ -f "${profile}" ]] || fail "seccomp profile 不存在：${profile}"
  if [[ "${TEST_MODE}" != "1" ]]; then
    actual_profile_sha="$(sha256sum "${profile}" | awk '{print $1}')"
    [[ "${actual_profile_sha}" == "${PROFILE_SHA256}" ]] || fail \
      "seccomp profile 哈希未通过审核：${actual_profile_sha}"
  fi
  [[ -f "${NEW_LIBRARY}" ]] || fail "尚未完成 --prepare：${NEW_LIBRARY} 不存在"
  [[ -L "${SONAME_LINK}" ]] || fail "当前 ${SONAME_LINK} 不是软链接，拒绝修改"

  old_target="$(readlink "${SONAME_LINK}")"
  [[ "${old_target}" =~ ^libseccomp\.so\.2\.[A-Za-z0-9._-]+$ ]] || fail \
    "当前 libseccomp 链接目标不在审核范围：${old_target}"
  [[ -f "${LIBDIR}/${old_target}" ]] || fail \
    "当前 libseccomp 目标文件不存在：${LIBDIR}/${old_target}"
  old_version="$(detect_loaded_version)" || fail "无法记录切换前 libseccomp 版本"

  backup_dir="${STATE_DIR}/backups/$(date -u +%Y%m%dT%H%M%SZ)-$$"
  mkdir -p "${backup_dir}"
  printf '%s\n' "${old_target}" > "${backup_dir}/old-target.txt"
  cp -a "${LIBDIR}/${old_target}" "${backup_dir}/"
  sha256sum "${LIBDIR}/${old_target}" > "${backup_dir}/old-library.sha256"
  {
    echo "old_version=${old_version}"
    echo "old_target=${old_target}"
    echo "running_containers_before=$(docker ps -q | wc -l | tr -d ' ')"
    echo "docker_server=${docker_version}"
  } > "${backup_dir}/baseline.txt"

  install -m 0755 "${NEW_LIBRARY}" "${ACTIVE_LIBRARY}"
  new_library_sha="$(sha256sum "${ACTIVE_LIBRARY}" | awk '{print $1}')"
  prepared_library_sha="$(sha256sum "${NEW_LIBRARY}" | awk '{print $1}')"
  [[ "${new_library_sha}" == "${prepared_library_sha}" ]] || fail \
    "安装后的 libseccomp 哈希与隔离构建结果不一致"

  switched=0
  rollback_link() {
    if [[ "${switched}" == "1" ]]; then
      rollback_tmp="${LIBDIR}/.libseccomp.so.2.rollback.$$"
      ln -s "${old_target}" "${rollback_tmp}"
      mv -f "${rollback_tmp}" "${SONAME_LINK}"
      restored_version="$(detect_loaded_version 2>/dev/null || true)"
      printf 'LIBSECCOMP_ROLLBACK: target=%s loaded_version=%s\n' \
        "${old_target}" "${restored_version:-UNKNOWN}" >&2
    fi
  }
  trap rollback_link EXIT

  next_link="${LIBDIR}/.libseccomp.so.2.next.$$"
  ln -s "${ACTIVE_LIBRARY_BASENAME}" "${next_link}"
  mv -f "${next_link}" "${SONAME_LINK}"
  switched=1

  loaded_version="$(detect_loaded_version)" || fail "切换后无法加载 libseccomp"
  [[ "${loaded_version}" == "${VERSION}" ]] || fail \
    "切换后实际加载版本不是 ${VERSION}：${loaded_version}"

  if [[ "${TEST_MODE}" == "1" ]]; then
    runtime_image_id="TEST_IMAGE"
  else
    docker inspect "${RUNTIME_IMAGE}" >/dev/null 2>&1 \
      || fail "正式 Playwright 基础镜像不在本地缓存，禁止在切换窗口内拉取"
    runtime_image_id="$(docker inspect --format '{{.Id}}' "${RUNTIME_IMAGE}")"
  fi
  docker run --rm \
    --security-opt "seccomp=${profile}" \
    --entrypoint node \
    "${RUNTIME_IMAGE}" \
    -e 'const {Worker}=require("worker_threads");const w=new Worker("process.exit(0)",{eval:true});w.once("error",()=>process.exit(1));w.once("exit",code=>process.exit(code));' \
    || fail "新版 libseccomp 下的正式 Node 线程探针失败"

  switched=0
  trap - EXIT
  {
    echo "activated_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "old_version=${old_version}"
    echo "new_version=${loaded_version}"
    echo "old_target=${old_target}"
    echo "new_target=${ACTIVE_LIBRARY_BASENAME}"
    echo "new_library_sha256=${new_library_sha}"
    echo "runtime_image=${RUNTIME_IMAGE}"
    echo "runtime_image_id=${runtime_image_id}"
    echo "seccomp_profile=${profile}"
    echo "running_containers_after=$(docker ps -q | wc -l | tr -d ' ')"
    echo "backup_dir=${backup_dir}"
  } > "${STATE_DIR}/activation-report.txt"
  cat "${STATE_DIR}/activation-report.txt"
  echo "LIBSECCOMP_ACTIVATION_OK"
}

case "${1:-}" in
  --prepare)
    [[ "$#" == "1" ]] || { usage >&2; exit 2; }
    prepare_library
    ;;
  --activate)
    [[ "$#" == "2" ]] || { usage >&2; exit 2; }
    activate_library "$2"
    ;;
  --all)
    [[ "$#" == "2" ]] || { usage >&2; exit 2; }
    prepare_library
    activate_library "$2"
    ;;
  *)
    usage >&2
    exit 2
    ;;
esac
