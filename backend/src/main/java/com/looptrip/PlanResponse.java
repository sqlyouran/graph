package com.looptrip;

import java.util.List;

public record PlanResponse(
        String answer,
        String model,
        long elapsedMs,
        PlanStatus status,
        String stopReason,
        int roundsUsed,
        List<String> problems,
        List<PlanningRoundSnapshot> rounds,
        List<PlanningEvent> events) {

    public PlanResponse {
        problems = List.copyOf(problems);
        rounds = List.copyOf(rounds);
        events = List.copyOf(events);
    }
}
