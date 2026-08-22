package com.looptrip;

import java.util.List;

public record PlanResponse(
        TripPlan plan,
        String model,
        long elapsedMs,
        PlanStatus status,
        String stopReason,
        int roundsUsed,
        List<String> problems,
        List<ConstraintCheckResult> constraintResults,
        List<PlanningRoundSnapshot> rounds,
        List<PlanningEvent> events) {

    public PlanResponse {
        problems = List.copyOf(problems);
        constraintResults = List.copyOf(constraintResults);
        rounds = List.copyOf(rounds);
        events = List.copyOf(events);
    }
}
