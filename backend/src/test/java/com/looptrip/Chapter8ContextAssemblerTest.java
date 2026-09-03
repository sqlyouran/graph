package com.looptrip;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 第 8-2 节：预算意识组装器的契约——五段排布、淘汰级联、返工意见永不裁、
 * 画像过滤（条件不匹配 / 显式字段覆盖），以及 usableBudget 与 estimatedTokens 的算术。
 */
class Chapter8ContextAssemblerTest {

    private final ContextAssembler assembler = new ContextAssembler();

    @Test
    void normalBudgetKeepsAllSectionsAndStructuredSummary() {
        PromptContext context = assemble(round2Input(), UserProfile.empty(), 6000, 0.15);

        assertThat(context.usableBudget()).isEqualTo(5100);
        assertThat(context.droppedSections()).isEmpty();
        assertThat(context.includedSections())
                .containsExactly("INSTRUCTION", "CONSTRAINT", "HISTORY", "PROFILE");
        assertThat(context.userPrompt())
                .contains("### SECTION:CONSTRAINT", "### SECTION:HISTORY", "### SECTION:PROFILE")
                .contains("【上一版摘要】", "destination=杭州", "days=3", "startDate=2026-10-01",
                        "outboundFlight=MU5211", "returnFlight=MU5212",
                        "hotels=湖滨课程酒店", "attractions=西湖风景名胜区")
                .doesNotContain("dailyPlans", "\"destination\":\"杭州\"")
                .contains("【本轮任务】");
        assertThat(context.systemPrompt()).contains("### SECTION:INSTRUCTION");
    }

    @Test
    void tightBudgetDropsProfileThenDegradesHistory() {
        PromptContext context = assemble(round2Input(), UserProfile.empty(), 500, 0.15);

        assertThat(context.usableBudget()).isEqualTo(425);
        assertThat(context.droppedSections().stream().map(PromptContext.DroppedSection::toString))
                .contains("profile: 剩余预算不足", "history-detail: 超出剩余预算");
        assertThat(context.includedSections()).doesNotContain("PROFILE");
        assertThat(context.userPrompt())
                .contains("【上一版摘要（最小）】", "destination=杭州", "days=3")
                .doesNotContain("hotels=", "attractions=", "用户画像");
    }

    @Test
    void instructionsConstraintsAndFeedbackSurviveAnyBudget() {
        PromptContext context = assemble(round2Input(), UserProfile.empty(), 100, 0.15);

        assertThat(context.systemPrompt()).contains("### SECTION:INSTRUCTION", "必须来自对应工具");
        assertThat(context.userPrompt())
                .contains("【原始需求】", "从上海出发，前往杭州", "总预算3000元")
                .contains("【上一轮全部问题】", "缺少第 3 天安排", "缺少住宿安排")
                .contains("【修订要求】", "完整修订版")
                .contains("【本轮任务】", "必去景点：西湖、灵隐寺");
        assertThat(context.estimatedTokens()).isGreaterThan(context.usableBudget());
    }

    @Test
    void mismatchedOrOverriddenPreferencesNeverEnterPrompt() {
        UserProfile profile = new UserProfile("u-1", List.of(
                new Preference("苏州", null, "偏爱园林景点"),
                new Preference("杭州", "budget", "他通常花 2500 到 3000 元"),
                new Preference("杭州", null, "喜欢靠窗座位")));

        assertThat(profile.relevantPreferences(request()))
                .extracting(Preference::text)
                .containsExactly("喜欢靠窗座位");

        PromptContext context = assemble(round2Input(), profile, 6000, 0.15);
        assertThat(context.userPrompt())
                .contains("喜欢靠窗座位")
                .doesNotContain("偏爱园林景点", "2500 到 3000");
    }

    @Test
    void firstRoundTightBudgetDropsProfileOnlyWithoutHistoryDetail() {
        PromptContext context = assemble(
                new PlanGenerationInput(request(), 1, null, List.of()), UserProfile.empty(), 400, 0.15);

        assertThat(context.usableBudget()).isEqualTo(340);
        assertThat(context.droppedSections().stream().map(PromptContext.DroppedSection::toString))
                .containsExactly("profile: 剩余预算不足");
        assertThat(context.userPrompt()).doesNotContain("### SECTION:HISTORY", "用户画像");
    }

    @Test
    void firstRoundCarriesNoHistorySection() {
        PromptContext context = assemble(
                new PlanGenerationInput(request(), 1, null, List.of()), UserProfile.empty(), 6000, 0.15);

        assertThat(context.includedSections()).doesNotContain("HISTORY");
        assertThat(context.userPrompt()).doesNotContain("### SECTION:HISTORY", "【上一版摘要】");
        assertThat(context.userPrompt()).contains("### SECTION:PROFILE", "用户画像：暂无");
    }

    @Test
    void estimatedTokensFollowsCeilCharsOverOnePointFive() {
        PromptContext context = assemble(round2Input(), UserProfile.empty(), 6000, 0.15);

        int expected = (int) Math.ceil(
                (context.systemPrompt() + "\n" + context.userPrompt()).length() / 1.5);
        assertThat(context.estimatedTokens()).isEqualTo(expected);
    }

    private PromptContext assemble(PlanGenerationInput input, UserProfile profile,
            int tokenBudget, double safetyMargin) {
        return assembler.assemble(input, profile, tokenBudget, safetyMargin);
    }

    private PlanGenerationInput round2Input() {
        return new PlanGenerationInput(request(), 2, TestTripPlans.missingLastDay(),
                List.of("缺少第 3 天安排", "缺少住宿安排"));
    }

    private PlanRequest request() {
        return new PlanRequest("上海", "杭州", LocalDate.of(2026, 10, 1), 3,
                3000, 700, "轻松", List.of("西湖", "灵隐寺"), 2);
    }
}
