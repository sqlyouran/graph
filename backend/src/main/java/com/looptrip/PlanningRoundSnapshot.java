package com.looptrip;

import java.util.List;

public record PlanningRoundSnapshot(
        int round,
        PlanRequest originalRequest,
        TripPlan plan,
        String model,
        long elapsedMs,
        BasicContractReviewResult review,
        List<ConstraintCheckResult> constraintResults,
        List<String> feedbackReceived,
        List<PlanningEvent> events) {

    public PlanningRoundSnapshot {
        feedbackReceived = List.copyOf(feedbackReceived);
        constraintResults = List.copyOf(constraintResults);
        events = List.copyOf(events);
    }
}
