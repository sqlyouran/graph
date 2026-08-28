package com.looptrip;

import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import static org.assertj.core.api.Assertions.assertThat;

class FactBackedPlanGeneratorTests {

    @Test
    void fallbackProducesDisplayablePlanThatPassesAllHardChecks() {
        TravelDataService data = new TravelDataService(new ObjectMapper().findAndRegisterModules(),
                new DefaultResourceLoader());
        data.loadFacts();
        PlanRequest request = new PlanRequest("上海", "杭州", LocalDate.of(2026, 10, 1), 3,
                3000, 700, "喜欢自然风景、本地小吃，行程不要太赶", List.of("西湖", "灵隐寺"), 2);

        TripPlan plan = new FactBackedPlanGenerator(data).generate(request);

        assertThat(new BasicContractReview().review(request, plan).passed()).isTrue();
        assertThat(TestTripPlans.constraintReviewer().review(request, plan))
                .filteredOn(result -> result.severity() == ConstraintSeverity.HARD)
                .allMatch(ConstraintCheckResult::passed);
        assertThat(plan.dailyPlans()).hasSize(3);
        assertThat(plan.dailyPlans()).flatExtracting(TripDayPlan::activities)
                .extracting(TripActivity::name).contains("西湖风景名胜区", "灵隐飞来峰景区");
        assertThat(plan.returnFlight().departureTime().toLocalTime()).isAfter(LocalDate.of(2026, 10, 1).atTime(18, 0).toLocalTime());
    }
}
