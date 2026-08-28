#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BUILDER="${ROOT_DIR}/scripts/ci/build-test-env-candidate-image.sh"
SECCOMP_PROFILE="${ROOT_DIR}/deploy/seccomp/docker18-clone3.json"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf -- "$TMP_DIR"' EXIT

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

mkdir -p "$TMP_DIR/bin"
cat > "$TMP_DIR/bin/timeout" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
while [[ "${1:-}" == --* ]]; do shift; done
duration="$1"
shift
printf '%s %s\n' "$duration" "$*" >> "$FAKE_TIMEOUT_LOG"
exec "$@"
EOF
cat > "$TMP_DIR/bin/docker" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
cmd="${1:-}"
shift || true
{
  printf '%s' "$cmd"
  for arg in "$@"; do printf ' <%s>' "$arg"; done
  printf '\n'
} >> "$FAKE_DOCKER_LOG"
case "$cmd" in
  image|build|rm) exit 0 ;;
  inspect)
    format="${2:-}"
    target="${3:-}"
    case "$format:$target" in
      *'.State.Status'*:mateclaw-artifact-*) printf 'created\n' ;;
      *'.State.Status'*:mateclaw-runtime-assembly-*) printf 'exited\n' ;;
      *'.Config.WorkingDir'*:*) printf '/app\n' ;;
      *'.Config.User'*:*) printf '0:0\n' ;;
      *'json .Config.Entrypoint'*:*) printf '["java","-jar","app.jar"]\n' ;;
      *'json .Config.Cmd'*:*) printf '[]\n' ;;
      *'.Config.Env'*:*) printf '%s\n' \
        'PLAYWRIGHT_BROWSERS_PATH=/ms-playwright' \
        'TZ=Asia/Shanghai' 'LANG=C.UTF-8' 'LC_ALL=C.UTF-8' \
        "MATECLAW_RELEASE_COMMIT=${FAKE_RELEASE_COMMIT:-1234567890abcdef1234567890abcdef12345678}" \
        'JAVA_TOOL_OPTIONS=-Duser.timezone=Asia/Shanghai -Dsun.jnu.encoding=UTF-8' \
        'SPRING_PROFILES_ACTIVE=mysql' ;;
      *'.Config.ExposedPorts'*:*) printf '18088/tcp\n1455/tcp\n' ;;
      *'.RootFS.Layers'*:sha256:*) printf 'sha256:base-layer-1\nsha256:base-layer-2\n' ;;
      *'.RootFS.Layers'*:mateclaw:test-candidate) printf 'sha256:base-layer-1\nsha256:base-layer-2\nsha256:candidate-layer\n' ;;
      *'.Id'*:sha256:*)
        printf '%s\n' "${FAKE_RUNTIME_BASE_ACTUAL_ID:-$target}"
        ;;
      *'.Id'*:*'-builder') printf 'sha256:builder-artifact\n' ;;
      *'.Id'*:mateclaw-runtime-assembly-*) printf 'sha256:assembly-container\n' ;;
      *'.Id'*:*) printf 'sha256:candidate-image\n' ;;
      *) exit 90 ;;
    esac
    ;;
  create)
    name=''
    while [[ "$#" -gt 0 ]]; do
      if [[ "$1" == --name ]]; then name="$2"; break; fi
      shift
    done
    printf 'sha256:%s-id\n' "$name"
    ;;
  cp)
    source_path="$1"
    destination="$2"
    if [[ "$source_path" == mateclaw-artifact-*:/build/mateclaw-server/target/. ]]; then
      mkdir -p "$destination"
      jar_count="${FAKE_JAR_COUNT:-1}"
      i=1
      while [[ "$i" -le "$jar_count" ]]; do
        printf 'fake-jar-%s\n' "$i" > "${destination%/}/mateclaw-${i}.jar"
        i=$((i + 1))
      done
    else
      printf 'openjdk-21-jre-headless=21.test\n' > "$destination"
    fi
    ;;
  run) exit 0 ;;
  commit) exit 0 ;;
  *) exit 91 ;;
