#!/usr/bin/env bash
set -euo pipefail

# 第 8 章第 3 节课堂回放：偏好学习闭环（不联网、不调模型）。
# 用法：ch08-preference-replay.sh
# 三个会话攒出候选 → 确认写入画像 → 画像进 Prompt → 相反修改当场遗忘。
# 产物：backend/data/profiles/replay-demo-user.json 与 backend/logs/prompts/preference-demo-session-1.txt

script_dir="$(cd "$(dirname "$0")" && pwd)"
backend_dir="$(cd "$script_dir/../../backend" && pwd)"

cd "$backend_dir"
export MAVEN_OPTS="${MAVEN_OPTS:-} -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8"
mvn -q -Dtest="Chapter8PreferenceReplayTest" test

echo "画像文件：$backend_dir/data/profiles/replay-demo-user.json"
cat "$backend_dir/data/profiles/replay-demo-user.json"
echo "Prompt 快照：$backend_dir/logs/prompts/preference-demo-session-1.txt"
grep -A3 "SECTION:PROFILE" "$backend_dir/logs/prompts/preference-demo-session-1.txt" | head -8
