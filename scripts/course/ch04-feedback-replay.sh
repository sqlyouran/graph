#!/usr/bin/env bash
set -euo pipefail

scenario="${1:-}"
case "$scenario" in
  success) test_method="success" ;;
  feedback) test_method="feedback" ;;
  no-feedback) test_method="noFeedback" ;;
  max-rounds) test_method="maxRounds" ;;
  *)
    echo "usage: $0 {success|feedback|no-feedback|max-rounds}" >&2
    exit 2
    ;;
esac

script_dir="$(cd "$(dirname "$0")" && pwd)"
backend_dir="$(cd "$script_dir/../../backend" && pwd)"

cd "$backend_dir"
export MAVEN_OPTS="${MAVEN_OPTS:-} -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8"
mvn -q -Dtest="Chapter4FeedbackReplayTest#$test_method" test
