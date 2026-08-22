package com.looptrip;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class TimeConflictConstraintCheck implements TripPlanConstraint {
    @Override
    public ConstraintCheckResult check(PlanRequest request, TripPlan plan) {
        List<String> evidence = new ArrayList<>();
        List<String> suggestions = new ArrayList<>();
        for (TripDayPlan day : plan.dailyPlans()) {
            List<TripActivity> valid = new ArrayList<>();
            for (TripActivity activity : day.activities()) {
                String name = StringUtils.hasText(activity.name()) ? activity.name() : "未命名活动";
                if (activity.startTime() == null || activity.endTime() == null) {
                    evidence.add(day.date() + " “" + name + "”缺少开始或结束时间");
                    suggestions.add("为“" + name + "”补全开始时间和结束时间");
                } else if (!activity.endTime().isAfter(activity.startTime())) {
                    evidence.add(day.date() + " “" + name + "”结束时间 " + activity.endTime()
                            + " 未晚于开始时间 " + activity.startTime());
                    suggestions.add("调整“" + name + "”使结束时间晚于开始时间");
                } else {
                    valid.add(activity);
                }
            }
            valid.sort(Comparator.comparing(TripActivity::startTime));
            for (int index = 1; index < valid.size(); index++) {
                TripActivity previous = valid.get(index - 1);
                TripActivity current = valid.get(index);
                if (current.startTime().isBefore(previous.endTime())) {
                    evidence.add(day.date() + " “" + previous.name() + "”(" + previous.startTime() + "-"
                            + previous.endTime() + ")与“" + current.name() + "”(" + current.startTime() + "-"
                            + current.endTime() + ")重叠");
                    suggestions.add("错开“" + previous.name() + "”和“" + current.name() + "”的时间");
                }
            }
        }
        boolean passed = suggestions.isEmpty();
        if (passed) {
            evidence.add("所有同日活动时间有效且互不重叠，相邻活动允许首尾相接");
            suggestions.add("无需修改");
        }
        return new ConstraintCheckResult("C2", "时间冲突", ConstraintSeverity.HARD, passed, evidence, suggestions);
    }
}