esac
EOF
chmod +x "$TMP_DIR/bin/timeout" "$TMP_DIR/bin/docker"

release_commit='1234567890abcdef1234567890abcdef12345678'
runtime_base_image_id='sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb'
legacy_log="$TMP_DIR/legacy-docker.log"
legacy_timeout_log="$TMP_DIR/legacy-timeout.log"
legacy_evidence="$TMP_DIR/legacy-evidence.txt"
legacy_manifest="$TMP_DIR/legacy-packages.txt"
: > "$legacy_log"
: > "$legacy_timeout_log"

PATH="$TMP_DIR/bin:$PATH" \
FAKE_DOCKER_LOG="$legacy_log" \
FAKE_TIMEOUT_LOG="$legacy_timeout_log" \
  "$BUILDER" LEGACY_CUSTOM_SECCOMP "$release_commit" "$ROOT_DIR" \
  'mateclaw:test-candidate' "$SECCOMP_PROFILE" "$runtime_base_image_id" \
  "$legacy_evidence" "$legacy_manifest" >/dev/null

grep -Fq 'build <--target> <builder>' "$legacy_log" \
  || fail "Docker 18 path must build only the builder target"
if grep -E '^build .*security-opt' "$legacy_log" >/dev/null; then
  fail "Docker 18 docker build must not receive --security-opt"
fi
grep -Fq 'run <--name> <mateclaw-runtime-assembly-' "$legacy_log" \
  || fail "legacy runtime assembly must use an exact container name"
grep -Fq '<--user> <0:0>' "$legacy_log" \
  || fail "legacy runtime assembly must run with an explicit root identity"
grep -Fq "<--security-opt> <seccomp=$SECCOMP_PROFILE>" "$legacy_log" \
  || fail "legacy runtime assembly must use the reviewed seccomp profile"
grep -Fq "<$runtime_base_image_id>" "$legacy_log" \
  || fail "legacy runtime assembly must start from the pinned immutable base ID"
if grep -Fq '<mcr.microsoft.com/playwright:v1.62.0-noble>' "$legacy_log"; then
  fail "legacy build must not re-inspect or run the mutable runtime tag"
fi
[[ "$(grep -o 'readonly' "$legacy_log" | wc -l | tr -d ' ')" -ge 3 ]] \
  || fail "installer, keyring, and JAR mounts must all be read-only"
for forbidden in --privileged --cap-add /var/run/docker.sock ':rw'; do
  if grep -Fq -- "$forbidden" "$legacy_log"; then
    fail "legacy assembly contains forbidden Docker authority: $forbidden"
  fi
done
grep -Fq "<--change> <ENV MATECLAW_RELEASE_COMMIT=$release_commit>" "$legacy_log" \
  || fail "docker commit must replace the immutable release identity"
for change in 'WORKDIR /app' 'ENTRYPOINT ["java","-jar","app.jar"]' 'CMD []' 'EXPOSE 18088 1455' 'USER 0:0'; do
  grep -Fq "<--change> <$change>" "$legacy_log" \
    || fail "docker commit is missing explicit runtime metadata: $change"
done
grep -Fq 'docker_build_security_mode=LEGACY_REVIEWED_SECCOMP_ASSEMBLY' "$legacy_evidence" \
  || fail "legacy build evidence must identify reviewed-seccomp assembly"
grep -Fq "runtime_base_image_id=$runtime_base_image_id" "$legacy_evidence" \
  || fail "legacy evidence must record the immutable runtime base ID"
grep -Fq 'artifact_image_id=sha256:builder-artifact' "$legacy_evidence" \
  || fail "legacy evidence must record the builder artifact image ID"
