package com.looptrip;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Chapter6PlanningEngineTests {

    @Test
    void unsupportedDestinationStopsBeforeGeneration() {
        AtomicInteger calls = new AtomicInteger();
        PlanResponse response = engine(input -> {
            calls.incrementAndGet();
            throw new AssertionError("must not call model");
        }).plan(new PlanRequest("上海", "火星", LocalDate.of(2026, 10, 1), 3,
                3000, 700, "", 2));

        assertThat(calls).hasValue(0);
        assertThat(response.terminalState()).isEqualTo(PlanningTerminalState.UNSUPPORTED);
        assertThat(response.rounds()).isEmpty();
        assertThat(response.stopReport().guardCode()).isEqualTo("P0_UNSUPPORTED");
    }

    @Test
    void impossibleMustVisitAndMinimumBudgetStopBeforeGeneration() {
        AtomicInteger calls = new AtomicInteger();
        TravelPlanningEngine engine = engine(input -> {
            calls.incrementAndGet();
            throw new AssertionError("must not call model");
        });
        PlanResponse missing = engine.plan(new PlanRequest("上海", "杭州", LocalDate.of(2026, 10, 1), 3,
                3000, 700, "", List.of("不存在景点"), 2));
        PlanResponse poor = engine.plan(new PlanRequest("上海", "杭州", LocalDate.of(2026, 10, 1), 3,
                100, 700, "", 2));

        assertThat(calls).hasValue(0);
        assertThat(missing.terminalState()).isEqualTo(PlanningTerminalState.INFEASIBLE);
        assertThat(missing.stopReport().guardCode()).isEqualTo("P1_MUST_VISIT");
        assertThat(poor.terminalState()).isEqualTo(PlanningTerminalState.INFEASIBLE);
        assertThat(poor.stopReport().guardCode()).isEqualTo("P3_MINIMUM_BUDGET");
    }

    @Test
    void noProgressWinsWhenAllGuardsWouldStop() {
        LoopGuardrail guard = new LoopGuardrail(new LoopGuardProperties(100, Duration.ofMillis(100), 1.2, 2));
        PlanningLedgerSnapshot snapshot = new PlanningLedgerSnapshot(List.of(
                new PlanningLedgerEntry(1, 100, 100, "C6:灵隐寺缺失"),
                new PlanningLedgerEntry(2, 100, 100, "C6:灵隐寺缺失")));

        assertThat(guard.beforeRound(snapshot, 2)).get()
                .extracting(PlanningStopReport::guardCode).isEqualTo("G1_NO_PROGRESS");
    }

    @Test
    void changingEvidenceAllowsTokenGuardToUseProjectedEstimate() {
        LoopGuardrail guard = new LoopGuardrail(new LoopGuardProperties(20_000, Duration.ofSeconds(30), 1.2, 2));
        PlanningLedgerSnapshot snapshot = new PlanningLedgerSnapshot(List.of(
                new PlanningLedgerEntry(1, 4_500, 2_000, "C1:超预算 1205 元"),
                new PlanningLedgerEntry(2, 5_200, 2_000, "C1:超预算 760 元"),
                new PlanningLedgerEntry(3, 6_100, 2_000, "C1:超预算 320 元")));

        PlanningStopReport report = guard.beforeRound(snapshot, 5).orElseThrow();
        assertThat(report.guardCode()).isEqualTo("G2_TOKEN_BUDGET");
        assertThat(report.evidence()).isEqualTo("15800+7320>20000");
    }

    @Test
    void transientFailuresRetryTwiceAndSuccessStillProducesOneResult() {
        AtomicInteger attempts = new AtomicInteger();
        TechnicalRetryExecutor retry = new TechnicalRetryExecutor(millis -> {});
        PlanGenerator generator = input -> {
            if (attempts.incrementAndGet() < 3) throw new ModelCallException("HTTP 503", null, true);
            return PlanGenerationResult.success(TestTripPlans.complete(3), "scripted", 10);
        };

        PlanGenerationResult result = retry.execute(generator, input());
        assertThat(attempts).hasValue(3);
        assertThat(result.plan()).isNotNull();
    }

    @Test
    void exhaustedTransientRetriesPropagateAsModelFailure() {
        AtomicInteger attempts = new AtomicInteger();
        TechnicalRetryExecutor retry = new TechnicalRetryExecutor(millis -> {});
        PlanGenerator generator = input -> {
            attempts.incrementAndGet();
            throw new ModelCallException("HTTP 429", null, true);
        };

        assertThatThrownBy(() -> retry.execute(generator, input())).isInstanceOf(ModelCallException.class);
        assertThat(attempts).hasValue(3);
    }

    private TravelPlanningEngine engine(PlanGenerator generator) {
        TravelDataService data = facts();
        return new TravelPlanningEngine(generator, new BasicContractReview(), TestTripPlans.constraintReviewer(),
                new InMemoryPlanningEventSink(), new TravelPlanPreflight(data),
                new LoopGuardrail(new LoopGuardProperties(20_000, Duration.ofSeconds(30), 1.2, 2)),
                new TechnicalRetryExecutor(millis -> {}));
    }

    private TravelDataService facts() {
        TravelDataService data = new TravelDataService(new ObjectMapper().findAndRegisterModules(),
                new DefaultResourceLoader());
        data.loadFacts();
        return data;
    }

    private PlanGenerationInput input() {
        PlanRequest request = new PlanRequest("上海", "杭州", LocalDate.of(2026, 10, 1), 3,
                3000, 700, "", 2);
        return new PlanGenerationInput(request, 1, null, List.of());
    }
}
