#!/usr/bin/env bash
# Rebuild ../panel-lib, publish to mavenLocal and evict Loom's remapped copy so MC-Inspector picks it up.
#   scripts/refresh-panellib.sh [version]   (default: 1.21.11; "all" for every version)
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VER="${1:-1.21.11}"
cd "$ROOT/../panel-lib"
if [ "$VER" = all ]; then ./gradlew publishToMavenLocal -x test --quiet; else ./gradlew ":fabric:$VER:publishToMavenLocal" -x test --quiet; fi
# Loom keys the remapped cache by artifact coordinates + a hash that does not change for a same-version republish.
rm -rf "$ROOT"/.gradle/loom-cache/remapped_mods/remapped/dev/harrison/panel-lib-mc*
echo "panel-lib $VER published and MC-Inspector's remapped cache evicted"
