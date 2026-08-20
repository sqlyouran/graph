package com.looptrip;

import java.util.List;

public record PlanningRoundSnapshot(
        int round,
        PlanRequest originalRequest,
        String markdown,
        String model,
        long elapsedMs,
        BasicContractReviewResult review,
        List<String> feedbackReceived,
        List<PlanningEvent> events) {

    public PlanningRoundSnapshot {
        feedbackReceived = List.copyOf(feedbackReceived);
        events = List.copyOf(events);
    }
}
