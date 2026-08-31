package com.looptrip;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 第 7 章会话级固定回放：脚本化生成器 + 真实控制器/会话链路，不联网、不调真实模型。
 */
class Chapter7SessionReplayTest {
    @TempDir Path temporaryDirectory;

    private AtomicInteger engineCalls;
    private PlanController controller;
    private PlanningSessionStore store;

    @BeforeEach
    void setUp() {
        engineCalls = new AtomicInteger();
        PlanGenerator script = input -> {
            engineCalls.incrementAndGet();
            boolean seeded = input.previousPlan() != null;
            if (input.round() < 3 && !seeded) {
                return new PlanGenerationResult(null, "scripted", 10, List.of("缺少住宿安排"));
            }
            return PlanGenerationResult.success(TestTripPlans.complete(3), "scripted", 10);
        };
        store = new PlanningSessionStore(new ObjectMapper().findAndRegisterModules(),
                new SessionProperties(20, Duration.ofMinutes(30), temporaryDirectory), Clock.systemUTC());
        SsePlanningEventSink sse = new SsePlanningEventSink(store);
        TravelPlanningEngine engine = new TravelPlanningEngine(script,
                new BasicContractReview(), TestTripPlans.constraintReviewer(), sse);
        PlanningChatServiceFacade chat = mock(PlanningChatServiceFacade.class);
        when(chat.recognize(anyString(), any())).thenAnswer(invocation -> {
            String utterance = invocation.getArgument(0);
            if (utterance.contains("预算")) {
                return new PlanningIntent(PlanningIntentAction.REVISE_BUDGET,
                        Map.of("budget", 4000), "我理解成：把总预算调整为 4000。", utterance);
            }
            return new PlanningIntent(PlanningIntentAction.ROLLBACK,
                    Map.of(), "我理解成：回到上一版方案。", utterance);
        });
        controller = new PlanController(engine, store, sse,
                new PlanningSessionContext(), new RevisionPolicy(), Runnable::run, chat);
    }

    @Test
    void fullSessionReplayKeepsEventOrderAndSanitizesEveryDetail() {
        PlanningSession session = startSession();
        controller.chat(session.id(), new PlanningChatRequest("预算提到 4000，其他不变", false));
        controller.chat(session.id(), new PlanningChatRequest("确认", true));

        List<PlanningEventType> types = session.events().stream()
                .map(PlanningSessionStore.PublicEvent::type).toList();
        assertThat(types)
                .containsSubsequence(PlanningEventType.ROUND_STARTED, PlanningEventType.REVIEW_COMPLETED,
                        PlanningEventType.INTENT_RECOGNIZED, PlanningEventType.REVISION_STARTED)
                .contains(PlanningEventType.COMPLETED);
        assertThat(types.indexOf(PlanningEventType.INTENT_RECOGNIZED))
                .isLessThan(types.indexOf(PlanningEventType.REVISION_STARTED));
        for (PlanningSessionStore.PublicEvent event : session.events()) {
            assertThat(event.details().keySet())
                    .as("event %s leaks sensitive keys", event.type())
                    .doesNotContain("apiKey", "prompt", "stackTrace", "problems");
        }
    }

    @Test
    void singleRevisionRunCostsLessThanFreshTwoRoundRun() {
        PlanningSession session = startSession();
        controller.chat(session.id(), new PlanningChatRequest("预算提到 4000，其他不变", false));
        controller.chat(session.id(), new PlanningChatRequest("确认", true));

        assertThat(session.versions()).hasSize(2);
        long freshRunTokens = session.versions().getFirst().estimatedTokens();
        long revisionRunTokens = session.versions().getLast().estimatedTokens();
        assertThat(session.versions().getFirst().rounds()).hasSize(3);
        assertThat(session.versions().getLast().rounds()).hasSize(1);
        assertThat(revisionRunTokens).isLessThan(freshRunTokens);
    }

    @Test
    void confirmAndRollbackPhasesNeverCallTheEngine() {
        PlanningSession session = startSession();

        controller.chat(session.id(), new PlanningChatRequest("预算提到 4000，其他不变", false));
        int callsAfterPendingIntent = engineCalls.get();
        controller.chat(session.id(), new PlanningChatRequest("确认", true));
        int callsAfterRevision = engineCalls.get();
        controller.chat(session.id(), new PlanningChatRequest("回滚吧", false));
        assertThat(engineCalls).hasValue(callsAfterRevision);

        assertThat(callsAfterRevision).isEqualTo(callsAfterPendingIntent + 1);
        assertThat(session.currentVersion()).isEqualTo(1);
    }

    private PlanningSession startSession() {
        Object body = controller.ask(request());
        @SuppressWarnings("unchecked")
        String sessionId = (String) ((Map<String, Object>) ((org.springframework.http.ResponseEntity<?>) body).getBody()).get("sessionId");
        return store.get(sessionId);
    }

    private PlanRequest request() {
        return new PlanRequest("上海", "杭州", LocalDate.of(2026, 10, 1), 3,
                3000, 700, "轻松", List.of(), 3);
    }
}
