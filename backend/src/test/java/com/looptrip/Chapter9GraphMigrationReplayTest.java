package com.looptrip;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import static org.assertj.core.api.Assertions.assertThat;

/** 第 9 章第 2 节课本回放：三条业务轨迹固定打印，不依赖真实模型随机输出。 */
class Chapter9GraphMigrationReplayTest {

    @Test
    void replayThreeBusinessPaths() {
        PlanResponse onePass = runOnce();
        PlanResponse rework = runRework();
        PlanResponse infeasible = runPreflightFailure();

        System.out.println("[轨迹1] 一次通过：START → preflight → generate → validate → END | 终态="
                + onePass.terminalState() + " | 业务轮次=" + onePass.rounds().size() + " | 模型调用=1");
        System.out.println("[轨迹2] 返工一次：START → preflight → generate → validate → generate → validate → END | 终态="
                + rework.terminalState() + " | 业务轮次=" + rework.rounds().size() + " | 模型调用=2");
        System.out.println("[轨迹3] 预检失败：START → preflight → PREFLIGHT_FAILED | 终态="
                + infeasible.terminalState() + " | 业务轮次=" + infeasible.rounds().size() + " | 模型调用=0");

        assertThat(onePass.terminalState()).isEqualTo(PlanningTerminalState.SUCCESS);
        assertThat(onePass.rounds()).hasSize(1);

        assertThat(rework.terminalState()).isEqualTo(PlanningTerminalState.SUCCESS);
        assertThat(rework.rounds()).hasSize(2);
        assertThat(rework.rounds().get(0).problems()).containsExactly("缺少返程安排");

        assertThat(infeasible.terminalState()).isEqualTo(PlanningTerminalState.UNSUPPORTED);
        assertThat(infeasible.rounds()).isEmpty();
        assertThat(infeasible.plan()).isNull();
    }

    private PlanResponse runOnce() {
        AtomicInteger calls = new AtomicInteger();
        TravelPlanningEngine engine = engine(input -> {
            calls.incrementAndGet();
            return PlanGenerationResult.success(TestTripPlans.complete(3), "replay", 1);
        });
        PlanResponse response = engine.plan(request("杭州"));
        assertThat(calls).hasValue(1);
        return response;
    }

    private PlanResponse runRework() {
        AtomicInteger calls = new AtomicInteger();
        TravelPlanningEngine engine = engine(input -> {
            calls.incrementAndGet();
            if (input.round() == 1) {
                return new PlanGenerationResult(TestTripPlans.complete(3), "replay", 1, List.of("缺少返程安排"));
            }
            return PlanGenerationResult.success(TestTripPlans.complete(3), "replay", 1);
        });
        PlanResponse response = engine.plan(request("杭州"));
        assertThat(calls).hasValue(2);
        return response;
    }

    private PlanResponse runPreflightFailure() {
        AtomicInteger calls = new AtomicInteger();
        TravelPlanningEngine engine = engine(input -> {
            calls.incrementAndGet();
            return PlanGenerationResult.success(TestTripPlans.complete(3), "replay", 1);
        });
        PlanResponse response = engine.plan(request("火星"));
        assertThat(calls).hasValue(0);
        return response;
    }

    private TravelPlanningEngine engine(PlanGenerator generator) {
        TravelDataService data = new TravelDataService(
                new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule()),
                new DefaultResourceLoader());
        data.loadFacts();
        return new TravelPlanningEngine(generator, new BasicContractReview(), TestTripPlans.constraintReviewer(),
                new InMemoryPlanningEventSink(), new TravelPlanPreflight(data), null,
                new TechnicalRetryExecutor(millis -> { }), () -> false);
    }

    private PlanRequest request(String destination) {
        return new PlanRequest("上海", destination, LocalDate.of(2026, 10, 1), 3,
                3000, 700, "轻松", 3);
    }
}
