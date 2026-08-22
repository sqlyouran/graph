package com.looptrip;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BasicContractReviewTests {

    private final BasicContractReview review = new BasicContractReview();

    @Test
    void acceptsCompleteStructuredPlanMatchingRequest() {
        assertThat(review.review(request(), TestTripPlans.complete(3)).passed()).isTrue();
    }

    @Test
    void reportsMissingCalendarDay() {
        assertThat(review.review(request(), TestTripPlans.missingLastDay()).problems())
                .containsExactly("缺少第 3 天安排");
    }

    @Test
    void reportsRequestIdentityMismatches() {
        TripPlan source = TestTripPlans.complete(3);
        TripPlan mismatched = new TripPlan("南京", "苏州", LocalDate.of(2026, 10, 2), 2,
                source.outboundFlight(), source.returnFlight(), source.dailyPlans());

        assertThat(review.review(request(), mismatched).problems())
                .contains("行程出发地与原请求不一致", "行程目的地与原请求不一致",
                        "行程开始日期与原请求不一致", "行程天数与原请求不一致");
    }

    @Test
    void reportsMissingHotelAndActivities() {
        TripPlan empty = TestTripPlans.plan(3, java.util.List.of(
                new TripDayPlan(LocalDate.of(2026, 10, 1), null, java.util.List.of()),
                new TripDayPlan(LocalDate.of(2026, 10, 2), null, java.util.List.of()),
                new TripDayPlan(LocalDate.of(2026, 10, 3), null, java.util.List.of())));

        assertThat(review.review(request(), empty).problems())
                .containsExactly("缺少住宿安排", "缺少景点安排");
    }

    @Test
    void mealAndTransferDoNotSatisfyAttractionContract() {
        TripPlan source = TestTripPlans.complete(3);
        TripPlan withoutAttraction = new TripPlan(
                source.origin(), source.destination(), source.startDate(), source.days(),
                source.outboundFlight(), source.returnFlight(),
                source.dailyPlans().stream().map(day -> new TripDayPlan(
                        day.date(), day.hotel(), java.util.List.of(
                                new TripActivity("午餐", "MEAL", java.time.LocalTime.NOON,
                                        java.time.LocalTime.of(13, 0), "湖滨", 0),
                                new TripActivity("地铁", "TRANSFER", java.time.LocalTime.of(13, 0),
                                        java.time.LocalTime.of(13, 30), "途中", 0))))
                        .toList());

        assertThat(review.review(request(), withoutAttraction).problems()).contains("缺少景点安排");
    }

    private PlanRequest request() {
        return new PlanRequest("上海", "杭州", LocalDate.of(2026, 10, 1), 3, 3000, 700, "轻松", 2);
    }
}
