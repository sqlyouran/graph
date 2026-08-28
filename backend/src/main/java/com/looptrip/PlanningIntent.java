package com.looptrip;

import java.util.Map;

public record PlanningIntent(PlanningIntentAction action, Map<String, Object> slots, String echo,
        String utterance) {
    public PlanningIntent {
        action = action == null ? PlanningIntentAction.UNKNOWN : action;
        slots = slots == null ? Map.of() : Map.copyOf(slots);
        echo = echo == null ? "我暂时没理解这句话" : echo;
        utterance = utterance == null ? "" : utterance;
    }

    public static PlanningIntent unknown(String utterance) {
        return new PlanningIntent(PlanningIntentAction.UNKNOWN, Map.of(), "我暂时没理解这句话", utterance);
    }
}
