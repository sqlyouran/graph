#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "$0")" && pwd)"
backend_dir="$(cd "$script_dir/../../backend" && pwd)"

cd "$backend_dir"
export MAVEN_OPTS="${MAVEN_OPTS:-} -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8"
mvn -q -Dtest=ChapterGuardrailsDemoTest test
