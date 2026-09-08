#!/usr/bin/env bash

set -euo pipefail

platform_tools="${ANDROID_HOME:?ANDROID_HOME must point to the Android SDK}/platform-tools"
if [[ ! -x "$platform_tools/adb.real" ]]; then
  mv "$platform_tools/adb" "$platform_tools/adb.real"
fi
install -m 0755 "$GITHUB_WORKSPACE/android-ech/ci-bin/adb" "$platform_tools/adb"
