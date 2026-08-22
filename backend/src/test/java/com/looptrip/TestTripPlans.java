package com.looptrip;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.DefaultResourceLoader;

final class TestTripPlans {
    private TestTripPlans() {}

    static TripPlan complete(int days) {
        List<TripDayPlan> dailyPlans = new ArrayList<>();
        for (int index = 0; index < days; index++) {
            LocalDate date = LocalDate.of(2026, 10, 1).plusDays(index);
            dailyPlans.add(new TripDayPlan(date,
                    new TripHotel("湖滨课程酒店", "湖滨", 680),
                    List.of(new TripActivity("西湖风景名胜区", "景点", LocalTime.of(9, 0),
                            LocalTime.of(11, 0), "西湖区", 0))));
        }
        return plan(days, dailyPlans);
    }

    static TripPlan missingLastDay() {
        return plan(3, complete(2).dailyPlans());
    }

    static TripPlan plan(int days, List<TripDayPlan> dailyPlans) {
        return new TripPlan("上海", "杭州", LocalDate.of(2026, 10, 1), days,
                new TripFlight("MU5211", "上海", "杭州",
                        LocalDateTime.of(2026, 10, 1, 8, 10),
                        LocalDateTime.of(2026, 10, 1, 9, 15), 520),
                new TripFlight("MU5212", "杭州", "上海",
                        LocalDateTime.of(2026, 10, 3, 19, 10),
                        LocalDateTime.of(2026, 10, 3, 20, 20), 560),
                dailyPlans);
    }

    static TripPlanConstraintReviewer constraintReviewer() {
        TravelDataService data = new TravelDataService(
                new ObjectMapper().findAndRegisterModules(), new DefaultResourceLoader());
        data.loadFacts();
        ConstraintProperties properties = new ConstraintProperties(
                new ConstraintProperties.Route(2, 40),
                new ConstraintProperties.Pace(2, 4, 540, LocalTime.of(11, 0), LocalTime.of(14, 0)));
        return new TripPlanConstraintReviewer(
                new BudgetConstraintCheck(data),
                new TimeConflictConstraintCheck(),
                new ClosedDayConstraintCheck(data),
                new OpeningHoursConstraintCheck(data),
                new RouteContinuityConstraintCheck(properties),
                new MustVisitConstraintCheck(),
                new DailyPaceConstraintCheck(properties));
    }
}
