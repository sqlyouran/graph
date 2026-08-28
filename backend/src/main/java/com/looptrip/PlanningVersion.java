package com.looptrip;

import java.util.List;
import java.util.Map;

public record PlanningVersion(
        int version,
        int run,
        Map<String, Object> requestDiff,
        PlanRequest request,
        TripPlan plan,
        List<String> problems,
        PlanningTerminalState terminalState,
        int round,
        long estimatedTokens,
        String model,
        long elapsedMs,
        PlanningRoundSnapshot snapshot,
        List<PlanningRoundSnapshot> rounds) {
    public PlanningVersion {
        requestDiff = requestDiff == null ? Map.of() : Map.copyOf(requestDiff);
        problems = problems == null ? List.of() : List.copyOf(problems);
        rounds = rounds == null ? List.of() : List.copyOf(rounds);
    }
}
