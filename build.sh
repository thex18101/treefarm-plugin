#!/usr/bin/env bash
# Compile the plugin and package TreeFarm.jar.
# Expects the API jars in ./lib (e.g. lib/paper-api.jar and, optionally, lib/GriefPrevention.jar).
set -euo pipefail
cd "$(dirname "$0")"

OUT=out
rm -rf "$OUT"
mkdir -p "$OUT"

javac -encoding UTF-8 -cp "lib/*" -d "$OUT" $(find src -name '*.java')

cp src/plugin.yml src/config.yml "$OUT/"
jar cf TreeFarm.jar -C "$OUT" .

echo "built TreeFarm.jar"
