#!/usr/bin/env bash

set -euo pipefail

usage() {
  printf '%s\n' 'usage: build-test-env-candidate-image.sh MODE RELEASE_COMMIT SOURCE_DIR CANDIDATE_IMAGE SECCOMP_PROFILE RUNTIME_BASE_IMAGE_ID EVIDENCE_FILE PACKAGE_MANIFEST_FILE' >&2
  exit 2
}

fail() {
  printf 'IMAGE_BUILD_FAILED: %s\n' "$*" >&2
  exit 1
}

[[ "$#" -eq 8 ]] || usage

build_mode="$1"
release_commit="$2"
source_dir="$3"
candidate_image="$4"
legacy_seccomp_profile="$5"
requested_runtime_base_image_id="$6"
evidence_file="$7"
package_manifest_file="$8"
dockerfile="${source_dir}/mateclaw-server/Dockerfile"
installer="${source_dir}/mateclaw-server/docker/install-runtime-dependencies.sh"
keyring_b64="${source_dir}/mateclaw-server/docker/ubuntu-archive-keyring.gpg.b64"
maven_flags="${MATECLAW_DOCKER_MAVEN_FLAGS:--Paliyun-first -Dmaven.wagon.http.connectTimeout=15000 -Dmaven.wagon.http.readTimeout=60000}"
docker_command_timeout="${MATECLAW_DOCKER_COMMAND_TIMEOUT_SECONDS:-30}"
assembly_timeout="${MATECLAW_DOCKER_ASSEMBLY_TIMEOUT_SECONDS:-1800}"
build_timeout="${MATECLAW_DOCKER_BUILD_TIMEOUT_SECONDS:-3600}"
candidate_probe_timeout="${MATECLAW_DOCKER_CANDIDATE_PROBE_TIMEOUT_SECONDS:-60}"
reviewed_seccomp_sha256='959c7b5f83f4fa6f0bec17dab25434fafa399b11e84661a30c725bece3d5473d'
reviewed_keyring_sha256='655e378ede8af51ed5f2ffe3669b38f124593abc1aa769c2cc76ef5986a2f835'
approved_frontend_base_image='itharbor.sangfor.com/base-image/node:22.18.0-alpine@sha256:ac0d137a585eaaaf648c4f011a6f52cbb71952f9471e76c343fdb2d1cd711b62'
approved_backend_base_image='itharbor.sangfor.com/ai-uat/mateclaw-maven:3.9.6-eclipse-temurin-21-alpine@sha256:1750ed0e15881d6b9e11d8657026a492cd29e85e009481bbb1d0d7a0056e42b9'
approved_runtime_base_image='itharbor.sangfor.com/ai-uat/mateclaw-playwright:v1.62.0-noble@sha256:0e5163ed3364179e474b849dbecfaa46a06e21212abe2c67873f706dc609b88e'

dockerfile_arg_default() {
  local requested_name="$1"
  awk -v requested_name="$requested_name" '
    toupper($1) == "ARG" {
      split($2, parts, "=")
      if (parts[1] == requested_name) {
        sub(/^[^=]*=/, "", $2)
        print $2
        exit
      }
    }
  ' "$dockerfile"
}

require_internal_digest_ref() {
  local variable_name="$1"
  local image_ref="$2"
  [[ -n "$image_ref" ]] \
    || fail "$variable_name 未配置；请先将 Playwright v1.62.0-noble 精确镜像同步到深信服 Harbor，再传入完整 tag@sha256 引用"
  [[ "$image_ref" =~ ^itharbor\.sangfor\.com/[A-Za-z0-9._/-]+:[A-Za-z0-9._-]+@sha256:[0-9a-f]{64}$ ]] \
    || fail "$variable_name 必须是 itharbor.sangfor.com 下带 tag 且固定 sha256 digest 的镜像引用"
}

