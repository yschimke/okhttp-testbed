#!/usr/bin/env bash

# Builds Conscrypt's OpenJDK artifact from the pinned google3-export commit, against the
# pinned BoringSSL commit, and stages the result under conscrypt/build/dist.
#
# This is deliberately not part of the Gradle build. It takes around fifteen minutes, needs
# a C++ toolchain and a cross compiler, and its output changes only when `pinned.properties`
# changes — so it runs in its own workflow and its output is cached as a release. Suites get
# the jar from `fetch-conscrypt.sh`, which downloads that release. See README.md.
#
# Requires: git, cmake, ninja, clang, a JDK, and g++-aarch64-linux-gnu. On Ubuntu:
#   sudo apt-get install -y cmake ninja-build clang g++-aarch64-linux-gnu binutils-aarch64-linux-gnu

set -euo pipefail

conscrypt_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
build_dir="$conscrypt_dir/build"
dist_dir="$build_dir/dist"

# shellcheck disable=SC1091
source "$conscrypt_dir/pinned.properties"

: "${conscryptRef:?pinned.properties must set conscryptRef}"
: "${conscryptVersion:?pinned.properties must set conscryptVersion}"
: "${boringsslRef:?pinned.properties must set boringsslRef}"

# One checkout per sha, so a bumped pin doesn't build on top of the previous tree and a
# rerun with an unchanged pin reuses what's already there.
boringssl_dir="$build_dir/boringssl-$boringsslRef"
conscrypt_src_dir="$build_dir/conscrypt-$conscryptRef"

# Fetches exactly one commit. `git clone --depth 1` can only take a branch tip, and both of
# these are pinned to a sha that may be behind it.
checkout() {
  local url="$1" ref="$2" target="$3"

  if [ -e "$target/.git" ]; then
    echo "Reusing $target"
    return
  fi

  rm -rf "$target"
  mkdir -p "$target"
  git -C "$target" init --quiet
  git -C "$target" remote add origin "$url"
  git -C "$target" fetch --quiet --depth 1 origin "$ref"
  git -C "$target" checkout --quiet FETCH_HEAD
}

echo "==> BoringSSL $boringsslRef"
checkout https://github.com/google/boringssl.git "$boringsslRef" "$boringssl_dir"

# Both architectures, because Conscrypt's `jar` task builds a native library for each and
# fails the build if either can't link. Only x86_64 is used by the suites today.
if [ ! -f "$boringssl_dir/build64/libssl.a" ]; then
  echo "==> BoringSSL x86_64"
  cmake -S "$boringssl_dir" -B "$boringssl_dir/build64" -GNinja \
    -DCMAKE_POSITION_INDEPENDENT_CODE=TRUE \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_C_COMPILER=clang \
    -DCMAKE_CXX_COMPILER=clang++
  ninja -C "$boringssl_dir/build64" crypto ssl
fi

if [ ! -f "$boringssl_dir/build.arm/libssl.a" ]; then
  echo "==> BoringSSL aarch64"
  cmake -S "$boringssl_dir" -B "$boringssl_dir/build.arm" -GNinja \
    -DCMAKE_SYSTEM_NAME=Linux \
    -DCMAKE_SYSTEM_PROCESSOR=aarch64 \
    -DCMAKE_C_COMPILER=aarch64-linux-gnu-gcc \
    -DCMAKE_CXX_COMPILER=aarch64-linux-gnu-g++ \
    -DCMAKE_POSITION_INDEPENDENT_CODE=TRUE \
    -DCMAKE_BUILD_TYPE=Release
  ninja -C "$boringssl_dir/build.arm" crypto ssl
fi

echo "==> Conscrypt $conscryptRef"
checkout https://github.com/google/conscrypt.git "$conscryptRef" "$conscrypt_src_dir"

# `jar` covers every native jar the host can build, which on Linux is x86_64 and aarch64.
# The Android modules are only included when an SDK is visible, and this build has no use
# for them, so ANDROID_HOME is cleared to keep them out of the task graph.
(
  cd "$conscrypt_src_dir"
  unset ANDROID_HOME ANDROID_SDK_ROOT
  BORINGSSL_HOME="$boringssl_dir" CC=clang CXX=clang++ \
    ./gradlew --no-daemon :conscrypt-openjdk:jar
)

rm -rf "$dist_dir"
mkdir -p "$dist_dir"
cp "$conscrypt_src_dir/openjdk/build/libs/"conscrypt-openjdk-*.jar "$dist_dir/"

# What the jars can't say: which commits they came from. `fetch-conscrypt.sh` writes the
# same file after a download, so a suite can report its provenance either way.
cat > "$dist_dir/build-info.json" <<JSON
{
  "conscryptRef": "$conscryptRef",
  "conscryptVersion": "$conscryptVersion",
  "boringsslRef": "$boringsslRef",
  "source": "built"
}
JSON

(cd "$dist_dir" && sha256sum ./*.jar > SHA256SUMS)

echo "==> Staged in $dist_dir"
ls -l "$dist_dir"
