package com.looptrip;

import java.util.List;

public record PlanningSeed(TripPlan previousPlan, List<String> feedback) {
    public PlanningSeed {
        feedback = feedback == null ? List.of() : List.copyOf(feedback);
    }

    public static PlanningSeed empty() {
        return new PlanningSeed(null, List.of());
    }
}
