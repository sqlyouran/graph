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
        List<PlanningRoundSnapshot> rounds,
        PlanningTerminalState terminalState,
        PlanningStopReport stopReport) {

    public PlanResponse {
        problems = List.copyOf(problems);
        rounds = List.copyOf(rounds);
    }

    public PlanResponse(TripPlan plan, String model, long elapsedMs, PlanStatus status, String stopReason,
            int roundsUsed, List<String> problems, List<PlanningRoundSnapshot> rounds) {
        this(plan, model, elapsedMs, status, stopReason, roundsUsed, problems, rounds,
                status == PlanStatus.COMPLETED ? PlanningTerminalState.SUCCESS : PlanningTerminalState.GUARDED, null);
    }
}
