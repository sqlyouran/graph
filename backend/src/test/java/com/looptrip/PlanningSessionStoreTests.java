package com.looptrip;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlanningSessionStoreTests {
    @TempDir Path temporaryDirectory;

    @Test
    void persistsAndRestoresVersionChainAndCurrentPointer() {
        PlanningSessionStore store = store(20);
        PlanningSession session = store.create(request());
        store.start(session, false);
        store.complete(session, response(), Map.of("budget", Map.of("before", 3000, "after", 3500)));
        int selected = session.currentVersion();

        PlanningSessionStore restored = store(20);
        PlanningSession loaded = restored.get(session.id());

        assertThat(loaded.state()).isEqualTo(PlanningSessionState.CLOSED);
        assertThat(loaded.versions()).hasSize(1);
        assertThat(loaded.currentVersion()).isEqualTo(selected);
        assertThat(loaded.versions().getFirst().requestDiff()).containsKey("budget");
        assertThat(loaded.versions().getFirst().rounds())
                .extracting(PlanningRoundSnapshot::round).containsExactly(1);
    }

    @Test
    void rejectsSessionsBeyondLiveLimitWithReasonAndSuggestion() {
        PlanningSessionStore store = store(1);
        store.create(request());

        assertThatThrownBy(() -> store.create(request()))
                .isInstanceOfSatisfying(SessionOperationException.class, error -> {
                    assertThat(error.status().value()).isEqualTo(429);
                    assertThat(error.getMessage()).contains("达到上限");
                    assertThat(error.suggestion()).isNotBlank();
                });
    }

    @Test
    void repeatedCancellationIsIdempotent() {
        PlanningSessionStore store = store(20);
        PlanningSession session = store.create(request());
        store.start(session, false);

        store.cancel(session);
        store.cancel(session);

        assertThat(session.cancellationRequested()).isTrue();
    }

    @Test
    void restoresLegacyPerRoundRecordsAsOneVersion() {
        PlanningSessionStore store = store(20);
        PlanningSession session = store.create(request());
        TripPlan plan = TestTripPlans.complete(3);
        PlanningRoundSnapshot first = new PlanningRoundSnapshot(1, request(), plan, "test", 10,
                List.of("问题一"), List.of(), List.of(), List.of(), 100, "C1:a");
        PlanningRoundSnapshot second = new PlanningRoundSnapshot(2, request(), plan, "test", 12,
                List.of(), List.of(), List.of("问题一"), List.of(), 120, "");
        session.mutableVersions().add(new PlanningVersion(1, 1, Map.of(), request(), plan,
                first.problems(), PlanningTerminalState.SUCCESS, 1, 100, "test", 10, first, List.of()));
        session.mutableVersions().add(new PlanningVersion(2, 1, Map.of(), request(), plan,
                second.problems(), PlanningTerminalState.SUCCESS, 2, 120, "test", 12, second, List.of()));
        session.currentVersion(2);
        store.persist(session);

        PlanningSession restored = store(20).get(session.id());

        assertThat(restored.versions()).hasSize(1);
        assertThat(restored.currentVersion()).isEqualTo(1);
        assertThat(restored.versions().getFirst().rounds())
                .extracting(PlanningRoundSnapshot::round).containsExactly(1, 2);
    }

    private PlanningSessionStore store(int maxLive) {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        PlanningSessionStore store = new PlanningSessionStore(mapper,
                new SessionProperties(maxLive, Duration.ofMinutes(30), temporaryDirectory), Clock.systemUTC());
        store.restore();
        return store;
    }

    private PlanRequest request() {
        return new PlanRequest("上海", "杭州", LocalDate.of(2026, 10, 1), 3,
                3000, 700, "轻松", List.of("西湖"), 2);
    }

    private PlanResponse response() {
        TripPlan plan = TestTripPlans.complete(3);
        PlanningRoundSnapshot round = new PlanningRoundSnapshot(1, request(), plan, "test", 10,
                List.of("待修问题"), List.of(), List.of(), List.of(), 123, "C1:x");
        return new PlanResponse(plan, "test", 10, PlanStatus.MAX_ROUNDS, "待修", 1,
                List.of("待修问题"), List.of(round), PlanningTerminalState.GUARDED, null);
    }
}
