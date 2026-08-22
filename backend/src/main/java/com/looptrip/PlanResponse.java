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
        List<PlanningRoundSnapshot> rounds) {

    public PlanResponse {
        problems = List.copyOf(problems);
        rounds = List.copyOf(rounds);
    }
}
