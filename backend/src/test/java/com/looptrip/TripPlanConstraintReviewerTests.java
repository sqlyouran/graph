package com.looptrip;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TripPlanConstraintReviewerTests {
    @Test
    void returnsFourIndependentHardChecksInContractOrder() {
        var results = TestTripPlans.constraintReviewer().review(
                new PlanRequest("上海", "杭州", java.time.LocalDate.of(2026, 10, 1),
                        3, 3000, 700, "", 1),
                TestTripPlans.complete(3));

        assertThat(results).extracting(ConstraintCheckResult::code)
                .containsExactly("C1", "C2", "C3", "C4");
        assertThat(results).allSatisfy(result -> {
            assertThat(result.severity()).isEqualTo(ConstraintSeverity.HARD);
            assertThat(result.evidence()).isNotEmpty();
            assertThat(result.suggestions()).isNotEmpty();
        });
    }
}
