package com.looptrip;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class PlanningRollbackTests {
    @TempDir Path temporaryDirectory;

    @Test
    void rollbackSwitchesStoredPointerWithZeroModelOrEngineCalls() {
        AtomicInteger modelCalls = new AtomicInteger();
        TravelPlanningEngine realEngine = new TravelPlanningEngine(input -> {
            modelCalls.incrementAndGet();
            return PlanGenerationResult.success(TestTripPlans.complete(3), "test", 10);
        }, new BasicContractReview(), TestTripPlans.constraintReviewer(), new InMemoryPlanningEventSink());
        PlanResponse generated = realEngine.plan(request());
        PlanningSessionStore store = store();
        PlanningSession session = store.create(request());
        store.start(session, false);
        store.complete(session, generated);
        TravelPlanningEngine engine = mock(TravelPlanningEngine.class);
        PlanController controller = new PlanController(engine, store, mock(SsePlanningEventSink.class),
                new PlanningSessionContext(), new RevisionPolicy(), Runnable::run);
        int callsBefore = modelCalls.get();

        Map<String, Object> result = controller.rollback(session.id(), 1);

        assertThat(result.get("currentVersion")).isEqualTo(1);
        assertThat(session.currentVersion()).isEqualTo(1);
        assertThat(modelCalls).hasValue(callsBefore);
        verifyNoInteractions(engine);
        assertThat(session.events()).extracting(PlanningSessionStore.PublicEvent::type)
                .contains(PlanningEventType.ROLLBACK_COMPLETED)
                .doesNotContain(PlanningEventType.GENERATION_STARTED);
    }

    @Test
    void rejectsRunningAndUnknownVersionsWithAvailableChoices() {
        PlanningSessionStore store = store();
        PlanningSession running = store.create(request());
        store.start(running, false);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> store.rollback(running, 1))
                .isInstanceOfSatisfying(SessionOperationException.class,
                        error -> assertThat(error.code()).isEqualTo("SESSION_NOT_CLOSED"));

        store.complete(running, response());
        assertThat(running.versions()).hasSize(1);
        assertThat(running.versions().getFirst().rounds())
                .extracting(PlanningRoundSnapshot::round).containsExactly(1, 2);

        PlanResponse restored = store.rollback(running, 1);
        assertThat(restored.roundsUsed()).isEqualTo(2);
        assertThat(restored.rounds()).extracting(PlanningRoundSnapshot::round).containsExactly(1, 2);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> store.rollback(running, 99))
                .isInstanceOfSatisfying(SessionOperationException.class, error -> {
                    assertThat(error.code()).isEqualTo("VERSION_NOT_FOUND");
                    assertThat(error.availableVersions()).containsExactly(1);
                });
    }

    private PlanningSessionStore store() {
        PlanningSessionStore store = new PlanningSessionStore(new ObjectMapper().findAndRegisterModules(),
                new SessionProperties(20, Duration.ofMinutes(30), temporaryDirectory), Clock.systemUTC());
        store.restore();
        return store;
    }

    private PlanRequest request() {
        return new PlanRequest("上海", "杭州", LocalDate.of(2026, 10, 1), 3,
                3000, 700, "轻松", List.of(), 2);
    }

    private PlanResponse response() {
        TripPlan first = TestTripPlans.complete(3);
        TripPlan second = TestTripPlans.complete(3);
        PlanningRoundSnapshot v1 = new PlanningRoundSnapshot(1, request(), first, "test", 10,
                List.of("问题一"), List.of(), List.of(), List.of(), 100, "C1:a");
        PlanningRoundSnapshot v2 = new PlanningRoundSnapshot(2, request(), second, "test", 12,
                List.of(), List.of(), List.of("问题一"), List.of(), 120, "");
        return new PlanResponse(second, "test", 22, PlanStatus.COMPLETED, "通过", 2,
                List.of(), List.of(v1, v2), PlanningTerminalState.SUCCESS, null);
    }
}
