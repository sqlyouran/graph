package com.looptrip;

import java.util.List;

public record PlanGenerationInput(
        PlanRequest originalRequest,
        int round,
        String previousMarkdown,
        List<String> feedbackProblems) {

    public PlanGenerationInput {
        feedbackProblems = List.copyOf(feedbackProblems);
    }
}
