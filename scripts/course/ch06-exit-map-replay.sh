#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "$0")" && pwd)"
backend_dir="$(cd "$script_dir/../../backend" && pwd)"
group="${1:-all}"

case "$group" in
  all) test_selector="Chapter6ExitMapReplayTest" ;;
  preflight|success|guards|retry|cancel) test_selector="Chapter6ExitMapReplayTest" ;;
  *) echo "usage: $0 [all|preflight|success|guards|retry|cancel]" >&2; exit 2 ;;
esac

cd "$backend_dir"
export MAVEN_OPTS="${MAVEN_OPTS:-} -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8"
if [[ "$group" == "all" ]]; then
  mvn -q -Dtest="$test_selector" test
else
  mvn -q -Dtest="$test_selector" -Dgroups="$group" test
fi