grep -Fq 'candidate_image_id=sha256:candidate-image' "$legacy_evidence" \
  || fail "legacy evidence must record the candidate image ID"
grep -Fq 'application_jar_sha256=' "$legacy_evidence" \
  || fail "legacy evidence must record the exact assembled JAR digest"
grep -Fq 'runtime_base_layer_count=2' "$legacy_evidence" \
  || fail "legacy evidence must record the pinned base layer count"
grep -Fq 'candidate_layer_count=3' "$legacy_evidence" \
  || fail "legacy candidate must contain exactly one layer beyond the pinned base"
grep -Fq 'candidate_probe=PASSED' "$legacy_evidence" \
  || fail "legacy evidence must record the bounded candidate probe"
grep -Fq 'runtime_installer_sha256=' "$legacy_evidence" \
  || fail "legacy evidence must record the shared installer digest"
grep -Fq 'ubuntu_keyring_sha256=655e378ede8af51ed5f2ffe3669b38f124593abc1aa769c2cc76ef5986a2f835' "$legacy_evidence" \
  || fail "legacy evidence must record the reviewed keyring digest"
grep -Eq '^30 docker rm -f mateclaw-artifact-[0-9]+$' "$legacy_timeout_log" \
  || fail "artifact container cleanup must be exact-name and bounded"
grep -Eq '^30 docker rm -f mateclaw-runtime-assembly-[0-9]+$' "$legacy_timeout_log" \
  || fail "assembly container cleanup must be exact-name and bounded"
grep -Eq '^30 docker rm -f mateclaw-candidate-probe-[0-9]+$' "$legacy_timeout_log" \
  || fail "candidate probe cleanup must be exact-name and bounded"
grep -Fq '60 docker run --name mateclaw-candidate-probe-' "$legacy_timeout_log" \
  || fail "candidate Java/Node/JAR probe must be bounded"
grep -Fq '3600 docker build --target builder' "$legacy_timeout_log" \
  || fail "builder-target docker build must be bounded"
grep -Fq 'BOOT-INF/classes/static/index.html' "$legacy_log" \
  || fail "candidate probe must validate the built frontend inside the JAR"
grep -Fq 'stat -c "%u:%g:%a" /app/app.jar' "$legacy_log" \
  || fail "candidate probe must validate app.jar root ownership and mode 0644"
grep -Fq 'Worker' "$legacy_log" \
  || fail "candidate probe must exercise Node worker creation"
if grep -Eq '^(stop|start|restart|kill) ' "$legacy_log"; then
  fail "candidate build must not restart or stop any running container"
fi

for jar_count in 0 2; do
  jar_log="$TMP_DIR/jar-${jar_count}.log"
  : > "$jar_log"
  if PATH="$TMP_DIR/bin:$PATH" \
    FAKE_DOCKER_LOG="$jar_log" FAKE_TIMEOUT_LOG="$TMP_DIR/jar-${jar_count}-timeout.log" \
    FAKE_JAR_COUNT="$jar_count" \
    "$BUILDER" LEGACY_CUSTOM_SECCOMP "$release_commit" "$ROOT_DIR" \
    "mateclaw:jar-${jar_count}" "$SECCOMP_PROFILE" "$runtime_base_image_id" \
    "$TMP_DIR/jar-${jar_count}-evidence.txt" "$TMP_DIR/jar-${jar_count}-packages.txt" \
    >"$TMP_DIR/jar-${jar_count}.out" 2>&1; then
    fail "legacy build must reject $jar_count JAR artifacts"
  fi
  grep -Fq '产物必须且只能有一个 JAR' "$TMP_DIR/jar-${jar_count}.out" \
    || fail "exact-one-JAR rejection must explain the artifact count"
  if grep -Fq 'commit ' "$jar_log"; then
    fail "invalid JAR counts must fail before docker commit"
  fi
done

