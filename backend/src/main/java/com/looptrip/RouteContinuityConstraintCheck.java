package com.looptrip;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class RouteContinuityConstraintCheck implements TripPlanConstraint {
    private final ConstraintProperties.Route properties;

    public RouteContinuityConstraintCheck(ConstraintProperties properties) {
        this.properties = properties.route();
    }

    @Override
    public ConstraintCheckResult check(PlanRequest request, TripPlan plan) {
        List<String> evidence = new ArrayList<>();
        List<String> suggestions = new ArrayList<>();
        for (TripDayPlan day : plan.dailyPlans()) {
            List<TripActivity> nonTransfers = day.activities().stream()
                    .filter(activity -> !TripActivityClassifier.isTransfer(activity))
                    .toList();
            int crossAreaCount = 0;
            for (int index = 1; index < nonTransfers.size(); index++) {
                TripActivity previous = nonTransfers.get(index - 1);
                TripActivity current = nonTransfers.get(index);
                if (differentArea(previous, current)) crossAreaCount++;
            }
            evidence.add(day.date() + " 跨区 " + crossAreaCount + " 次，建议上限 "
                    + properties.maxCrossAreaPerDay() + " 次");
            if (crossAreaCount > properties.maxCrossAreaPerDay()) {
                suggestions.add("减少 " + day.date() + " 的跨区安排，或调整活动顺序");
            }

            List<TripActivity> activities = day.activities();
            for (int index = 1; index < activities.size(); index++) {
                TripActivity previous = activities.get(index - 1);
                TripActivity current = activities.get(index);
                if (TripActivityClassifier.isTransfer(previous)
                        || TripActivityClassifier.isTransfer(current)
                        || !differentArea(previous, current)) {
                    continue;
                }
                if (previous.endTime() == null || current.startTime() == null) {
                    evidence.add(day.date() + " “" + previous.name() + "”到“" + current.name()
                            + "”跨区但缺少可计算的衔接时间");
                    suggestions.add("补全跨区活动时间，或在两项之间加入 TRANSFER 活动");
                    continue;
                }
                long gap = Duration.between(previous.endTime(), current.startTime()).toMinutes();
                evidence.add(day.date() + " “" + previous.name() + "”到“" + current.name()
                        + "”跨区间隔 " + gap + " 分钟");
                if (gap < properties.minTransferMinutes()) {
                    suggestions.add("为“" + previous.name() + "”到“" + current.name() + "”至少预留 "
                            + properties.minTransferMinutes() + " 分钟，或加入 TRANSFER 活动");
                }
            }
        }
        boolean passed = suggestions.isEmpty();
        if (passed) suggestions.add("无需调整路线衔接");
        return new ConstraintCheckResult("C5", "路线衔接", ConstraintSeverity.SOFT, passed, evidence, suggestions);
    }

    private boolean differentArea(TripActivity left, TripActivity right) {
        return StringUtils.hasText(left.area()) && StringUtils.hasText(right.area())
                && !left.area().trim().equals(right.area().trim());
    }
}
