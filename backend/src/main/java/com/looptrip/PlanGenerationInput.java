package com.looptrip;

import java.util.List;

public record PlanGenerationInput(
        PlanRequest originalRequest,
        int round,
        TripPlan previousPlan,
        List<String> feedbackProblems,
        PlanningFacts facts) {

    public PlanGenerationInput {
        feedbackProblems = List.copyOf(feedbackProblems);
    }

    public PlanGenerationInput(PlanRequest originalRequest, int round, TripPlan previousPlan,
            List<String> feedbackProblems) {
        this(originalRequest, round, previousPlan, feedbackProblems, null);
    }
}
