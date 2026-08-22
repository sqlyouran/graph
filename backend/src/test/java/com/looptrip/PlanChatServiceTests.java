package com.looptrip;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlanChatServiceTests {

    @Mock private ChatClient.Builder chatClientBuilder;
    @Mock private ChatClient chatClient;
    @Mock private ChatClient.ChatClientRequestSpec requestSpec;
    @Mock private ChatClient.CallResponseSpec responseSpec;
    @Mock private TravelTools travelTools;

    private PlanChatService service;

    @BeforeEach
    void setUp() {
        when(chatClientBuilder.defaultTools(travelTools)).thenReturn(chatClientBuilder);
        when(chatClientBuilder.build()).thenReturn(chatClient);
        service = new PlanChatService(chatClientBuilder, travelTools, "qwen-test");
    }

    @Test
    void generatesOneCandidateWithModelAndElapsedTime() {
        TripPlan plan = TestTripPlans.complete(3);
        stubResponse(plan);

        PlanGenerationResult response = service.generate(new PlanGenerationInput(request(), 1, null, List.of()));

        assertThat(response.plan()).isEqualTo(plan);
        assertThat(response.problems()).isEmpty();
        assertThat(response.model()).isEqualTo("qwen-test");
        assertThat(response.elapsedMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void revisionPromptContainsAllFourRequiredSections() {
        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(userPrompt.capture())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.entity(TripPlan.class)).thenReturn(TestTripPlans.complete(3));

        service.generate(new PlanGenerationInput(
                request(),
                2,
                TestTripPlans.missingLastDay(),
                List.of("缺少第 3 天安排", "缺少住宿安排")));

        assertThat(userPrompt.getValue())
                .contains("【原始需求】", "从上海出发，前往杭州")
                .contains("【上一版完整 TripPlan】", "\"destination\":\"杭州\"")
                .contains("【上一轮全部问题】", "缺少第 3 天安排", "缺少住宿安排")
                .contains("【修订要求】", "保留已合格部分", "完整修订版")
                .contains("【上一轮全部问题】\n缺少第 3 天安排\n缺少住宿安排\n\n【修订要求】")
                .doesNotContain("- 缺少第 3 天安排");
        assertThat(userPrompt.getValue().indexOf("【原始需求】"))
                .isLessThan(userPrompt.getValue().indexOf("【上一版完整 TripPlan】"));
        assertThat(userPrompt.getValue().indexOf("【上一版完整 TripPlan】"))
                .isLessThan(userPrompt.getValue().indexOf("【上一轮全部问题】"));
        assertThat(userPrompt.getValue().indexOf("【上一轮全部问题】"))
                .isLessThan(userPrompt.getValue().indexOf("【修订要求】"));
    }

    @Test
    void systemPromptEnforcesFactDisciplineAndOutputContract() {
        assertThat(PlanChatService.SYSTEM_PROMPT)
                .contains("必须来自对应工具")
                .contains("该城市/日期暂无数据")
                .contains("不得用常识")
                .contains("不要计算或返回总花费");
    }

    @Test
    void returnsRoundProblemWhenStructuredParsingFails() {
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.entity(TripPlan.class)).thenThrow(new IllegalArgumentException("invalid json"));

        PlanGenerationResult result = service.generate(new PlanGenerationInput(request(), 1, null, List.of()));

        assertThat(result.plan()).isNull();
        assertThat(result.problems()).containsExactly(PlanChatService.PARSE_FAILURE);
    }

    @Test
    void wrapsModelCallFailureWithoutFallback() {
        when(chatClient.prompt()).thenThrow(new IllegalStateException("upstream unavailable"));
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.generate(new PlanGenerationInput(request(), 1, null, List.of())))
                .isInstanceOf(ModelCallException.class).hasMessage("模型调用失败，请稍后重试")
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    private void stubResponse(TripPlan plan) {
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.entity(TripPlan.class)).thenReturn(plan);
    }

    private PlanRequest request() {
        return new PlanRequest("上海", "杭州", LocalDate.of(2026, 10, 1), 3, 3000, 700, "轻松", 2);
    }
}
