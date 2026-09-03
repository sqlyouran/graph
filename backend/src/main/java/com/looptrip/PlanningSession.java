package com.looptrip;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PlanningSession {
    private final String id;
    private final List<PlanningSessionStore.PublicEvent> events = new CopyOnWriteArrayList<>();
    private final List<BlockingQueue<PlanningSessionStore.PublicEvent>> listeners = new CopyOnWriteArrayList<>();
    private final List<PlanningVersion> versions = new CopyOnWriteArrayList<>();
    private final AtomicBoolean cancellationRequested = new AtomicBoolean();
    private volatile PlanningSessionState state;
    private volatile PlanningTerminalState terminalState;
    private volatile PlanRequest request;
    private volatile PlanResponse response;
    private volatile int currentVersion;
    private volatile Instant updatedAt;
    private volatile PlanningIntent pendingIntent;
    private volatile String userId;

    PlanningSession(String id, PlanRequest request) {
        this(id, request, null);
    }

    PlanningSession(String id, PlanRequest request, String userId) {
        this.id = id;
        this.request = request;
        this.userId = userId;
        this.state = PlanningSessionState.CREATED;
        this.updatedAt = Instant.now();
    }

    public String id() { return id; }
    public String userId() { return userId; }
    public List<PlanningSessionStore.PublicEvent> events() { return events; }
    List<BlockingQueue<PlanningSessionStore.PublicEvent>> listeners() { return listeners; }
    public List<PlanningVersion> versions() { return List.copyOf(versions); }
    List<PlanningVersion> mutableVersions() { return versions; }
    public boolean cancellationRequested() { return cancellationRequested.get(); }
    AtomicBoolean cancellationFlag() { return cancellationRequested; }
    public PlanningSessionState state() { return state; }
    void state(PlanningSessionState value) { state = value; touch(); }
    public PlanningTerminalState terminalState() { return terminalState; }
    void terminalState(PlanningTerminalState value) { terminalState = value; }
    public PlanRequest request() { return request; }
    void request(PlanRequest value) { request = value; touch(); }
    public PlanResponse response() { return response; }
    void response(PlanResponse value) { response = value; touch(); }
    public int currentVersion() { return currentVersion; }
    void currentVersion(int value) { currentVersion = value; touch(); }
    public Instant updatedAt() { return updatedAt; }
    void updatedAt(Instant value) { updatedAt = value; }
    void touch() { updatedAt = Instant.now(); }
    public PlanningIntent pendingIntent() { return pendingIntent; }
    public void pendingIntent(PlanningIntent value) { pendingIntent = value; touch(); }

    public Map<String, Object> bestSummary() {
        if (response == null || response.plan() == null) return Map.of("available", false);
        return Map.of("available", true, "version", currentVersion,
                "destination", response.plan().destination(), "days", response.plan().days(),
                "remainingProblems", response.problems().size());
    }
}
