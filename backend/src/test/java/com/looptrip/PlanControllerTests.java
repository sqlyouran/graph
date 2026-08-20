package com.looptrip;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class PlanControllerTests {

    private static final String VALID_REQUEST = """
            {"origin":"上海","destination":"杭州","startDate":"2026-10-01","days":3,
             "budget":3000,"maxHotelPrice":700,"preferences":"轻松","maxRounds":2}
            """;

    @Mock private TravelPlanningEngine planningEngine;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new PlanController(planningEngine))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void returnsLoopResultForValidRequest() throws Exception {
        when(planningEngine.plan(any())).thenReturn(new PlanResponse(
                "## 行程", "qwen-test", 321, PlanStatus.COMPLETED, "基础契约通过", 1,
                List.of(), List.of(), List.of()));

        mockMvc.perform(post("/api/plan/ask").contentType(MediaType.APPLICATION_JSON).content(VALID_REQUEST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("## 行程"))
                .andExpect(jsonPath("$.model").value("qwen-test"))
                .andExpect(jsonPath("$.elapsedMs").value(321))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.roundsUsed").value(1));
    }

    @Test
    void returnsBadRequestForInvalidParameters() throws Exception {
        when(planningEngine.plan(any())).thenThrow(new PlanValidationException("maxRounds must be between 1 and 5"));
        mockMvc.perform(post("/api/plan/ask").contentType(MediaType.APPLICATION_JSON).content(VALID_REQUEST))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"));
    }

    @Test
    void returnsBadGatewayForModelFailure() throws Exception {
        when(planningEngine.plan(any())).thenThrow(new ModelCallException("模型调用失败，请稍后重试"));
        mockMvc.perform(post("/api/plan/ask").contentType(MediaType.APPLICATION_JSON).content(VALID_REQUEST))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.errorCode").value("MODEL_CALL_FAILED"));
    }

    @Test
    void returnsBadRequestForMalformedJson() throws Exception {
        mockMvc.perform(post("/api/plan/ask").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.message").value("request body is missing or malformed"));
    }
}
