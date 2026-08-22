package com.looptrip;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ClosedDayConstraintCheck implements TripPlanConstraint {
    private final TravelDataService travelData;

    public ClosedDayConstraintCheck(TravelDataService travelData) {
        this.travelData = travelData;
    }

    @Override
    public ConstraintCheckResult check(PlanRequest request, TripPlan plan) {
        List<String> evidence = new ArrayList<>();
        List<String> suggestions = new ArrayList<>();
        for (TripDayPlan day : plan.dailyPlans()) {
            for (TripActivity activity : day.activities()) {
                if (!TripActivityClassifier.isAttraction(activity)) continue;
                String name = StringUtils.hasText(activity.name()) ? activity.name() : "未命名景点";
                var fact = travelData.findAttractionByName(activity.name());
                if (fact.isEmpty()) {
                    evidence.add("景点“" + name + "”在事实快照中不存在，无法检查闭馆日");
                    suggestions.add("使用事实快照中的景点原始名称");
                } else if (day.date() == null) {
                    evidence.add("景点“" + name + "”所在日缺少日期");
                    suggestions.add("补充该日日期后重新检查闭馆日");
                } else if (fact.get().closedDays().contains(day.date().getDayOfWeek())) {
                    evidence.add(day.date() + " 是 " + day.date().getDayOfWeek() + "，“" + name + "”闭馆");
                    suggestions.add("将“" + name + "”调整到非闭馆日");
                } else {
                    evidence.add(day.date() + " 是 " + day.date().getDayOfWeek() + "，“" + name + "”开放");
                }
            }
        }
        boolean passed = suggestions.isEmpty();
        if (passed) suggestions.add("无需修改");
        return new ConstraintCheckResult("C3", "闭馆日", ConstraintSeverity.HARD, passed, evidence, suggestions);
    }
}
