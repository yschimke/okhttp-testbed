#!/usr/bin/env bash

# Prints the release tag for the currently pinned build.
#
# One place, because three things have to agree on it: the workflow that publishes the
# release, the script that downloads it, and anyone looking at the releases page trying to
# work out which commits a jar came from. Both shas are in the tag because the artifact
# depends on both.

set -euo pipefail

conscrypt_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# shellcheck disable=SC1091
source "$conscrypt_dir/pinned.properties"

echo "conscrypt-${conscryptRef:0:12}-boringssl-${boringsslRef:0:12}"
