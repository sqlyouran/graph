package com.looptrip;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class Chapter4FeedbackReplayTest {

    @Test
    void success() {
        Chapter4ScriptedPlanningPolicy policy = policy(Chapter4ScriptedPlanningPolicy.Scenario.SUCCESS);
        PlanResponse result = engine(policy).plan(request(3));

        assertThat(result.status()).isEqualTo(PlanStatus.COMPLETED);
        assertThat(result.roundsUsed()).isEqualTo(1);
        assertThat(result.rounds()).singleElement().satisfies(round -> assertThat(round.review().passed()).isTrue());
        print(result, policy);
    }

    @Test
    void feedback() {
        Chapter4ScriptedPlanningPolicy policy = policy(Chapter4ScriptedPlanningPolicy.Scenario.FEEDBACK);
        PlanResponse result = engine(policy).plan(request(3));

        assertThat(result.status()).isEqualTo(PlanStatus.COMPLETED);
        assertThat(result.roundsUsed()).isEqualTo(2);
        assertThat(result.rounds().get(0).review().problems()).contains("缺少第 3 天安排");
        assertThat(result.rounds().get(1).feedbackReceived()).containsExactly("缺少第 3 天安排");
        assertThat(policy.inputs().get(1).originalRequest()).isEqualTo(request(3));
        assertThat(policy.inputs().get(1).previousMarkdown()).isEqualTo(result.rounds().get(0).markdown());
        assertThat(policy.secondRoundReceivedFeedback()).isTrue();
        assertThat(result.rounds().get(1).review().passed()).isTrue();
        print(result, policy);
    }

    @Test
    void noFeedback() {
        Chapter4ScriptedPlanningPolicy policy = policy(Chapter4ScriptedPlanningPolicy.Scenario.NO_FEEDBACK);
        PlanResponse result = engine(policy).plan(request(2));

        assertThat(result.status()).isEqualTo(PlanStatus.MAX_ROUNDS);
        assertThat(result.roundsUsed()).isEqualTo(2);
        assertThat(result.rounds()).allSatisfy(round ->
                assertThat(round.review().problems()).contains("缺少第 3 天安排"));
        assertThat(policy.secondRoundReceivedFeedback()).isFalse();
        assertThat(result.problems()).contains("缺少第 3 天安排");
        print(result, policy);
    }

    @Test
    void maxRounds() {
        Chapter4ScriptedPlanningPolicy policy = policy(Chapter4ScriptedPlanningPolicy.Scenario.MAX_ROUNDS);
        PlanResponse result = engine(policy).plan(request(1));

        assertThat(result.status()).isEqualTo(PlanStatus.MAX_ROUNDS);
        assertThat(result.roundsUsed()).isEqualTo(1);
        assertThat(policy.inputs()).hasSize(1);
        assertThat(result.answer()).isEqualTo(result.rounds().get(0).markdown());
        assertThat(result.problems()).contains("缺少第 3 天安排");
        print(result, policy);
    }

    private TravelPlanningEngine engine(PlanGenerator generator) {
        return new TravelPlanningEngine(generator, new BasicContractReview(), new InMemoryPlanningEventSink());
    }

    private Chapter4ScriptedPlanningPolicy policy(Chapter4ScriptedPlanningPolicy.Scenario scenario) {
        return new Chapter4ScriptedPlanningPolicy(scenario);
    }

    private PlanRequest request(int maxRounds) {
        return new PlanRequest("上海", "杭州", LocalDate.of(2026, 10, 1), 3, 3000, 700, "轻松", maxRounds);
    }

    private void print(PlanResponse result, Chapter4ScriptedPlanningPolicy policy) {
        for (PlanningRoundSnapshot round : result.rounds()) {
            System.out.println("── 第 " + round.round() + " 轮 ─────────────────────────────");
            if (round.round() >= 2) {
                System.out.println("第 " + round.round() + " 轮收到上一轮反馈 = " + policy.secondRoundReceivedFeedback());
            }
            System.out.println("生成候选（脚本化替身）");
            if (round.review().passed()) {
                System.out.println("✅ 基础契约通过");
            } else {
                System.out.println("❌ 基础契约未通过：" + String.join("，", round.review().problems()));
                if (round.round() < result.roundsUsed()) {
                    System.out.println("带 " + round.review().problems().size() + " 条问题进入下一轮");
                }
            }
        }
        if (result.status() == PlanStatus.MAX_ROUNDS) {
            System.out.println("🟠 已达最大轮次，返回当前最好版本");
        }
        System.out.println("最终状态 = " + result.status());
        System.out.println("执行轮次 = " + result.roundsUsed());
        System.out.println("未解决问题 = " + result.problems());
    }
}
