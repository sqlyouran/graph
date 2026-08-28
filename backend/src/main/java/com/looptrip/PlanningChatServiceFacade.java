package com.looptrip;

import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class PlanningChatServiceFacade {
    private final PlanningIntentRecognizer recognizer;
    private final PlanningQuestionAnswerer answerer;
    public PlanningChatServiceFacade(PlanningIntentRecognizer recognizer, PlanningQuestionAnswerer answerer) {
        this.recognizer = recognizer; this.answerer = answerer;
    }
    public PlanningIntent recognize(String utterance, PlanningSession session) { return recognizer.recognize(utterance, session); }
    public PlanningChatResponse question(PlanningIntent intent, PlanningSession session) {
        String answer = answerer.answer(String.valueOf(intent.slots().getOrDefault("question", intent.utterance())), session);
        return new PlanningChatResponse(intent.action(), intent.slots(), intent.echo(), PlanningChatDecision.ANSWERED, answer, null);
    }
}
