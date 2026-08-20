package com.looptrip;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class InMemoryPlanningEventSink implements PlanningEventSink {

    private static final Logger log = LoggerFactory.getLogger(InMemoryPlanningEventSink.class);

    private final ThreadLocal<Context> context = new ThreadLocal<>();

    @Override
    public <T> T capture(List<PlanningEvent> events, Supplier<T> action) {
        Context previous = context.get();
        context.set(new Context(events));
        try {
            return action.get();
        } finally {
            if (previous == null) {
                context.remove();
            } else {
                context.set(previous);
            }
        }
    }

    @Override
    public void setRound(int round) {
        Context current = context.get();
        if (current != null) current.round = round;
    }

    @Override
    public void emit(PlanningEventType type, String message, Map<String, Object> details) {
        try {
            Context current = context.get();
            if (current != null) {
                current.events.add(new PlanningEvent(
                        ++current.sequence,
                        current.round,
                        type,
                        message,
                        details));
            }
        } catch (RuntimeException exception) {
            log.warn("Failed to record planning event type={}", type, exception);
        }
    }

    private static final class Context {
        private final List<PlanningEvent> events;
        private int sequence;
        private int round;

        private Context(List<PlanningEvent> events) {
            this.events = events;
        }
    }
}
