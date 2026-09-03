#!/usr/bin/env bash
set -euo pipefail

# 第 8 章第 4 节课堂评测：记忆八维自动化评测（不联网、不调模型）。
# 用法：ch08-memory-eval.sh [画像数]   默认 6 个画像的缩小集，几分钟内出结果；0 = 全量 50 画像。
# 门禁：-Dlooptrip.eval=true（不进 CI 二值通道）；报告写 eval/reports/memory-eval-<时间戳>.md。

script_dir="$(cd "$(dirname "$0")" && pwd)"
backend_dir="$(cd "$script_dir/../../backend" && pwd)"
limit="${1:-6}"

cd "$backend_dir"
export MAVEN_OPTS="${MAVEN_OPTS:-} -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8"
mvn -q -Dtest="Chapter8MemoryEvaluationTests" -Dlooptrip.eval=true -Dlooptrip.eval.limit="$limit" test

report="$(ls -t "$backend_dir/../eval/reports"/memory-eval-*.md | head -1)"
echo "评测报告：$report"
cat "$report"
