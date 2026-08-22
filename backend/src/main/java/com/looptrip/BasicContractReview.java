package com.looptrip;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class BasicContractReview {

    public BasicContractReviewResult review(PlanRequest request, TripPlan plan) {
        List<String> problems = new ArrayList<>();
        if (plan == null) {
            return new BasicContractReviewResult(false, List.of("缺少可检查的结构化行程"));
        }

        if (!sameText(request.origin(), plan.origin())) problems.add("行程出发地与原请求不一致");
        if (!sameText(request.destination(), plan.destination())) problems.add("行程目的地与原请求不一致");
        if (!Objects.equals(request.startDate(), plan.startDate())) problems.add("行程开始日期与原请求不一致");
        if (request.days() != plan.days()) problems.add("行程天数与原请求不一致");

        List<TripDayPlan> dailyPlans = plan.dailyPlans();
        if (dailyPlans.size() > request.days()) problems.add("每日计划包含请求日期范围外的安排");
        for (int day = 1; day <= request.days(); day++) {
            var expectedDate = request.startDate().plusDays(day - 1L);
            if (dailyPlans.stream().noneMatch(item -> expectedDate.equals(item.date()))) {
                problems.add("缺少第 " + day + " 天安排");
            }
        }
        long distinctDates = dailyPlans.stream().map(TripDayPlan::date).filter(Objects::nonNull).distinct().count();
        if (distinctDates < dailyPlans.size()) problems.add("每日计划包含重复日期");
        if (dailyPlans.stream().noneMatch(item -> item.hotel() != null
                && StringUtils.hasText(item.hotel().name()))) {
            problems.add("缺少住宿安排");
        }
        if (dailyPlans.stream().flatMap(item -> item.activities().stream())
                .noneMatch(activity -> TripActivityClassifier.isAttraction(activity)
                        && StringUtils.hasText(activity.name()))) {
            problems.add("缺少景点安排");
        }
        return new BasicContractReviewResult(problems.isEmpty(), problems);
    }

    private boolean sameText(String expected, String actual) {
        return StringUtils.hasText(expected)
                && StringUtils.hasText(actual)
                && expected.trim().equals(actual.trim());
    }
}
