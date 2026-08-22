package com.looptrip;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DailyPaceConstraintCheckTests {
    private final DailyPaceConstraintCheck check = new DailyPaceConstraintCheck(properties());

    @Test
    void ignoresAttractionMinimumOnFirstAndLastDayButStillRequiresMeal() {
        TripPlan plan = threeDayPlan(
                day(1, List.of(meal("12:00", "13:00"))),
                day(2, List.of(attraction("A", "09:00", "10:00"), meal("12:00", "13:00"))),
                day(3, List.of(meal("12:00", "13:00"))));

        ConstraintCheckResult result = check.check(request(), plan);

        assertThat(result.passed()).isFalse();
        assertThat(result.suggestions()).contains("为 2026-10-02 增加景点，使数量不少于 2 个")
                .noneMatch(item -> item.contains("2026-10-01") && item.contains("增加景点"))
                .noneMatch(item -> item.contains("2026-10-03") && item.contains("增加景点"));
    }

    @Test
    void acceptsTwoToFourAttractionsNineHourBoundaryAndMealWindowOverlap() {
        List<TripActivity> middle = new ArrayList<>();
        middle.add(attraction("A", "06:00", "10:00"));
        middle.add(meal("11:00", "12:00"));
        middle.add(attraction("B", "12:00", "16:00"));
        TripPlan plan = threeDayPlan(
                day(1, List.of(meal("13:30", "14:30"))),
                day(2, middle),
                day(3, List.of(meal("11:30", "12:00"))));

        assertThat(check.check(request(), plan).passed()).isTrue();
    }

    @Test
    void reportsTooManyAttractionsExcessDurationAndMissingMeal() {
        List<TripActivity> activities = new ArrayList<>();
        for (int index = 0; index < 5; index++) {
            activities.add(attraction("A" + index, "%02d:00".formatted(5 + index * 2),
                    "%02d:00".formatted(7 + index * 2)));
        }
        TripPlan plan = threeDayPlan(day(1, List.of(meal("12:00", "13:00"))),
                day(2, activities), day(3, List.of(meal("12:00", "13:00"))));

        ConstraintCheckResult result = check.check(request(), plan);

        assertThat(result.severity()).isEqualTo(ConstraintSeverity.SOFT);
        assertThat(result.passed()).isFalse();
        assertThat(result.suggestions()).anyMatch(item -> item.contains("不超过 4 个"))
                .anyMatch(item -> item.contains("540 分钟以内"))
                .anyMatch(item -> item.contains("安排 MEAL"));
    }

    private TripDayPlan day(int day, List<TripActivity> activities) {
        return new TripDayPlan(LocalDate.of(2026, 10, day), null, activities);
    }

    private TripActivity attraction(String name, String start, String end) {
        return new TripActivity(name, "ATTRACTION", LocalTime.parse(start), LocalTime.parse(end), "区域", 0);
    }

    private TripActivity meal(String start, String end) {
        return new TripActivity("午餐", "MEAL", LocalTime.parse(start), LocalTime.parse(end), "区域", 0);
    }

    private TripPlan threeDayPlan(TripDayPlan... days) {
        return TestTripPlans.plan(3, List.of(days));
    }

    private PlanRequest request() {
        return new PlanRequest("上海", "杭州", LocalDate.of(2026, 10, 1), 3, 3000, 700, "", 1);
    }

    private ConstraintProperties properties() {
        return new ConstraintProperties(new ConstraintProperties.Route(2, 40),
                new ConstraintProperties.Pace(2, 4, 540, LocalTime.of(11, 0), LocalTime.of(14, 0)));
    }
}
