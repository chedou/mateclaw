#!/usr/bin/env bash

set -euo pipefail

fail() {
  printf 'LIBSECCOMP_VERSION_DETECTION_FAILED: %s\n' "$1" >&2
  exit 1
}

python_bin=""
for candidate in python3 python; do
  if command -v "${candidate}" >/dev/null 2>&1; then
    python_bin="${candidate}"
    break
  fi
done
[[ -n "${python_bin}" ]] || fail "python3/python 不存在，无法查询动态加载的 libseccomp"

version="$(${python_bin} -c '
import ctypes

class ScmpVersion(ctypes.Structure):
    _fields_ = [
        ("major", ctypes.c_uint),
        ("minor", ctypes.c_uint),
        ("micro", ctypes.c_uint),
    ]

library = ctypes.CDLL("libseccomp.so.2")
library.seccomp_version.restype = ctypes.POINTER(ScmpVersion)
value = library.seccomp_version()
if not value:
    raise RuntimeError("seccomp_version returned NULL")
loaded = value.contents
print("%d.%d.%d" % (loaded.major, loaded.minor, loaded.micro))
')" || fail "无法加载 libseccomp.so.2 或读取 seccomp_version"

[[ "${version}" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail \
  "动态库返回了非法版本：${version:-EMPTY}"
printf '%s\n' "${version}"
