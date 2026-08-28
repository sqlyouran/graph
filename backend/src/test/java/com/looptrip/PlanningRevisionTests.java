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
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlanningRevisionTests {
    @TempDir Path temporaryDirectory;

    @Test
    void revisionUsesCurrentStoredVersionAsFirstRoundSeed() {
        PlanningSessionStore store = store();
        PlanningSession session = store.create(request());
        store.start(session, false);
        PlanResponse previous = response("旧问题");
        store.complete(session, previous);
        TravelPlanningEngine engine = mock(TravelPlanningEngine.class);
        when(engine.plan(any(PlanRequest.class), any(PlanningSeed.class))).thenReturn(response("新问题"));
        PlanController controller = new PlanController(engine, store, new SsePlanningEventSink(store),
                new PlanningSessionContext(), new RevisionPolicy(), Runnable::run);

        controller.revise(session.id(), new RevisionRequest(3500, null, "更轻松", 3,
                List.of("西湖", "灵隐寺"), null, null, null));

        ArgumentCaptor<PlanningSeed> seed = ArgumentCaptor.forClass(PlanningSeed.class);
        verify(engine).plan(any(PlanRequest.class), seed.capture());
        assertThat(seed.getValue().previousPlan()).isEqualTo(previous.plan());
        assertThat(seed.getValue().feedback()).containsExactly("旧问题");
        assertThat(session.state()).isEqualTo(PlanningSessionState.CLOSED);
        assertThat(session.events()).extracting(PlanningSessionStore.PublicEvent::type)
                .contains(PlanningEventType.REVISION_STARTED);
        assertThat(session.versions()).hasSize(2);
        assertThat(session.versions().getLast().requestDiff()).containsKeys("budget", "preferences", "maxRounds", "mustVisit");
    }

    private PlanningSessionStore store() {
        PlanningSessionStore store = new PlanningSessionStore(new ObjectMapper().findAndRegisterModules(),
                new SessionProperties(20, Duration.ofMinutes(30), temporaryDirectory), Clock.systemUTC());
        store.restore();
        return store;
    }

    private PlanRequest request() {
        return new PlanRequest("上海", "杭州", LocalDate.of(2026, 10, 1), 3,
                3000, 700, "轻松", List.of("西湖"), 2);
    }

    private PlanResponse response(String problem) {
        TripPlan plan = TestTripPlans.complete(3);
        PlanningRoundSnapshot round = new PlanningRoundSnapshot(1, request(), plan, "test", 10,
                List.of(problem), List.of(), List.of(), List.of(), 123, "C1:x");
        return new PlanResponse(plan, "test", 10, PlanStatus.MAX_ROUNDS, problem, 1,
                List.of(problem), List.of(round), PlanningTerminalState.GUARDED, null);
    }
}
