package com.looptrip;

import java.util.Map;

public record RevisionDecision(boolean allowed, PlanRequest request, Map<String, Object> diff,
        String reason, String suggestion) {
    public static RevisionDecision reject(String reason) {
        return new RevisionDecision(false, null, Map.of(), reason, "请开新会话重新规划");
    }
}