[[ "$release_commit" =~ ^[0-9a-fA-F]{40}$ ]] || fail "RELEASE_COMMIT 必须是完整 40 位 Git SHA"
[[ -d "$source_dir" && -f "$dockerfile" ]] || fail "源码目录或 Dockerfile 不存在"
[[ -x "$installer" ]] || fail "运行依赖安装器不存在或不可执行：$installer"
[[ -f "$keyring_b64" ]] || fail "经审核的 Ubuntu keyring 不存在"
[[ -n "$candidate_image" ]] || fail "候选镜像名不能为空"
frontend_base_image_ref="${MATECLAW_FRONTEND_BASE_IMAGE:-$(dockerfile_arg_default MATECLAW_FRONTEND_BASE_IMAGE)}"
backend_base_image_ref="${MATECLAW_BACKEND_BASE_IMAGE:-$(dockerfile_arg_default MATECLAW_BACKEND_BASE_IMAGE)}"
runtime_base_image_ref="${MATECLAW_RUNTIME_BASE_IMAGE:-$(dockerfile_arg_default MATECLAW_RUNTIME_BASE_IMAGE)}"
require_internal_digest_ref MATECLAW_FRONTEND_BASE_IMAGE "$frontend_base_image_ref"
require_internal_digest_ref MATECLAW_BACKEND_BASE_IMAGE "$backend_base_image_ref"
require_internal_digest_ref MATECLAW_RUNTIME_BASE_IMAGE "$runtime_base_image_ref"
[[ "$frontend_base_image_ref" == "$approved_frontend_base_image" ]] \
  || fail "MATECLAW_FRONTEND_BASE_IMAGE 必须等于代码中已审核的 Node 不可变引用：$approved_frontend_base_image"
[[ "$backend_base_image_ref" == "$approved_backend_base_image" ]] \
  || fail "MATECLAW_BACKEND_BASE_IMAGE 必须等于代码中已审核的 Maven 不可变引用：$approved_backend_base_image"
[[ "$runtime_base_image_ref" == "$approved_runtime_base_image" ]] \
  || fail "MATECLAW_RUNTIME_BASE_IMAGE 必须等于代码中已审核的 Playwright 不可变引用：$approved_runtime_base_image"
[[ "$runtime_base_image_ref" == *':v1.62.0-noble@sha256:'* ]] \
  || fail "MATECLAW_RUNTIME_BASE_IMAGE 必须是 Playwright v1.62.0-noble 的内网 digest 镜像"
grep -Fxq 'FROM ${MATECLAW_FRONTEND_BASE_IMAGE} AS frontend-builder' "$dockerfile" \
  || fail "Dockerfile 前端构建阶段未使用 MATECLAW_FRONTEND_BASE_IMAGE"
grep -Fxq 'FROM ${MATECLAW_BACKEND_BASE_IMAGE} AS builder' "$dockerfile" \
  || fail "Dockerfile 后端构建阶段未使用 MATECLAW_BACKEND_BASE_IMAGE"
grep -Fxq 'FROM ${MATECLAW_RUNTIME_BASE_IMAGE}' "$dockerfile" \
  || fail "Dockerfile 运行阶段未使用 MATECLAW_RUNTIME_BASE_IMAGE"

command -v docker >/dev/null || fail "docker 命令不存在"
command -v timeout >/dev/null || fail "宿主缺少 timeout，无法有界执行 Docker 组装与清理"

installer_sha256="$(sha256sum "$installer" | awk '{print $1}')"
keyring_sha256="$(base64 --decode < "$keyring_b64" | sha256sum | awk '{print $1}')"
[[ "$keyring_sha256" == "$reviewed_keyring_sha256" ]] \
  || fail "Ubuntu keyring 解码后哈希未通过审核：$keyring_sha256"

artifact_container="mateclaw-artifact-$$"
assembly_container="mateclaw-runtime-assembly-$$"
manifest_container="mateclaw-manifest-$$"
candidate_probe_container="mateclaw-candidate-probe-$$"
artifact_image="${candidate_image}-builder"
temporary_dir="$(mktemp -d)"
artifact_dir="${temporary_dir}/target"
assembly_inputs="${temporary_dir}/assembly-inputs"
artifact_container_created=false
assembly_container_created=false
manifest_container_created=false
candidate_probe_container_created=false
artifact_image_created=false

bounded_docker() {
  local seconds="$1"
  shift
  timeout --signal=TERM --kill-after=5s "$seconds" docker "$@"
}

