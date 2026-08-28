package com.looptrip;

public final class SessionCancellationSignal implements PlanningCancellationSignal {
    private final PlanningSessionContext context;

    public SessionCancellationSignal(PlanningSessionContext context) { this.context = context; }

    @Override
    public boolean isCancelled() {
        PlanningSession session = context.current();
        return session != null && session.cancellationRequested();
    }
}
