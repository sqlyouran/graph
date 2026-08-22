package com.looptrip;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BudgetConstraintCheckTests {
    @Test
    void usesTwoFlightsActualNightsAndDeduplicatedTicketsAtExactLimits() {
        TravelDataService data = facts();
        BudgetConstraintCheck check = new BudgetConstraintCheck(data);
        PlanRequest request = request(850, 300);

        ConstraintCheckResult result = check.check(request, plan("F1", "课程酒店", "课程景点"));

        assertThat(result.passed()).isTrue();
        assertThat(result.evidence()).anyMatch(item ->
                item.contains("200（航班）+600（2 晚住宿）+50（去重门票）=850 元"));
    }

    @Test
    void failsBudgetHotelLimitAndUnknownNamesIndependentlyOfModelPrices() {
        TravelDataService data = facts();
        BudgetConstraintCheck check = new BudgetConstraintCheck(data);

        ConstraintCheckResult limits = check.check(request(849, 299), plan("F1", "课程酒店", "课程景点"));
        ConstraintCheckResult unknown = check.check(request(9999, 9999), plan("UNKNOWN", "未知酒店", "未知景点"));

        assertThat(limits.passed()).isFalse();
        assertThat(limits.suggestions()).anyMatch(item -> item.contains("每晚不超过 299"))
                .anyMatch(item -> item.contains("事实总价不超过 849"));
        assertThat(unknown.passed()).isFalse();
        assertThat(unknown.evidence()).anyMatch(item -> item.contains("UNKNOWN") && item.contains("不存在"))
                .anyMatch(item -> item.contains("未知酒店") && item.contains("不存在"))
                .anyMatch(item -> item.contains("未知景点") && item.contains("不存在"));
    }

    private TravelDataService facts() {
        TravelDataService data = mock(TravelDataService.class);
        when(data.findFlightByNumber("F1")).thenReturn(Optional.of(new FlightFact(
                "F1", "上海", "杭州", LocalDateTime.parse("2026-10-01T08:00:00"),
                LocalDateTime.parse("2026-10-01T09:00:00"), 100)));
        when(data.findHotelByName("课程酒店")).thenReturn(Optional.of(new HotelFact("课程酒店", "杭州", "西湖区", 300, 4.5)));
        when(data.findAttractionByName("课程景点")).thenReturn(Optional.of(new AttractionFact(
                "课程景点", "杭州", "西湖区", 50, LocalTime.of(9, 0), LocalTime.of(17, 0),
                List.of(DayOfWeek.MONDAY), 120)));
        return data;
    }

    private TripPlan plan(String flight, String hotel, String attraction) {
        List<TripDayPlan> days = java.util.stream.IntStream.range(0, 3)
                .mapToObj(offset -> new TripDayPlan(LocalDate.of(2026, 10, 1).plusDays(offset),
                        new TripHotel(hotel, "西湖区", 1),
                        List.of(new TripActivity(attraction, "景点", LocalTime.of(10, 0),
                                LocalTime.of(11, 0), "西湖区", 999))))
                .toList();
        TripFlight selected = new TripFlight(flight, "上海", "杭州", null, null, 9999);
        return new TripPlan("上海", "杭州", LocalDate.of(2026, 10, 1), 3, selected, selected, days);
    }

    private PlanRequest request(int budget, int hotelLimit) {
        return new PlanRequest("上海", "杭州", LocalDate.of(2026, 10, 1), 3,
                budget, hotelLimit, "", 2);
    }
}