cleanup() {
  local cleanup_rc=0
  if [[ "$artifact_container_created" == true ]]; then
    bounded_docker "$docker_command_timeout" rm -f "$artifact_container" >/dev/null 2>&1 || cleanup_rc=$?
  fi
  if [[ "$assembly_container_created" == true ]]; then
    bounded_docker "$docker_command_timeout" rm -f "$assembly_container" >/dev/null 2>&1 || cleanup_rc=$?
  fi
  if [[ "$manifest_container_created" == true ]]; then
    bounded_docker "$docker_command_timeout" rm -f "$manifest_container" >/dev/null 2>&1 || cleanup_rc=$?
  fi
  if [[ "$candidate_probe_container_created" == true ]]; then
    bounded_docker "$docker_command_timeout" rm -f "$candidate_probe_container" >/dev/null 2>&1 || cleanup_rc=$?
  fi
  if [[ "$artifact_image_created" == true ]]; then
    bounded_docker "$docker_command_timeout" image rm "$artifact_image" >/dev/null 2>&1 || true
  fi
  rm -rf -- "$temporary_dir"
  return "$cleanup_rc"
}
trap 'cleanup || true' EXIT

bounded_docker "$docker_command_timeout" image rm "$candidate_image" >/dev/null 2>&1 || true
runtime_base_image_id='NOT_REQUIRED'
artifact_image_id='NOT_REQUIRED'
artifact_container_id='NOT_REQUIRED'
assembly_container_id='NOT_REQUIRED'
legacy_seccomp_sha256='NOT_REQUIRED'
jar_path=''

