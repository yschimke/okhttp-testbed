#!/usr/bin/env bash

# Puts the pinned Conscrypt build under conscrypt/build/dist, downloading it from this
# repository's releases rather than building it.
#
# The release tag is derived from `pinned.properties`, so this and `build-conscrypt.sh`
# always agree on what "the pinned build" means, and a bumped pin misses the cache rather
# than silently returning the old jar. `--build-if-missing` falls back to building; without
# it a missing release is an error, which is what a test workflow wants — a suite that
# quietly builds Conscrypt on every checkin is the thing this arrangement exists to avoid.
#
# Nothing here needs a token: the releases of a public repository are public.

set -euo pipefail

build_if_missing=false
if [ "${1:-}" = "--build-if-missing" ]; then
  build_if_missing=true
elif [ $# -gt 0 ]; then
  echo "usage: $0 [--build-if-missing]" >&2
  exit 2
fi

conscrypt_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
dist_dir="$conscrypt_dir/build/dist"

# shellcheck disable=SC1091
source "$conscrypt_dir/pinned.properties"

repository="${GITHUB_REPOSITORY:-yschimke/okhttp-testbed}"
tag="$("$conscrypt_dir/release-tag.sh")"
base_url="https://github.com/$repository/releases/download/$tag"

if [ -f "$dist_dir/build-info.json" ] && grep -q "\"$conscryptRef\"" "$dist_dir/build-info.json"; then
  echo "Already have $tag in $dist_dir"
  exit 0
fi

echo "==> Fetching $tag from $repository"
staging="$(mktemp -d)"
trap 'rm -rf "$staging"' EXIT

if ! curl -fsSL --retry 3 --retry-delay 2 "$base_url/SHA256SUMS" -o "$staging/SHA256SUMS"; then
  echo "No release $tag on $repository." >&2
  if [ "$build_if_missing" = true ]; then
    echo "Building it instead." >&2
    exec "$conscrypt_dir/build-conscrypt.sh"
  fi
  echo "Run conscrypt/build-conscrypt.sh, or the conscrypt workflow, to publish it." >&2
  exit 1
fi

# The names come from the checksum file rather than being guessed, so adding an
# architecture to the build doesn't need a matching change here.
while read -r _ name; do
  name="${name#./}"
  curl -fsSL --retry 3 --retry-delay 2 "$base_url/$name" -o "$staging/$name"
done < "$staging/SHA256SUMS"

curl -fsSL --retry 3 --retry-delay 2 "$base_url/build-info.json" -o "$staging/build-info.json"

(cd "$staging" && sha256sum --check --quiet SHA256SUMS)

rm -rf "$dist_dir"
mkdir -p "$(dirname "$dist_dir")"
mv "$staging" "$dist_dir"
trap - EXIT

echo "==> Fetched into $dist_dir"
ls -l "$dist_dir"
