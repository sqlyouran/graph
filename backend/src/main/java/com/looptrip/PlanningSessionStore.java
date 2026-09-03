package com.looptrip;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class PlanningSessionStore {
    public record PublicEvent(int round, PlanningEventType type, String message, Map<String, Object> details) {
        public PublicEvent { details = details == null ? Map.of() : Map.copyOf(details); }
    }

    private record StoredSession(String id, String userId, PlanningSessionState state, PlanningTerminalState terminalState,
            PlanRequest request, PlanResponse response, int currentVersion, Instant updatedAt,
            List<PublicEvent> events, List<PlanningVersion> versions, boolean cancellationRequested) {}

    private final Map<String, PlanningSession> sessions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;
    private final SessionProperties properties;
    private final Clock clock;

    public PlanningSessionStore(ObjectMapper objectMapper, SessionProperties properties, Clock clock) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.clock = clock;
    }

    @PostConstruct
    void restore() {
        try {
            Files.createDirectories(properties.storagePath());
            try (var files = Files.list(properties.storagePath())) {
                files.filter(path -> path.getFileName().toString().endsWith(".json")).forEach(this::restoreFile);
            }
            evictExpired();
        } catch (IOException exception) {
            throw new IllegalStateException("无法初始化规划会话存储", exception);
        }
    }

    public synchronized PlanningSession create(PlanRequest request) {
        return create(request, null);
    }

    public synchronized PlanningSession create(PlanRequest request, String userId) {
        evictExpired();
        long live = sessions.values().stream().filter(item -> item.state() != PlanningSessionState.CLOSED).count();
        if (live >= properties.maxLive()) {
            throw new SessionOperationException(HttpStatus.TOO_MANY_REQUESTS, "SESSION_CAPACITY_REACHED",
                    "当前已有 " + live + " 个规划会话正在处理，达到上限 " + properties.maxLive(),
                    "请等待已有会话结束后重试");
        }
        PlanningSession session = new PlanningSession(UUID.randomUUID().toString(), request, userId);
        sessions.put(session.id(), session);
        persist(session);
        return session;
    }

    public PlanningSession create() { return create(null); }
    public PlanningSession get(String id) { return sessions.get(id); }

    public synchronized void start(PlanningSession session, boolean revision) {
        session.cancellationFlag().set(false);
        session.state(revision ? PlanningSessionState.REVISING : PlanningSessionState.RUNNING);
        persist(session);
    }

    public synchronized void complete(PlanningSession session, PlanResponse response) {
        complete(session, response, Map.of());
    }

    public synchronized void complete(PlanningSession session, PlanResponse response, Map<String, Object> requestDiff) {
        session.response(response);
        session.terminalState(response.terminalState());
        appendVersions(session, response, requestDiff);
        session.state(PlanningSessionState.CLOSED);
        persist(session);
    }

    public synchronized Map<String, Object> cancel(PlanningSession session) {
        session.cancellationFlag().set(true);
        session.touch();
        publish(session, new PublicEvent(0, PlanningEventType.CANCEL_REQUESTED,
                "取消请求已接收，正在收尾当前一轮", Map.of("bestVersion", session.currentVersion())));
        persist(session);
        return session.bestSummary();
    }

    public synchronized PlanResponse rollback(PlanningSession session, int version) {
        if (session.state() != PlanningSessionState.CLOSED) {
            throw new SessionOperationException(HttpStatus.CONFLICT, "SESSION_NOT_CLOSED",
                    "规划仍在运行，当前不能回滚", "请等待当前规划结束后再回滚");
        }
        PlanningVersion selected = session.versions().stream().filter(item -> item.version() == version)
                .findFirst().orElseThrow(() -> new SessionOperationException(HttpStatus.BAD_REQUEST,
                        "VERSION_NOT_FOUND", "版本 " + version + " 不存在", "请选择可用版本",
                        session.versions().stream().map(PlanningVersion::version).toList()));
        PlanStatus status = selected.problems().isEmpty() ? PlanStatus.COMPLETED : PlanStatus.MAX_ROUNDS;
        List<PlanningRoundSnapshot> restoredRounds = selected.rounds();
        PlanResponse response = new PlanResponse(selected.plan(), selected.model(), selected.elapsedMs(), status,
                "已回滚到版本 " + version, restoredRounds.size(), selected.problems(), restoredRounds,
                selected.terminalState(), null);
        session.request(selected.request());
        session.response(response);
        session.terminalState(selected.terminalState());
        session.currentVersion(version);
        publish(session, new PublicEvent(0, PlanningEventType.ROLLBACK_COMPLETED,
                "已回滚到版本 " + version, Map.of("version", version)));
        persist(session);
        return response;
    }

    public void publish(PlanningSession session, PublicEvent event) {
        session.events().add(event);
        session.touch();
        for (BlockingQueue<PublicEvent> queue : session.listeners()) queue.offer(event);
    }

    /** 偏好确认这类跨会话事件：广播给该用户还活着的会话，前端时间线才看得到。 */
    public void publishToUser(String userId, PublicEvent event) {
        if (userId == null) return;
        sessions.values().stream()
                .filter(session -> userId.equals(session.userId()))
                .filter(session -> session.state() != PlanningSessionState.CLOSED
                        || session.updatedAt().isAfter(clock.instant().minus(properties.ttl())))
                .forEach(session -> publish(session, event));
    }

    public BlockingQueue<PublicEvent> subscribe(PlanningSession session) {
        BlockingQueue<PublicEvent> queue = new LinkedBlockingQueue<>();
        queue.addAll(session.events());
        session.listeners().add(queue);
        return queue;
    }

    public void unsubscribe(PlanningSession session, BlockingQueue<PublicEvent> queue) {
        session.listeners().remove(queue);
    }

    public synchronized void persist(PlanningSession session) {
        try {
            Files.createDirectories(properties.storagePath());
            Path target = path(session.id());
            Path temporary = Files.createTempFile(properties.storagePath(), session.id(), ".tmp");
            StoredSession stored = new StoredSession(session.id(), session.userId(), session.state(), session.terminalState(),
                    session.request(), session.response(), session.currentVersion(), session.updatedAt(),
                    List.copyOf(session.events()), session.versions(), session.cancellationRequested());
            objectMapper.writeValue(temporary.toFile(), stored);
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("无法保存规划会话 " + session.id(), exception);
        }
    }

    private void appendVersions(PlanningSession session, PlanResponse response, Map<String, Object> requestDiff) {
        int next = session.versions().stream().mapToInt(PlanningVersion::version).max().orElse(0) + 1;
        long estimatedTokens = response.rounds().stream().mapToLong(PlanningRoundSnapshot::estimatedTokens).sum();
        PlanningVersion version = new PlanningVersion(next, next, requestDiff, session.request(), response.plan(),
                response.problems(), response.terminalState(), response.roundsUsed(), estimatedTokens,
                response.model(), response.elapsedMs(), null, response.rounds());
        session.mutableVersions().add(version);
        session.currentVersion(next);
    }

    private PlanningRoundSnapshot legacySnapshot(PlanningVersion version) {
        return new PlanningRoundSnapshot(version.round(), version.request(), version.plan(), version.model(),
                version.elapsedMs(), version.problems(), List.of(), List.of(), List.of(),
                version.estimatedTokens(), "");
    }

    private void restoreFile(Path file) {
        try {
            StoredSession stored = objectMapper.readValue(file.toFile(), StoredSession.class);
            PlanningSession session = new PlanningSession(stored.id(), stored.request(), stored.userId());
            session.events().addAll(stored.events() == null ? List.of() : stored.events());
            List<PlanningVersion> normalized = normalizeRuns(stored.versions(), stored.currentVersion(), stored.response());
            session.mutableVersions().addAll(normalized);
            session.response(stored.response());
            session.terminalState(stored.terminalState());
            session.currentVersion(normalizeCurrentVersion(stored.versions(), stored.currentVersion(), normalized));
            session.updatedAt(stored.updatedAt() == null ? clock.instant() : stored.updatedAt());
            if (stored.cancellationRequested()) session.cancellationFlag().set(true);
            if (stored.state() == PlanningSessionState.RUNNING || stored.state() == PlanningSessionState.REVISING) {
                session.state(PlanningSessionState.CLOSED);
                session.terminalState(PlanningTerminalState.GUARDED);
            } else {
                session.state(stored.state());
            }
            sessions.put(session.id(), session);
        } catch (IOException ignored) {
            // One corrupt session must not prevent recovery of the remaining files.
        }
    }

    private void evictExpired() {
        Instant cutoff = clock.instant().minus(properties.ttl());
        List<String> expired = new ArrayList<>();
        sessions.forEach((id, session) -> {
            if (session.updatedAt().isBefore(cutoff)) expired.add(id);
        });
        for (String id : expired) {
            sessions.remove(id);
            try { Files.deleteIfExists(path(id)); } catch (IOException ignored) {}
        }
    }

    private List<PlanningVersion> normalizeRuns(List<PlanningVersion> storedVersions, int oldCurrent,
            PlanResponse storedResponse) {
        if (storedVersions == null || storedVersions.isEmpty()) return List.of();
        if (storedVersions.stream().allMatch(version -> !version.rounds().isEmpty())) return storedVersions;
        List<List<PlanningVersion>> groups = new ArrayList<>();
        List<PlanningVersion> current = null;
        int run = 1;
        int previousRound = 0;
        for (PlanningVersion version : storedVersions.stream()
                .sorted(Comparator.comparingInt(PlanningVersion::version)).toList()) {
            int effectiveRun = version.run() > 0 ? version.run() : run;
            if (current == null || effectiveRun != run || (version.run() == 0 && previousRound > 0 && version.round() <= previousRound)) {
                current = new ArrayList<>();
                groups.add(current);
                run = effectiveRun != run ? effectiveRun : run + (previousRound > 0 ? 1 : 0);
            }
            current.add(version);
            previousRound = version.round();
        }
        List<PlanningVersion> migrated = new ArrayList<>();
        for (int index = 0; index < groups.size(); index++) {
            List<PlanningVersion> group = groups.get(index);
            PlanningVersion selected = group.getLast();
            List<PlanningRoundSnapshot> rounds = group.stream()
                    .map(item -> item.snapshot() == null ? legacySnapshot(item) : item.snapshot()).toList();
            boolean selectedGroup = group.stream().anyMatch(item -> item.version() == oldCurrent);
            if (selectedGroup && storedResponse != null && !storedResponse.rounds().isEmpty()
                    && rounds.stream().allMatch(round -> round.events().isEmpty())) {
                rounds = storedResponse.rounds();
            }
            migrated.add(new PlanningVersion(index + 1, index + 1, selected.requestDiff(), selected.request(),
                    selected.plan(), selected.problems(), selected.terminalState(), rounds.size(),
                    rounds.stream().mapToLong(PlanningRoundSnapshot::estimatedTokens).sum(), selected.model(),
                    group.stream().mapToLong(PlanningVersion::elapsedMs).sum(), null, rounds));
        }
        return migrated;
    }

    private int normalizeCurrentVersion(List<PlanningVersion> stored, int oldCurrent, List<PlanningVersion> normalized) {
        if (stored == null || stored.isEmpty() || normalized.isEmpty()) return 0;
        if (stored.stream().allMatch(version -> !version.rounds().isEmpty())) return oldCurrent;
        PlanningVersion selected = stored.stream().filter(version -> version.version() == oldCurrent)
                .findFirst().orElse(stored.getLast());
        if (selected.run() > 0) {
            List<Integer> runs = stored.stream().map(PlanningVersion::run).distinct().toList();
            return runs.indexOf(selected.run()) + 1;
        }
        int groupedVersion = 1;
        int previousRound = 0;
        for (PlanningVersion version : stored.stream().sorted(Comparator.comparingInt(PlanningVersion::version)).toList()) {
            if (previousRound > 0 && version.round() <= previousRound) groupedVersion++;
            if (version.version() == selected.version()) return groupedVersion;
            previousRound = version.round();
        }
        return normalized.getLast().version();
    }

    private Path path(String id) { return properties.storagePath().resolve(id + ".json"); }
}