case "$build_mode" in
  NATIVE_CLONE3_SECCOMP)
    bounded_docker "$build_timeout" build \
      --build-arg "MATECLAW_FRONTEND_BASE_IMAGE=$frontend_base_image_ref" \
      --build-arg "MATECLAW_BACKEND_BASE_IMAGE=$backend_base_image_ref" \
      --build-arg "MATECLAW_RUNTIME_BASE_IMAGE=$runtime_base_image_ref" \
      --build-arg "MAVEN_FLAGS=$maven_flags" \
      --build-arg "MATECLAW_RELEASE_COMMIT=$release_commit" \
      -t "$candidate_image" \
      -f "$dockerfile" \
      "$source_dir"
    manifest_container_created=true
    manifest_container_id="$(bounded_docker "$docker_command_timeout" create --name "$manifest_container" "$candidate_image")" \
      || fail "无法创建候选镜像清单容器"
    bounded_docker "$docker_command_timeout" cp \
      "$manifest_container:/app/runtime-package-manifest.txt" "$package_manifest_file" \
      || fail "无法从候选镜像取出运行包清单"
    jar_path="${temporary_dir}/native-app.jar"
    bounded_docker "$docker_command_timeout" cp \
      "$manifest_container:/app/app.jar" "$jar_path" \
      || fail "无法从候选镜像取出待校验 JAR"
    docker_build_security_mode='NATIVE_FULL_DOCKERFILE'
    ;;

  LEGACY_CUSTOM_SECCOMP)
    [[ -f "$legacy_seccomp_profile" ]] || fail "Docker 18 审核 seccomp profile 不存在"
    legacy_seccomp_sha256="$(sha256sum "$legacy_seccomp_profile" | awk '{print $1}')"
    [[ "$legacy_seccomp_sha256" == "$reviewed_seccomp_sha256" ]] \
      || fail "Docker 18 seccomp profile 哈希未通过审核：$legacy_seccomp_sha256"
    [[ "$requested_runtime_base_image_id" =~ ^sha256:[0-9a-f]{64}$ ]] \
      || fail "Docker 18 预检传入的基础镜像 ID 格式非法：${requested_runtime_base_image_id:-EMPTY}"
    runtime_base_image_id="$(
      bounded_docker "$docker_command_timeout" inspect --format '{{.Id}}' \
        "$requested_runtime_base_image_id"
    )" || fail "无法确认 Docker 18 预检传入的不可变基础镜像 ID"
    [[ "$runtime_base_image_id" == "$requested_runtime_base_image_id" ]] \
      || fail "Docker 18 基础镜像 ID 与预检证据不一致"

    bounded_docker "$build_timeout" build \
      --target builder \
      --build-arg "MATECLAW_FRONTEND_BASE_IMAGE=$frontend_base_image_ref" \
      --build-arg "MATECLAW_BACKEND_BASE_IMAGE=$backend_base_image_ref" \
      --build-arg "MATECLAW_RUNTIME_BASE_IMAGE=$runtime_base_image_ref" \
      --build-arg "MAVEN_FLAGS=$maven_flags" \
      -t "$artifact_image" \
      -f "$dockerfile" \
      "$source_dir"
    artifact_image_created=true
    artifact_image_id="$(bounded_docker "$docker_command_timeout" inspect --format '{{.Id}}' "$artifact_image")" \
      || fail "无法获取 builder 产物镜像 ID"
    [[ -n "$artifact_image_id" ]] || fail "builder 产物镜像 ID 为空"

    artifact_container_created=true
    artifact_container_id="$(bounded_docker "$docker_command_timeout" create --name "$artifact_container" "$artifact_image_id")" \
      || fail "无法创建仅用于取出 JAR 的停止容器"
    artifact_state="$(bounded_docker "$docker_command_timeout" inspect --format '{{.State.Status}}' "$artifact_container")" \
      || fail "无法确认 JAR 产物容器状态"
    [[ "$artifact_state" != running ]] || fail "JAR 产物容器被意外启动"

    mkdir -p "$artifact_dir"
    bounded_docker "$docker_command_timeout" cp \
      "$artifact_container:/build/mateclaw-server/target/." "$artifact_dir/" \
      || fail "无法从停止的 builder 容器取出 JAR"
    jar_count="$(find "$artifact_dir" -maxdepth 1 -type f -name '*.jar' | wc -l | tr -d '[:space:]')"
    [[ "$jar_count" == 1 ]] \
      || fail "builder 产物必须且只能有一个 JAR，当前为 $jar_count 个"
    jar_path="$(find "$artifact_dir" -maxdepth 1 -type f -name '*.jar' -print | head -n 1)"

    # Docker 18 on the test host does not reliably expose multiple individual
    # read-only file mounts with their source permissions intact. Stage the
    # three reviewed inputs with explicit modes and mount one read-only
    # directory instead; no daemon, host library or running container changes.
    install -d -m 0755 "$assembly_inputs"
    install -m 0755 "$installer" "$assembly_inputs/mateclaw-install-runtime-dependencies"
    install -m 0644 "$keyring_b64" "$assembly_inputs/ubuntu-archive-keyring.gpg.b64"
    install -m 0644 "$jar_path" "$assembly_inputs/app.jar"
    cmp -s "$installer" "$assembly_inputs/mateclaw-install-runtime-dependencies" \
      || fail "Docker 18 组装目录中的运行依赖安装器不一致"
    cmp -s "$keyring_b64" "$assembly_inputs/ubuntu-archive-keyring.gpg.b64" \
      || fail "Docker 18 组装目录中的 Ubuntu keyring 不一致"
    cmp -s "$jar_path" "$assembly_inputs/app.jar" \
      || fail "Docker 18 组装目录中的 JAR 不一致"

    assembly_container_created=true
    bounded_docker "$assembly_timeout" run \
      --name "$assembly_container" \
      --user 0:0 \
      --security-opt "seccomp=$legacy_seccomp_profile" \
      --mount "type=bind,src=$assembly_inputs,dst=/mnt,readonly" \
      --entrypoint /bin/bash \
      "$runtime_base_image_id" -ceu '
        install -o root -g root -m 0755 /mnt/mateclaw-install-runtime-dependencies /usr/local/sbin/mateclaw-install-runtime-dependencies
        /usr/local/sbin/mateclaw-install-runtime-dependencies /mnt/ubuntu-archive-keyring.gpg.b64
        install -d -o root -g root -m 0755 /app
        install -o root -g root -m 0644 /mnt/app.jar /app/app.jar
      ' || fail "Docker 18 运行时组装容器执行失败或超时"
    assembly_container_id="$(bounded_docker "$docker_command_timeout" inspect --format '{{.Id}}' "$assembly_container")" \
      || fail "无法获取运行时组装容器 ID"
    assembly_state="$(bounded_docker "$docker_command_timeout" inspect --format '{{.State.Status}}' "$assembly_container")" \
      || fail "无法确认运行时组装容器状态"
    [[ "$assembly_state" == exited ]] || fail "运行时组装容器未安全退出：$assembly_state"
    bounded_docker "$docker_command_timeout" cp \
      "$assembly_container:/app/runtime-package-manifest.txt" "$package_manifest_file" \
      || fail "无法取出 Docker 18 运行包清单"

    bounded_docker "$docker_command_timeout" commit \
      --change 'WORKDIR /app' \
      --change 'ENV PLAYWRIGHT_BROWSERS_PATH=/ms-playwright TZ=Asia/Shanghai LANG=C.UTF-8 LC_ALL=C.UTF-8' \
      --change "ENV MATECLAW_RELEASE_COMMIT=$release_commit" \
      --change 'ENV JAVA_TOOL_OPTIONS="-Duser.timezone=Asia/Shanghai -Dsun.jnu.encoding=UTF-8"' \
      --change 'ENV SPRING_PROFILES_ACTIVE=mysql' \
      --change 'ENTRYPOINT ["java","-jar","app.jar"]' \
      --change 'CMD []' \
      --change 'EXPOSE 18088 1455' \
      --change 'USER 0:0' \
      "$assembly_container" "$candidate_image" >/dev/null \
      || fail "无法将 Docker 18 组装容器提交为候选镜像"
    docker_build_security_mode='LEGACY_REVIEWED_SECCOMP_ASSEMBLY'
    ;;

  *) fail "未知 Docker 构建兼容模式：$build_mode" ;;
