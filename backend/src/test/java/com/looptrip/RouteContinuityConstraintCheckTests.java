package com.looptrip;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RouteContinuityConstraintCheckTests {
    private final RouteContinuityConstraintCheck check = new RouteContinuityConstraintCheck(properties());

    @Test
    void reportsTooManyCrossAreaMovesAndShortDirectGap() {
        TripPlan plan = plan(
                activity("A", "一区", "09:00", "10:00", "ATTRACTION"),
                activity("B", "二区", "10:30", "11:30", "ATTRACTION"),
                activity("C", "三区", "12:30", "13:30", "MEAL"),
                activity("D", "四区", "14:30", "15:30", "ATTRACTION"));

        ConstraintCheckResult result = check.check(request(), plan);

        assertThat(result.severity()).isEqualTo(ConstraintSeverity.SOFT);
        assertThat(result.passed()).isFalse();
        assertThat(result.evidence()).anyMatch(item -> item.contains("跨区 3 次"))
                .anyMatch(item -> item.contains("A") && item.contains("B") && item.contains("30 分钟"));
    }

    @Test
    void transferActivityMakesCrossAreaMovementExplicit() {
        TripPlan plan = plan(
                activity("A", "一区", "09:00", "10:00", "ATTRACTION"),
                activity("地铁", "途中", "10:00", "10:25", "TRANSFER"),
                activity("B", "二区", "10:25", "11:30", "ATTRACTION"));

        assertThat(check.check(request(), plan).passed()).isTrue();
    }

    private TripActivity activity(String name, String area, String start, String end, String type) {
        return new TripActivity(name, type, LocalTime.parse(start), LocalTime.parse(end), area, 0);
    }

    private TripPlan plan(TripActivity... activities) {
        return TestTripPlans.plan(1, List.of(new TripDayPlan(
                LocalDate.of(2026, 10, 1), null, List.of(activities))));
    }

    private PlanRequest request() {
        return new PlanRequest("上海", "杭州", LocalDate.of(2026, 10, 1), 1, 1000, 500, "", 1);
    }

    private ConstraintProperties properties() {
        return new ConstraintProperties(new ConstraintProperties.Route(2, 40),
                new ConstraintProperties.Pace(2, 4, 540, LocalTime.of(11, 0), LocalTime.of(14, 0)));
    }
}
