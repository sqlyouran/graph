package com.looptrip;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClosedDayConstraintCheckTests {
    @Test
    void comparesTripDateDayOfWeekAndFailsUnknownAttraction() {
        TravelDataService data = mock(TravelDataService.class);
        when(data.findAttractionByName("周一闭馆馆")).thenReturn(Optional.of(new AttractionFact(
                "周一闭馆馆", "杭州", "西湖区", 0, LocalTime.of(9, 0), LocalTime.of(17, 0),
                List.of(DayOfWeek.MONDAY), 60)));
        ClosedDayConstraintCheck check = new ClosedDayConstraintCheck(data);

        ConstraintCheckResult monday = check.check(request(LocalDate.of(2026, 10, 5)),
                plan(LocalDate.of(2026, 10, 5), "周一闭馆馆"));
        ConstraintCheckResult tuesday = check.check(request(LocalDate.of(2026, 10, 6)),
                plan(LocalDate.of(2026, 10, 6), "周一闭馆馆"));
        ConstraintCheckResult unknown = check.check(request(LocalDate.of(2026, 10, 6)),
                plan(LocalDate.of(2026, 10, 6), "未知景点"));

        assertThat(monday.passed()).isFalse();
        assertThat(monday.evidence()).anyMatch(item -> item.contains("MONDAY") && item.contains("闭馆"));
        assertThat(tuesday.passed()).isTrue();
        assertThat(unknown.passed()).isFalse();
    }

    private TripPlan plan(LocalDate date, String name) {
        return TestTripPlans.plan(1, List.of(new TripDayPlan(date, null,
                List.of(new TripActivity(name, "景点", LocalTime.of(10, 0),
                        LocalTime.of(11, 0), "西湖区", 0)))));
    }

    private PlanRequest request(LocalDate date) {
        return new PlanRequest("上海", "杭州", date, 1, 1000, 500, "", 1);
    }
}
