package com.looptrip;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OpeningHoursConstraintCheckTests {
    @Test
    void acceptsExactOpeningBoundariesAndSkipsMealsAndTransport() {
        TravelDataService data = mock(TravelDataService.class);
        when(data.findAttractionByName("课程景点")).thenReturn(Optional.of(new AttractionFact(
                "课程景点", "杭州", "西湖区", 0, LocalTime.of(9, 0), LocalTime.of(17, 0), List.of(), 60)));
        OpeningHoursConstraintCheck check = new OpeningHoursConstraintCheck(data);
        TripPlan plan = plan(
                new TripActivity("课程景点", "景点", LocalTime.of(9, 0), LocalTime.of(17, 0), "西湖区", 0),
                new TripActivity("未知餐厅", "餐饮", LocalTime.of(8, 0), LocalTime.of(9, 0), "西湖区", 0),
                new TripActivity("地铁", "交通", LocalTime.of(18, 0), LocalTime.of(19, 0), "西湖区", 0));

        assertThat(check.check(request(), plan).passed()).isTrue();
    }

    @Test
    void rejectsAnyPartOutsideOpeningHoursAndUnknownAttraction() {
        TravelDataService data = mock(TravelDataService.class);
        when(data.findAttractionByName("课程景点")).thenReturn(Optional.of(new AttractionFact(
                "课程景点", "杭州", "西湖区", 0, LocalTime.of(9, 0), LocalTime.of(17, 0), List.of(), 60)));
        OpeningHoursConstraintCheck check = new OpeningHoursConstraintCheck(data);

        ConstraintCheckResult result = check.check(request(), plan(
                new TripActivity("课程景点", "景点", LocalTime.of(8, 59), LocalTime.of(10, 0), "西湖区", 0),
                new TripActivity("未知景点", "景点", LocalTime.of(10, 0), LocalTime.of(11, 0), "西湖区", 0)));

        assertThat(result.passed()).isFalse();
        assertThat(result.evidence()).anyMatch(item -> item.contains("08:59") && item.contains("开放时间"))
                .anyMatch(item -> item.contains("未知景点") && item.contains("不存在"));
    }

    private TripPlan plan(TripActivity... activities) {
        return TestTripPlans.plan(1, List.of(new TripDayPlan(
                LocalDate.of(2026, 10, 1), null, List.of(activities))));
    }

    private PlanRequest request() {
        return new PlanRequest("上海", "杭州", LocalDate.of(2026, 10, 1), 1, 1000, 500, "", 1);
    }
}
