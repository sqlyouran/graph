#!/usr/bin/env bash
set -euo pipefail

# 第 8 章第 1 节课堂回放：PromptDumper 落盘格式（不联网、不调模型）。
# 用法：ch08-prompt-dumper-replay.sh
# 快照写到 backend/logs/prompts/：demo-session-1.txt 与 demo-session-1-2.txt（同轮重试不覆盖）。

script_dir="$(cd "$(dirname "$0")" && pwd)"
backend_dir="$(cd "$script_dir/../../backend" && pwd)"

cd "$backend_dir"
export MAVEN_OPTS="${MAVEN_OPTS:-} -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8"
mvn -q -Dtest="Chapter8PromptDumperReplayTest" test

echo "快照位置：$backend_dir/logs/prompts"
ls -la "$backend_dir/logs/prompts" | grep demo-session || true
