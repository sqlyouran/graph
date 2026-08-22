package com.looptrip;

import java.util.List;

public record PlanningRoundSnapshot(
        int round,
        PlanRequest originalRequest,
        TripPlan plan,
        String model,
        long elapsedMs,
        List<String> problems,
        List<ConstraintCheckResult> constraintResults,
        List<String> feedbackReceived,
        List<PlanningEvent> events) {

    public PlanningRoundSnapshot {
        problems = List.copyOf(problems);
        feedbackReceived = List.copyOf(feedbackReceived);
        constraintResults = List.copyOf(constraintResults);
        events = List.copyOf(events);
    }

    public boolean passed() {
        return problems.isEmpty();
    }
}
