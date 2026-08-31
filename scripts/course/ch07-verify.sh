#!/usr/bin/env bash
set -euo pipefail

# 第 7 章验收分组脚本：测试走二值通道（默认），评测必须显式 opt-in。
# 用法：ch07-verify.sh {events|cancel|revision|policy|isolation|rollback|intent|replay|eval|all}

group="${1:-all}"
case "$group" in
  events)    tests="TravelPlanningEngineTests" ;;
  cancel)    tests="SessionCancellationTests" ;;
  revision)  tests="PlanningRevisionTests" ;;
  policy)    tests="RevisionPolicyTests,PlanningIntentGateTests" ;;
  isolation) tests="TravelPlanningEngineTests#*failingSink*,SsePlanningEventSinkDesensitizationTests" ;;
  rollback)  tests="PlanningRollbackTests,PlanningChatZeroEngineTests" ;;
  intent)    tests="PlanningIntentRecognizerTests,PlanningIntentGateTests" ;;
  replay)    tests="Chapter7SessionReplayTest" ;;
  eval)      tests="PlanningIntentEvaluationTests" ;;
  all)       tests="" ;;
  *)
    echo "usage: $0 {events|cancel|revision|policy|isolation|rollback|intent|replay|eval|all}" >&2
    exit 2
    ;;
esac

script_dir="$(cd "$(dirname "$0")" && pwd)"
backend_dir="$(cd "$script_dir/../../backend" && pwd)"

cd "$backend_dir"
export MAVEN_OPTS="${MAVEN_OPTS:-} -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8"

if [ "$group" = "eval" ]; then
  # 评测是分数、会波动、要花模型调用：不进默认通道，必须显式带门禁跑
  mvn -Dtest="$tests" -Dlooptrip.eval=true test
elif [ "$group" = "all" ]; then
  mvn test
else
  mvn -q -Dtest="$tests" test
fi
