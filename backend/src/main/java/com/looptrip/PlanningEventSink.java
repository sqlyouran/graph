package com.looptrip;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public interface PlanningEventSink {

    <T> T capture(List<PlanningEvent> events, Supplier<T> action);

    void setRound(int round);

    void emit(PlanningEventType type, String message, Map<String, Object> details);
}
