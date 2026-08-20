package com.looptrip;

import java.util.Map;

public record PlanningEvent(
        int sequence,
        int round,
        PlanningEventType type,
        String message,
        Map<String, Object> details) {

    public PlanningEvent {
        details = Map.copyOf(details);
    }
}
