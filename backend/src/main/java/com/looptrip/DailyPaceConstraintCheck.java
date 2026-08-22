package com.looptrip;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

@Component
public class DailyPaceConstraintCheck implements TripPlanConstraint {
    private final ConstraintProperties.Pace properties;

    public DailyPaceConstraintCheck(ConstraintProperties properties) {
        this.properties = properties.pace();
    }

    @Override
    public ConstraintCheckResult check(PlanRequest request, TripPlan plan) {
        List<String> evidence = new ArrayList<>();
        List<String> suggestions = new ArrayList<>();
        LocalDate lastDate = request.startDate().plusDays(request.days() - 1L);
        for (TripDayPlan day : plan.dailyPlans()) {
            long attractionCount = day.activities().stream()
                    .filter(activity -> TripActivityClassifier.hasType(activity, "ATTRACTION"))
                    .count();
            boolean boundaryDay = request.startDate().equals(day.date()) || lastDate.equals(day.date());
            evidence.add(day.date() + " 景点 " + attractionCount + " 个"
                    + (boundaryDay ? "（首日/末日不检查下限）" : ""));
            if (!boundaryDay && attractionCount < properties.minAttractionsPerDay()) {
                suggestions.add("为 " + day.date() + " 增加景点，使数量不少于 "
                        + properties.minAttractionsPerDay() + " 个");
            }
            if (attractionCount > properties.maxAttractionsPerDay()) {
                suggestions.add("减少 " + day.date() + " 的景点，使数量不超过 "
                        + properties.maxAttractionsPerDay() + " 个");
            }

            long totalMinutes = 0;
            boolean allDurationsValid = true;
            for (TripActivity activity : day.activities()) {
                if (activity.startTime() == null || activity.endTime() == null
                        || !activity.endTime().isAfter(activity.startTime())) {
                    allDurationsValid = false;
                    continue;
                }
                totalMinutes += Duration.between(activity.startTime(), activity.endTime()).toMinutes();
            }
            evidence.add(day.date() + " 活动总时长 " + totalMinutes + " 分钟，建议上限 "
                    + properties.maxActivityMinutesPerDay() + " 分钟");
            if (!allDurationsValid) suggestions.add("补全并修正 " + day.date() + " 的活动时间后重新评估节奏");
            if (totalMinutes > properties.maxActivityMinutesPerDay()) {
                suggestions.add("缩短 " + day.date() + " 的活动总时长至 "
                        + properties.maxActivityMinutesPerDay() + " 分钟以内");
            }

            boolean hasMeal = day.activities().stream()
                    .filter(TripActivityClassifier::isMeal)
                    .anyMatch(activity -> activity.startTime() != null && activity.endTime() != null
                            && activity.startTime().isBefore(properties.mealWindowEnd())
                            && activity.endTime().isAfter(properties.mealWindowStart()));
            evidence.add(day.date() + (hasMeal ? " 在午餐窗口有 MEAL" : " 在午餐窗口没有 MEAL"));
            if (!hasMeal) {
                suggestions.add("在 " + day.date() + " 的 " + properties.mealWindowStart() + "-"
                        + properties.mealWindowEnd() + " 安排 MEAL");
            }
        }
        boolean passed = suggestions.isEmpty();
        if (passed) suggestions.add("无需调整每日节奏");
        return new ConstraintCheckResult("C7", "每日节奏", ConstraintSeverity.SOFT, passed, evidence, suggestions);
    }
}
