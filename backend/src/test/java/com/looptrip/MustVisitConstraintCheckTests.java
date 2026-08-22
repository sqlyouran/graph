package com.looptrip;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MustVisitConstraintCheckTests {
    private final MustVisitConstraintCheck check = new MustVisitConstraintCheck();

    @Test
    void matchesCommonAliasesAgainstAttractionNames() {
        PlanRequest request = new PlanRequest("上海", "杭州", LocalDate.of(2026, 10, 1), 1,
                1000, 500, "", List.of("灵隐", "西湖"), 1);
        TripPlan plan = plan(
                activity("灵隐飞来峰景区", "ATTRACTION"),
                activity("西湖风景名胜区", "ATTRACTION"));

        assertThat(check.check(request, plan).passed()).isTrue();
    }

    @Test
    void requiresMustVisitToBeAnAttractionActivity() {
        PlanRequest request = new PlanRequest("上海", "杭州", LocalDate.of(2026, 10, 1), 1,
                1000, 500, "", List.of("灵隐寺"), 1);

        ConstraintCheckResult result = check.check(request, plan(activity("灵隐飞来峰景区", "MEAL")));

        assertThat(result.severity()).isEqualTo(ConstraintSeverity.HARD);
        assertThat(result.passed()).isFalse();
        assertThat(result.evidence()).contains("必去景点“灵隐寺”未出现在 ATTRACTION 活动中");
    }

    private TripActivity activity(String name, String type) {
        return new TripActivity(name, type, LocalTime.of(10, 0), LocalTime.of(11, 0), "西湖区", 0);
    }

    private TripPlan plan(TripActivity... activities) {
        return TestTripPlans.plan(1, List.of(new TripDayPlan(
                LocalDate.of(2026, 10, 1), null, List.of(activities))));
    }
}
