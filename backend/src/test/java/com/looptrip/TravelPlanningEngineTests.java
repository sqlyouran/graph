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
    void maxRoundsReturnsRoundWithFewestProblemsAndExplicitStopReason() {
        PlanGenerator generator = input -> switch (input.round()) {
            case 1 -> new PlanGenerationResult(TestTripPlans.complete(3), "round-1", 1,
                    List.of("问题一", "问题二", "问题三"));
            case 2 -> new PlanGenerationResult(TestTripPlans.complete(3), "round-2", 1,
                    List.of("仅剩问题"));
            default -> new PlanGenerationResult(TestTripPlans.complete(3), "round-3", 1,
                    List.of("问题甲", "问题乙"));
        };

        PlanResponse result = engine(generator, new InMemoryPlanningEventSink()).plan(request(3));

        assertThat(result.status()).isEqualTo(PlanStatus.MAX_ROUNDS);
        assertThat(result.model()).isEqualTo("round-2");
        assertThat(result.problems()).containsExactly("仅剩问题");
        assertThat(result.stopReason()).isEqualTo(
                "达到最大轮次（3 轮），仍有 1 个未解决问题；返回问题最少的第 2 轮");
        assertThat(result.rounds()).hasSize(3);
    }

    @Test
    void onlyHardConstraintFailuresBecomeNextRoundFeedback() {
        List<PlanGenerationInput> inputs = new java.util.ArrayList<>();
        PlanGenerator generator = input -> {
            inputs.add(input);
            return PlanGenerationResult.success(TestTripPlans.complete(3), "scripted", 1);
        };
        PlanRequest request = new PlanRequest("上海", "杭州", LocalDate.of(2026, 10, 1), 3,
                3000, 700, "轻松", List.of("灵隐寺"), 2);

        PlanResponse result = engine(generator, new InMemoryPlanningEventSink()).plan(request);

        assertThat(result.status()).isEqualTo(PlanStatus.MAX_ROUNDS);
        assertThat(inputs.get(1).feedbackProblems())
                .allMatch(problem -> problem.startsWith("C6 必去景点："))
                .noneMatch(problem -> problem.startsWith("C5") || problem.startsWith("C7"));
        assertThat(result.rounds().get(0).constraintResults())
                .filteredOn(item -> item.severity() == ConstraintSeverity.SOFT && !item.passed())
                .isNotEmpty();
        assertThat(result.rounds().get(1).feedbackReceived())
                .containsExactlyElementsOf(result.rounds().get(0).problems());
    }

    @Test
    void structuredParseProblemEntersFeedbackLoop() {
        List<PlanGenerationInput> inputs = new java.util.ArrayList<>();
        PlanGenerator generator = input -> {
            inputs.add(input);
            if (input.round() == 1) {
                return new PlanGenerationResult(null, "scripted", 1, List.of(PlanChatService.PARSE_FAILURE));
            }
            return PlanGenerationResult.success(TestTripPlans.complete(3), "scripted", 1);
        };

        PlanResponse result = engine(generator, new InMemoryPlanningEventSink()).plan(request(2));

        assertThat(result.status()).isEqualTo(PlanStatus.COMPLETED);
        assertThat(inputs).hasSize(2);
        assertThat(inputs.get(1).feedbackProblems()).containsExactly(PlanChatService.PARSE_FAILURE);
        assertThat(result.rounds().get(0).plan()).isNull();
        assertThat(result.rounds().get(1).plan()).isNotNull();
    }

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
        PlanGenerator generator = input -> PlanGenerationResult.success(TestTripPlans.complete(1), "scripted", 1);
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
        assertThat(result.problems()).isEmpty();
        assertThat(result.rounds()).singleElement().satisfies(round -> {
            assertThat(round.events()).isEmpty();
            assertThat(round.problems()).isEmpty();
        });
        assertThat(result.rounds().get(0).constraintResults()).filteredOn(item -> item.code().equals("C7"))
                .singleElement().satisfies(item -> {
                    assertThat(item.severity()).isEqualTo(ConstraintSeverity.SOFT);
                    assertThat(item.passed()).isFalse();
                });
    }

    private TravelPlanningEngine engine(PlanGenerator generator, PlanningEventSink sink) {
        return new TravelPlanningEngine(generator, new BasicContractReview(), TestTripPlans.constraintReviewer(), sink);
    }

    private PlanRequest request(int maxRounds) {
        return new PlanRequest("上海", "杭州", LocalDate.of(2026, 10, 1), 3, 3000, 700, "轻松", maxRounds);
    }
}
