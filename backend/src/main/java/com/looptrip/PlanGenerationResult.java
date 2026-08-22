package com.looptrip;

import java.util.List;

public record PlanGenerationResult(TripPlan plan, String model, long elapsedMs, List<String> problems) {

    public PlanGenerationResult {
        problems = problems == null ? List.of() : List.copyOf(problems);
    }

    public static PlanGenerationResult success(TripPlan plan, String model, long elapsedMs) {
        return new PlanGenerationResult(plan, model, elapsedMs, List.of());
    }
}
