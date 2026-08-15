package com.looptrip;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class PlanControllerTests {

    @Mock
    private PlanChatService planChatService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new PlanController(planChatService))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void returnsPlanForValidQuestion() throws Exception {
        when(planChatService.ask("规划杭州三日游"))
                .thenReturn(new AskResponse("## 杭州三日游", "qwen-test", 321));

        mockMvc.perform(post("/api/plan/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"规划杭州三日游\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("## 杭州三日游"))
                .andExpect(jsonPath("$.model").value("qwen-test"))
                .andExpect(jsonPath("$.elapsedMs").value(321));
    }

    @Test
    void returnsBadRequestForBlankQuestion() throws Exception {
        when(planChatService.ask(" "))
                .thenThrow(new PlanValidationException("question must not be blank"));

        mockMvc.perform(post("/api/plan/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("question must not be blank"));
    }

    @Test
    void returnsBadGatewayForModelFailure() throws Exception {
        when(planChatService.ask("规划杭州三日游"))
                .thenThrow(new ModelCallException("模型调用失败，请稍后重试"));

        mockMvc.perform(post("/api/plan/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"规划杭州三日游\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.errorCode").value("MODEL_CALL_FAILED"))
                .andExpect(jsonPath("$.message").value("模型调用失败，请稍后重试"));
    }

    @Test
    void returnsBadRequestForMalformedJson() throws Exception {
        mockMvc.perform(post("/api/plan/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("request body must contain question"));
    }
}