esac

[[ -s "$package_manifest_file" ]] || fail "运行包清单为空"
package_manifest_sha256="$(sha256sum "$package_manifest_file" | awk '{print $1}')"
[[ -s "$jar_path" ]] || fail "待校验的候选 JAR 为空"
jar_sha256="$(sha256sum "$jar_path" | awk '{print $1}')"
candidate_image_id="$(bounded_docker "$docker_command_timeout" inspect --format '{{.Id}}' "$candidate_image")" \
  || fail "无法获取候选镜像 ID"
[[ -n "$candidate_image_id" ]] || fail "候选镜像 ID 为空"

candidate_workdir="$(bounded_docker "$docker_command_timeout" inspect --format '{{.Config.WorkingDir}}' "$candidate_image")"
candidate_user="$(bounded_docker "$docker_command_timeout" inspect --format '{{.Config.User}}' "$candidate_image")"
candidate_entrypoint="$(bounded_docker "$docker_command_timeout" inspect --format '{{json .Config.Entrypoint}}' "$candidate_image")"
candidate_cmd="$(bounded_docker "$docker_command_timeout" inspect --format '{{json .Config.Cmd}}' "$candidate_image")"
candidate_env="$(bounded_docker "$docker_command_timeout" inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$candidate_image")"
candidate_ports="$(
  bounded_docker "$docker_command_timeout" inspect \
    --format '{{range $port, $_ := .Config.ExposedPorts}}{{println $port}}{{end}}' "$candidate_image" \
    | LC_ALL=C sort
)"
[[ "$candidate_workdir" == /app ]] || fail "候选镜像 WorkingDir 不是 /app"
[[ "$candidate_user" == 0:0 ]] || fail "候选镜像 User 不是 0:0"
[[ "$candidate_entrypoint" == '["java","-jar","app.jar"]' ]] \
  || fail "候选镜像 Entrypoint 不是审核值"
[[ "$candidate_cmd" == '[]' ]] || fail "候选镜像 Cmd 不是空数组"
[[ "$candidate_ports" == $'1455/tcp\n18088/tcp' ]] \
  || fail "候选镜像 ExposedPorts 不等于 1455/tcp + 18088/tcp"
for expected_env in \
  'PLAYWRIGHT_BROWSERS_PATH=/ms-playwright' \
  'TZ=Asia/Shanghai' \
  'LANG=C.UTF-8' \
  'LC_ALL=C.UTF-8' \
  "MATECLAW_RELEASE_COMMIT=$release_commit" \
  'JAVA_TOOL_OPTIONS=-Duser.timezone=Asia/Shanghai -Dsun.jnu.encoding=UTF-8' \
  'SPRING_PROFILES_ACTIVE=mysql'; do
  grep -Fxq "$expected_env" <<<"$candidate_env" \
    || fail "候选镜像缺少审核环境变量：$expected_env"
