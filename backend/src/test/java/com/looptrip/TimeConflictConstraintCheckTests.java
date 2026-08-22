package com.looptrip;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TimeConflictConstraintCheckTests {
    private final TimeConflictConstraintCheck check = new TimeConflictConstraintCheck();

    @Test
    void allowsAdjacentActivities() {
        TripPlan plan = plan(
                activity("A", 9, 10),
                activity("B", 10, 11));

        assertThat(check.check(request(), plan).passed()).isTrue();
    }

    @Test
    void rejectsOverlapNonPositiveDurationAndMissingTime() {
        TripPlan plan = plan(
                activity("A", 9, 11),
                activity("B", 10, 12),
                activity("C", 13, 13),
                new TripActivity("D", "景点", null, LocalTime.of(15, 0), "西湖区", 0));

        ConstraintCheckResult result = check.check(request(), plan);

        assertThat(result.passed()).isFalse();
        assertThat(result.evidence()).anyMatch(item -> item.contains("A") && item.contains("B") && item.contains("重叠"))
                .anyMatch(item -> item.contains("C") && item.contains("未晚于"))
                .anyMatch(item -> item.contains("D") && item.contains("缺少"));
    }

    private TripActivity activity(String name, int start, int end) {
        return new TripActivity(name, "景点", LocalTime.of(start, 0), LocalTime.of(end, 0), "西湖区", 0);
    }

    private TripPlan plan(TripActivity... activities) {
        return TestTripPlans.plan(1, List.of(new TripDayPlan(
                LocalDate.of(2026, 10, 1), new TripHotel("课程酒店", "西湖区", 300), List.of(activities))));
    }

    private PlanRequest request() {
        return new PlanRequest("上海", "杭州", LocalDate.of(2026, 10, 1), 1, 1000, 500, "", 1);
    }
}
