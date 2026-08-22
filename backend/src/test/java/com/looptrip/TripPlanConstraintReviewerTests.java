package com.looptrip;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TripPlanConstraintReviewerTests {
    @Test
    void returnsSevenIndependentChecksInContractOrder() {
        var results = TestTripPlans.constraintReviewer().review(
                new PlanRequest("上海", "杭州", java.time.LocalDate.of(2026, 10, 1),
                        3, 3000, 700, "", 1),
                TestTripPlans.complete(3));

        assertThat(results).extracting(ConstraintCheckResult::code)
                .containsExactly("C1", "C2", "C3", "C4", "C5", "C6", "C7");
        assertThat(results.subList(0, 4)).allSatisfy(result -> {
            assertThat(result.severity()).isEqualTo(ConstraintSeverity.HARD);
            assertThat(result.evidence()).isNotEmpty();
            assertThat(result.suggestions()).isNotEmpty();
        });
        assertThat(results.get(4).severity()).isEqualTo(ConstraintSeverity.SOFT);
        assertThat(results.get(5).severity()).isEqualTo(ConstraintSeverity.HARD);
        assertThat(results.get(6).severity()).isEqualTo(ConstraintSeverity.SOFT);
    }
}