done

base_layer_count='NOT_CHECKED'
candidate_layer_count='NOT_CHECKED'
candidate_probe='NOT_REQUIRED'
if [[ "$build_mode" == LEGACY_CUSTOM_SECCOMP ]]; then
  base_layers_file="${temporary_dir}/base-layers.txt"
  candidate_layers_file="${temporary_dir}/candidate-layers.txt"
  bounded_docker "$docker_command_timeout" inspect \
    --format '{{range .RootFS.Layers}}{{println .}}{{end}}' "$runtime_base_image_id" \
    > "$base_layers_file"
  bounded_docker "$docker_command_timeout" inspect \
    --format '{{range .RootFS.Layers}}{{println .}}{{end}}' "$candidate_image" \
    > "$candidate_layers_file"
  base_layer_count="$(grep -c . "$base_layers_file")"
  candidate_layer_count="$(grep -c . "$candidate_layers_file")"
  [[ "$base_layer_count" -gt 0 && "$candidate_layer_count" -eq $((base_layer_count + 1)) ]] \
    || fail "Docker 18 候选镜像必须只比 pinned base 多一层"
  head -n "$base_layer_count" "$candidate_layers_file" | cmp -s - "$base_layers_file" \
    || fail "Docker 18 候选镜像 RootFS 不以 pinned base 完整层序列为前缀"

  candidate_probe_container_created=true
  bounded_docker "$candidate_probe_timeout" run \
    --name "$candidate_probe_container" \
    --user 0:0 \
    --security-opt "seccomp=$legacy_seccomp_profile" \
    -e "EXPECTED_JAR_SHA256=$jar_sha256" \
    --entrypoint /bin/bash \
    "$candidate_image_id" -ceu '
      java -version >/dev/null 2>&1
      node -e '\''const {Worker}=require("worker_threads");const w=new Worker("process.exit(0)",{eval:true});w.once("error",()=>process.exit(1));w.once("exit",code=>process.exit(code));'\''
      test "$(stat -c "%u:%g:%a" /app/app.jar)" = "0:0:644"
      printf "%s  /app/app.jar\n" "$EXPECTED_JAR_SHA256" | sha256sum --check --strict -
      python -c '\''import sys,zipfile; archive=zipfile.ZipFile(sys.argv[1]); sys.exit(0 if "BOOT-INF/classes/static/index.html" in archive.namelist() else 1)'\'' /app/app.jar
    ' || fail "Docker 18 候选镜像在审核 seccomp 下的 Java/Node/JAR 探针失败或超时"
  candidate_probe='PASSED'
fi

{
  echo "docker_build_security_mode=$docker_build_security_mode"
  echo "frontend_base_image_ref=$frontend_base_image_ref"
  echo "backend_base_image_ref=$backend_base_image_ref"
  echo "runtime_base_image_ref=$runtime_base_image_ref"
  echo "runtime_base_image_id=$runtime_base_image_id"
  echo "runtime_installer_sha256=$installer_sha256"
  echo "ubuntu_keyring_sha256=$keyring_sha256"
  echo "legacy_seccomp_profile_sha256=$legacy_seccomp_sha256"
  echo "artifact_image_id=$artifact_image_id"
  echo "artifact_container_id=$artifact_container_id"
  echo "assembly_container_id=$assembly_container_id"
  echo "runtime_package_manifest_sha256=$package_manifest_sha256"
  echo "application_jar_sha256=$jar_sha256"
  echo "candidate_image_id=$candidate_image_id"
  echo "runtime_base_layer_count=$base_layer_count"
  echo "candidate_layer_count=$candidate_layer_count"
  echo "candidate_probe=$candidate_probe"
  echo "release_commit=$release_commit"
  echo 'current_container_restarted=NO'
} > "$evidence_file"

cleanup || fail "Docker 临时容器的精确名称有界清理失败"
trap - EXIT
cat "$evidence_file"