for invalid_base_id in NOT_REQUIRED sha256:missing; do
  invalid_log="$TMP_DIR/invalid-base-${invalid_base_id//:/-}.log"
  : > "$invalid_log"
  if PATH="$TMP_DIR/bin:$PATH" \
    FAKE_DOCKER_LOG="$invalid_log" FAKE_TIMEOUT_LOG="$TMP_DIR/invalid-base-timeout.log" \
    "$BUILDER" LEGACY_CUSTOM_SECCOMP "$release_commit" "$ROOT_DIR" \
    'mateclaw:invalid-base' "$SECCOMP_PROFILE" "$invalid_base_id" \
    "$TMP_DIR/invalid-base-evidence.txt" "$TMP_DIR/invalid-base-packages.txt" \
    >"$TMP_DIR/invalid-base.out" 2>&1; then
    fail "legacy build must reject invalid recorded runtime base ID: $invalid_base_id"
  fi
  if grep -Eq '^(build|run|commit) ' "$invalid_log"; then
    fail "invalid recorded runtime base ID must fail before build or assembly"
  fi
done

mismatch_log="$TMP_DIR/mismatched-base.log"
: > "$mismatch_log"
if PATH="$TMP_DIR/bin:$PATH" \
  FAKE_DOCKER_LOG="$mismatch_log" FAKE_TIMEOUT_LOG="$TMP_DIR/mismatched-base-timeout.log" \
  FAKE_RUNTIME_BASE_ACTUAL_ID='sha256:different-runtime-base' \
  "$BUILDER" LEGACY_CUSTOM_SECCOMP "$release_commit" "$ROOT_DIR" \
  'mateclaw:mismatched-base' "$SECCOMP_PROFILE" "$runtime_base_image_id" \
  "$TMP_DIR/mismatched-base-evidence.txt" "$TMP_DIR/mismatched-base-packages.txt" \
  >"$TMP_DIR/mismatched-base.out" 2>&1; then
  fail "legacy build must reject a runtime base ID whose inspect result changed"
fi
if grep -Eq '^(build|run|commit) ' "$mismatch_log"; then
  fail "mismatched runtime base ID must fail before build or assembly"
fi

native_log="$TMP_DIR/native-docker.log"
: > "$native_log"
PATH="$TMP_DIR/bin:$PATH" \
FAKE_DOCKER_LOG="$native_log" FAKE_TIMEOUT_LOG="$TMP_DIR/native-timeout.log" \
FAKE_BASE_INSPECT_FAIL=1 \
  "$BUILDER" NATIVE_CLONE3_SECCOMP "$release_commit" "$ROOT_DIR" \
  'mateclaw:native-candidate' "$SECCOMP_PROFILE" NOT_REQUIRED \
  "$TMP_DIR/native-evidence.txt" "$TMP_DIR/native-packages.txt" >/dev/null
grep -Fq 'build <--build-arg>' "$native_log" \
  || fail "modern path must retain the full Dockerfile build"
if grep -Fq '<--target> <builder>' "$native_log"; then
  fail "modern path must not stop at the builder target"
fi
grep -Fq 'docker_build_security_mode=NATIVE_FULL_DOCKERFILE' "$TMP_DIR/native-evidence.txt" \
  || fail "modern evidence must identify the full Dockerfile build"
grep -Fq 'runtime_base_image_id=NOT_REQUIRED' "$TMP_DIR/native-evidence.txt" \
  || fail "modern cold-cache builds must not depend on a pre-existing runtime base tag"
grep -Fq '3600 docker build --build-arg' "$TMP_DIR/native-timeout.log" \
  || fail "full Dockerfile build must be bounded"

if grep -Eq -- 'docker build[^\n]*(--security-opt|security-opt)' "$BUILDER" "${ROOT_DIR}/Jenkinsfile.test-env"; then
  fail "pipeline must never pass --security-opt to docker build"
fi

printf 'PASS: Docker 18 builds one JAR and assembles a constrained pinned-base candidate\n'
