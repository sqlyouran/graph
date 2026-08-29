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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class PlanningChatZeroEngineTests {
    @TempDir Path temporaryDirectory;

    @Test
    void unknownPathThroughChatNeverTouchesEngine() {
        Rig rig = rig((utterance, session) -> PlanningIntent.unknown(utterance));

        PlanningChatResponse response = rig.controller.chat(rig.session.id(), new PlanningChatRequest("???", false));

        assertThat(response.decision()).isEqualTo(PlanningChatDecision.UNRECOGNIZED);
        assertEngineUntouched(rig);
    }

    @Test
    void clarificationPathThroughChatNeverTouchesEngine() {
        Rig rig = rig((utterance, session) -> new PlanningIntent(PlanningIntentAction.REVISE_BUDGET, Map.of(),
                "我理解成：希望降低总预算。", utterance));

        PlanningChatResponse response = rig.controller.chat(rig.session.id(), new PlanningChatRequest("太贵了", false));

        assertThat(response.decision()).isEqualTo(PlanningChatDecision.CLARIFICATION_REQUIRED);
        assertEngineUntouched(rig);
    }

    @Test
    void questionPathThroughChatNeverTouchesEngine() {
        Rig rig = rig((utterance, session) -> new PlanningIntent(PlanningIntentAction.ASK_QUESTION,
                Map.of("question", "雷峰塔几点关门"), "我理解成：询问雷峰塔的闭馆时间。", utterance));

        PlanningChatResponse response = rig.controller.chat(rig.session.id(), new PlanningChatRequest("雷峰塔几点关门", false));

        assertThat(response.decision()).isEqualTo(PlanningChatDecision.ANSWERED);
        assertThat(response.text()).isEqualTo("测试答案");
        assertEngineUntouched(rig);
    }

    private void assertEngineUntouched(Rig rig) {
        verifyNoInteractions(rig.engine);
        assertThat(rig.session.events()).extracting(PlanningSessionStore.PublicEvent::type)
                .contains(PlanningEventType.INTENT_RECOGNIZED)
                .doesNotContain(PlanningEventType.GENERATION_STARTED);
    }

    private Rig rig(PlanningIntentRecognizer recognizer) {
        PlanningSessionStore store = new PlanningSessionStore(new ObjectMapper().findAndRegisterModules(),
                new SessionProperties(20, Duration.ofMinutes(30), temporaryDirectory), Clock.systemUTC());
        store.restore();
        PlanningSession session = store.create(request());
        store.start(session, false);
        store.complete(session, response());
        TravelPlanningEngine engine = mock(TravelPlanningEngine.class);
        PlanningChatServiceFacade facade = new PlanningChatServiceFacade(recognizer, (question, s) -> "测试答案");
        PlanController controller = new PlanController(engine, store, mock(SsePlanningEventSink.class),
                new PlanningSessionContext(), new RevisionPolicy(), Runnable::run, facade);
        return new Rig(controller, session, engine);
    }

    private record Rig(PlanController controller, PlanningSession session, TravelPlanningEngine engine) {}

    private PlanRequest request() {
        return new PlanRequest("上海", "杭州", LocalDate.of(2026, 10, 1), 3,
                3000, 700, "轻松", List.of(), 2);
    }

    private PlanResponse response() {
        PlanningRoundSnapshot v1 = new PlanningRoundSnapshot(1, request(), TestTripPlans.complete(3), "test", 10,
                List.of(), List.of(), List.of(), List.of(), 100, "C1:a");
        return new PlanResponse(TestTripPlans.complete(3), "test", 10, PlanStatus.COMPLETED, "通过", 1,
                List.of(), List.of(v1), PlanningTerminalState.SUCCESS, null);
    }
}
