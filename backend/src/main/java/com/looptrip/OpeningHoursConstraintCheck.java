package com.looptrip;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class OpeningHoursConstraintCheck implements TripPlanConstraint {
    private final TravelDataService travelData;

    public OpeningHoursConstraintCheck(TravelDataService travelData) {
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
                var fact = travelData.findAttractionByName(name);
                if (fact.isEmpty()) {
                    evidence.add("景点“" + name + "”在事实快照中不存在，无法检查营业时间");
                    suggestions.add("使用事实快照中的景点原始名称");
                } else if (activity.startTime() == null || activity.endTime() == null) {
                    evidence.add("景点“" + name + "”缺少开始或结束时间");
                    suggestions.add("补全“" + name + "”的活动时间");
                } else if (activity.startTime().isBefore(fact.get().openTime())
                        || activity.endTime().isAfter(fact.get().closeTime())) {
                    evidence.add("“" + name + "”安排为 " + activity.startTime() + "-" + activity.endTime()
                            + "，开放时间为 " + fact.get().openTime() + "-" + fact.get().closeTime());
                    suggestions.add("将“" + name + "”完整调整到开放时间内");
                } else {
                    evidence.add("“" + name + "”的 " + activity.startTime() + "-" + activity.endTime()
                            + " 完整位于开放时间内");
                }
            }
        }
        boolean passed = suggestions.isEmpty();
        if (passed) suggestions.add("无需修改；餐饮和交通活动不参与本项检查");
        return new ConstraintCheckResult("C4", "营业时间", ConstraintSeverity.HARD, passed, evidence, suggestions);
    }
}
