package com.looptrip;

public interface PlanningIntentRecognizer {
    PlanningIntent recognize(String utterance, PlanningSession session);
}
