package com.looptrip;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlanningIntentRecognizerTests {
    @Mock private ChatClient.Builder builder;
    @Mock private ChatClient chat;
    @Mock private ChatClient.ChatClientRequestSpec requestSpec;
    @Mock private ChatClient.CallResponseSpec responseSpec;

    private CascadingPlanningIntentRecognizer recognizer;

    @BeforeEach
    void setUp() {
        when(builder.build()).thenReturn(chat);
        recognizer = new CascadingPlanningIntentRecognizer(builder);
    }

    @Test
    void relativeBudgetPhraseYieldsReviseBudgetWithoutSlots() {
        PlanningIntent intent = recognizer.recognize("太贵了", null);
        assertThat(intent.action()).isEqualTo(PlanningIntentAction.REVISE_BUDGET);
        assertThat(intent.slots()).doesNotContainKey("budget");
    }

    @Test
    void relativeHotelPhraseYieldsReviseHotelPriceWithoutSlots() {
        PlanningIntent intent = recognizer.recognize("酒店太贵", null);
        assertThat(intent.action()).isEqualTo(PlanningIntentAction.REVISE_HOTEL_PRICE);
        assertThat(intent.slots()).doesNotContainKey("maxHotelPrice");
    }

    @Test
    void fixedCancelPhraseHitsRuleWithoutModelCall() {
        PlanningIntent intent = recognizer.recognize("算了不弄了", null);
        assertThat(intent.action()).isEqualTo(PlanningIntentAction.CANCEL);
        verifyNoInteractions(chat);
    }

    @Test
    void rollbackPhrasesHitRuleWithAndWithoutVersion() {
        assertThat(recognizer.recognize("还是上一版好", null).action()).isEqualTo(PlanningIntentAction.ROLLBACK);
        assertThat(recognizer.recognize("回滚到 2", null).slots()).containsEntry("rollbackVersion", 2);
        verifyNoInteractions(chat);
    }

    @Test
    void inventedEnumFallsBackToUnknownWithoutException() {
        stubModelJson("{\"action\":\"REVISE_PRICE\",\"slots\":{}}");
        assertThat(recognizer.recognize("随便改改", null).action()).isEqualTo(PlanningIntentAction.UNKNOWN);
    }

    @Test
    void invalidSlotTypeFallsBackToUnknownWithoutException() {
        stubModelJson("{\"action\":\"REVISE_BUDGET\",\"slots\":{\"budget\":\"便宜一点\"}}");
        assertThat(recognizer.recognize("便宜一点", null).action()).isEqualTo(PlanningIntentAction.UNKNOWN);
    }

    @Test
    void missingRequiredSlotSurvivesRecognitionAndGateClarifies() {
        stubModelJson("{\"action\":\"REVISE_BUDGET\",\"slots\":{},\"echo\":\"我理解成：希望降低总预算。\"}");
        PlanningIntent intent = recognizer.recognize("整体开销能不能压缩一下", null);
        assertThat(intent.action()).isEqualTo(PlanningIntentAction.REVISE_BUDGET);
        assertThat(new PlanningIntentGate().decide(intent, false, null).decision())
                .isEqualTo(PlanningChatDecision.CLARIFICATION_REQUIRED);
    }

    @Test
    void modelStubbedAdjustPaceHits() {
        stubModelJson("{\"action\":\"ADJUST_PACE\",\"slots\":{\"preferences\":\"轻松一点\"},\"echo\":\"我理解成：把节奏放慢。\"}");
        PlanningIntent intent = recognizer.recognize("行程太赶了想慢点", null);
        assertThat(intent.action()).isEqualTo(PlanningIntentAction.ADJUST_PACE);
        assertThat(intent.slots()).containsEntry("preferences", "轻松一点");
    }

    @Test
    void modelStubbedAddMustVisitHits() {
        stubModelJson("{\"action\":\"ADD_MUST_VISIT\",\"slots\":{\"mustVisit\":[\"雷峰塔\"]},\"echo\":\"我理解成：增加必去景点雷峰塔。\"}");
        PlanningIntent intent = recognizer.recognize("顺便把雷峰塔加进去", null);
        assertThat(intent.action()).isEqualTo(PlanningIntentAction.ADD_MUST_VISIT);
        assertThat(intent.slots().get("mustVisit")).isEqualTo(java.util.List.of("雷峰塔"));
    }

    @Test
    void modelStubbedNewSessionHits() {
        stubModelJson("{\"action\":\"NEW_SESSION\",\"slots\":{\"destination\":\"南京\"},\"echo\":\"我理解成：改去南京，需要重新规划。\"}");
        PlanningIntent intent = recognizer.recognize("改去南京吧", null);
        assertThat(intent.action()).isEqualTo(PlanningIntentAction.NEW_SESSION);
    }

    @Test
    void modelStubbedAskQuestionHits() {
        stubModelJson("{\"action\":\"ASK_QUESTION\",\"slots\":{\"question\":\"雷峰塔几点关门\"},\"echo\":\"我理解成：询问雷峰塔的闭馆时间。\"}");
        PlanningIntent intent = recognizer.recognize("雷峰塔几点关门", null);
        assertThat(intent.action()).isEqualTo(PlanningIntentAction.ASK_QUESTION);
    }

    private void stubModelJson(String json) {
        when(chat.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.content()).thenReturn(json);
    }
}
