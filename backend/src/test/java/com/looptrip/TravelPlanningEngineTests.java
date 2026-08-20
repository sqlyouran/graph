package com.looptrip;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TravelPlanningEngineTests {

    @Test
    void rejectsInvalidRoundLimitBeforeGeneration() {
        PlanGenerator generator = input -> {
            throw new AssertionError("generator must not be called");
        };

        assertThatThrownBy(() -> engine(generator, new InMemoryPlanningEventSink()).plan(request(0)))
                .isInstanceOf(PlanValidationException.class)
                .hasMessage("maxRounds must be between 1 and 5");
    }

    @Test
    void modelFailureIsPropagatedWithoutFallback() {
        PlanGenerator generator = input -> {
            throw new ModelCallException("模型调用失败，请稍后重试");
        };

        assertThatThrownBy(() -> engine(generator, new InMemoryPlanningEventSink()).plan(request(2)))
                .isInstanceOf(ModelCallException.class)
                .hasMessage("模型调用失败，请稍后重试");
    }

    @Test
    void eventFailuresDoNotInterruptPlanning() {
        PlanGenerator generator = input -> new PlanGenerationResult("""
                ## 第 1 天
                参观景点并入住酒店。
                预计总花费 1000 元
                """, "scripted", 1);
        PlanningEventSink failingSink = new PlanningEventSink() {
            @Override
            public <T> T capture(List<PlanningEvent> events, Supplier<T> action) {
                return action.get();
            }

            @Override
            public void setRound(int round) {
                throw new IllegalStateException("event sink unavailable");
            }

            @Override
            public void emit(PlanningEventType type, String message, Map<String, Object> details) {
                throw new IllegalStateException("event sink unavailable");
            }
        };

        PlanResponse result = engine(generator, failingSink).plan(new PlanRequest(
                "上海", "杭州", LocalDate.of(2026, 10, 1), 1, 3000, 700, "轻松", 1));

        assertThat(result.status()).isEqualTo(PlanStatus.COMPLETED);
        assertThat(result.events()).isEmpty();
    }

    private TravelPlanningEngine engine(PlanGenerator generator, PlanningEventSink sink) {
        return new TravelPlanningEngine(generator, new BasicContractReview(), sink);
    }

    private PlanRequest request(int maxRounds) {
        return new PlanRequest("上海", "杭州", LocalDate.of(2026, 10, 1), 3, 3000, 700, "轻松", maxRounds);
    }
}
