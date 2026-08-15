package com.looptrip;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlanChatServiceTests {

    @Mock
    private ChatClient.Builder chatClientBuilder;

    @Mock
    private ChatClient chatClient;

    @Mock
    private ChatClient.ChatClientRequestSpec requestSpec;

    @Mock
    private ChatClient.CallResponseSpec responseSpec;

    private PlanChatService service;

    @BeforeEach
    void setUp() {
        when(chatClientBuilder.build()).thenReturn(chatClient);
        service = new PlanChatService(chatClientBuilder, "qwen-test");
    }

    @Test
    void rejectsBlankQuestionWithoutCallingModel() {
        assertThatThrownBy(() -> service.ask("  "))
                .isInstanceOf(PlanValidationException.class)
                .hasMessage("question must not be blank");

        verifyNoInteractions(chatClient);
    }

    @Test
    void returnsAnswerModelAndElapsedTime() {
        stubResponse("## 行程\n\n从西湖开始。 ");

        AskResponse response = service.ask("规划杭州三日游");

        assertThat(response.answer()).isEqualTo("## 行程\n\n从西湖开始。 ");
        assertThat(response.model()).isEqualTo("qwen-test");
        assertThat(response.elapsedMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void treatsBlankModelContentAsModelFailure() {
        stubResponse("  ");

        assertThatThrownBy(() -> service.ask("规划杭州三日游"))
                .isInstanceOf(ModelCallException.class)
                .hasMessage("模型返回了空回答");
    }

    @Test
    void wrapsModelCallFailure() {
        when(chatClient.prompt()).thenThrow(new IllegalStateException("upstream unavailable"));

        assertThatThrownBy(() -> service.ask("规划杭州三日游"))
                .isInstanceOf(ModelCallException.class)
                .hasMessage("模型调用失败，请稍后重试")
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    private void stubResponse(String content) {
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn(content);
    }
}
