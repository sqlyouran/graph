package com.looptrip;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SessionCancellationTests {
    @Test
    void runningCancellationFinishesCurrentRoundAndReturnsBestPlan() throws Exception {
        PlanningSessionContext context = new PlanningSessionContext();
        PlanningSession session = new PlanningSession("cancel-running", request());
        CountDownLatch generating = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        PlanGenerator generator = input -> {
            calls.incrementAndGet();
            generating.countDown();
            await(release);
            return PlanGenerationResult.success(TestTripPlans.complete(3), "test", 1);
        };
        TravelPlanningEngine engine = engine(generator, new SessionCancellationSignal(context));

        CompletableFuture<PlanResponse> future = CompletableFuture.supplyAsync(
                () -> context.bind(session, () -> engine.plan(request())));
        assertThat(generating.await(2, TimeUnit.SECONDS)).isTrue();
        session.cancellationFlag().set(true);
        release.countDown();
        PlanResponse response = future.get(3, TimeUnit.SECONDS);

        assertThat(response.terminalState()).isEqualTo(PlanningTerminalState.CANCELLED);
        assertThat(response.plan()).isNotNull();
        assertThat(response.rounds()).hasSize(1);
        assertThat(calls).hasValue(1);
    }

    @Test
    void cancellationBeforeFirstRoundReturnsWithoutBestPlan() {
        PlanningSessionContext context = new PlanningSessionContext();
        PlanningSession session = new PlanningSession("cancel-before", request());
        session.cancellationFlag().set(true);
        AtomicInteger calls = new AtomicInteger();
        TravelPlanningEngine engine = engine(input -> {
            calls.incrementAndGet();
            return PlanGenerationResult.success(TestTripPlans.complete(3), "test", 1);
        }, new SessionCancellationSignal(context));

        PlanResponse response = context.bind(session, () -> engine.plan(request()));

        assertThat(response.terminalState()).isEqualTo(PlanningTerminalState.CANCELLED);
        assertThat(response.plan()).isNull();
        assertThat(response.rounds()).isEmpty();
        assertThat(calls).hasValue(0);
        assertThat(context.current()).isNull();
    }

    private TravelPlanningEngine engine(PlanGenerator generator, PlanningCancellationSignal signal) {
        return new TravelPlanningEngine(generator, new BasicContractReview(), TestTripPlans.constraintReviewer(),
                new InMemoryPlanningEventSink(), null, null, new TechnicalRetryExecutor(millis -> {}), signal);
    }

    private PlanRequest request() {
        return new PlanRequest("上海", "杭州", LocalDate.of(2026, 10, 1), 3,
                3000, 700, "轻松", List.of("灵隐寺"), 3);
    }

    private void await(CountDownLatch latch) {
        try { latch.await(2, TimeUnit.SECONDS); } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(exception);
        }
    }
}
