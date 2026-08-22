请继续完善旅行规划项目。先读 backend/src/main/java/com/looptrip
下的代码，重点看 TravelPlanningEngine、TripPlanConstraintReviewer、七个
TripPlanConstraint 实现，以及 BasicContractReview。

现状：七条检查 C1-C7 已共用 TripPlanConstraint 接口，评审员用构造器注入收齐，
review() 只有一行 map。这些是前两节完成的，这次不要重写。这次要做的是
"把验收权接进 Loop"：

1. 引擎每轮拿到 TripPlan 后跑两层检查：BasicContractReview 守契约层——
   出发地、目的地、开始日期、天数是否与原请求一致，有无缺天、重复日期、
   住宿和景点；七条检查守交付层——五条 HARD 是否满足、两条 SOFT 有无建议。
2. 两层问题并入同一个 problems 列表，但入口有差别：只有 HARD 检查的失败
   建议带编号写进 problems；SOFT 失败只留在 constraintResults 报告里，
   不触发下一轮。
3. 本轮是否通过，只看 problems 列表是否为空，不允许出现"我觉得可以"式判断。
4. 每轮的行程、problems、constraintResults、feedback、事件一起记进
   PlanningRoundSnapshot，PlanResponse 带全部轮次快照返回——页面右栏、
   逐轮回放、下一轮反馈都从这份数据读，不做第二个来源。

七个检查的实现和评审员那行 map 都不要改。完成后告诉我改了哪些文件，
并带我从生成到判断到反馈走一遍完整链路。