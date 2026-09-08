#!/usr/bin/env bash
set -euo pipefail

# 第 9 章第 2 节课堂回放：等价迁移的三条业务轨迹对照（不联网、不调模型）。
# 用法：ch09-s2-graph-migration.sh
# 输出：一次通过 / 返工一次 / 预检失败 三条固定轨迹，附业务轮次与模型调用次数。

script_dir="$(cd "$(dirname "$0")" && pwd)"
backend_dir="$(cd "$script_dir/../../backend" && pwd)"

cd "$backend_dir"
export MAVEN_OPTS="${MAVEN_OPTS:-} -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8"
mvn -Dtest="Chapter9GraphMigrationReplayTest" test
