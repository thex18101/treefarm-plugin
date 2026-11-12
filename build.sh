#!/usr/bin/env bash
set -euo pipefail
if [[ $# -ne 2 ]]; then
  echo 'Usage: ./build.sh /path/to/paper-server /path/to/GriefPrevention.jar' >&2
  exit 2
fi
root=$(cd -- "$(dirname -- "$0")" && pwd)
server_dir=$(cd -- "$1" && pwd)
gp_jar=$(realpath -- "$2")
if [[ ! -d "$server_dir/libraries" || ! -f "$gp_jar" ]]; then
  echo 'Provide the libraries directory of a booted Paper server via its server root, and the GP compile-time JAR.' >&2
  exit 2
fi
classpath="$gp_jar"
while IFS= read -r -d '' lib; do classpath+=":$lib"; done < <(find "$server_dir/libraries" -name '*.jar' -print0)
build_dir=$(mktemp -d)
trap 'rm -rf -- "$build_dir"' EXIT
mapfile -d '' -t sources < <(find "$root/src" -name '*.java' -print0)
javac --release 25 -encoding UTF-8 -cp "$classpath" -d "$build_dir" "${sources[@]}"
cp "$root/src/config.yml" "$root/src/plugin.yml" "$build_dir/"
mkdir -p "$root/build"
jar --create --file "$root/build/SaplingFarm-2.2.0.jar" -C "$build_dir" .
echo "Built $root/build/SaplingFarm-2.2.0.jar"
