package com.looptrip;

import java.util.function.Supplier;
import org.springframework.stereotype.Component;

@Component
public class PlanningSessionContext {
    private final ThreadLocal<PlanningSession> current = new ThreadLocal<>();

    public <T> T bind(PlanningSession session, Supplier<T> action) {
        PlanningSession previous = current.get();
        current.set(session);
        try {
            return action.get();
        } finally {
            if (previous == null) current.remove(); else current.set(previous);
        }
    }

    public PlanningSession current() { return current.get(); }
}
