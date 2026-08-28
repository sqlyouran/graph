package com.looptrip;

import java.util.Map;

public record PlanningChatResponse(PlanningIntentAction action, Map<String, Object> slots, String echo,
        PlanningChatDecision decision, String text, Object result) {
    public PlanningChatResponse {
        slots = slots == null ? Map.of() : Map.copyOf(slots);
    }
}
