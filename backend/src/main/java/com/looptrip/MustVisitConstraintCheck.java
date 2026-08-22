package com.looptrip;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

@Component
public class MustVisitConstraintCheck implements TripPlanConstraint {
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("灵隐", "灵隐飞来峰景区"),
            Map.entry("灵隐寺", "灵隐飞来峰景区"),
            Map.entry("飞来峰", "灵隐飞来峰景区"),
            Map.entry("西湖", "西湖风景名胜区"),
            Map.entry("大运河博物馆", "中国大运河博物馆"),
            Map.entry("运河博物馆", "中国大运河博物馆"),
            Map.entry("雷峰塔", "雷峰塔景区"),
            Map.entry("西溪湿地", "西溪国家湿地公园"));

    @Override
    public ConstraintCheckResult check(PlanRequest request, TripPlan plan) {
        List<String> evidence = new ArrayList<>();
        List<String> suggestions = new ArrayList<>();
        Set<String> planned = plan.dailyPlans().stream()
                .flatMap(day -> day.activities().stream())
                .filter(activity -> TripActivityClassifier.hasType(activity, "ATTRACTION"))
                .map(TripActivity::name)
                .filter(name -> name != null && !name.isBlank())
                .map(this::canonical)
                .collect(Collectors.toSet());

        for (String requested : request.mustVisit()) {
            String expected = canonical(requested);
            if (planned.contains(expected)) {
                evidence.add("必去景点“" + requested + "”已由 ATTRACTION 活动“" + expected + "”覆盖");
            } else {
                evidence.add("必去景点“" + requested + "”未出现在 ATTRACTION 活动中");
                suggestions.add("新增名称为“" + expected + "”的 ATTRACTION 活动");
            }
        }
        if (request.mustVisit().isEmpty()) evidence.add("用户未指定必去景点");
        boolean passed = suggestions.isEmpty();
        if (passed) suggestions.add("无需修改");
        return new ConstraintCheckResult("C6", "必去景点", ConstraintSeverity.HARD, passed, evidence, suggestions);
    }

    private String canonical(String value) {
        String compact = value == null ? "" : value.trim().replaceAll("\\s+", "");
        return ALIASES.getOrDefault(compact, compact);
    }
}
