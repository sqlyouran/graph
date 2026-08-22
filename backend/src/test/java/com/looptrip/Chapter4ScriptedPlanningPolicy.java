package com.looptrip;

import java.util.ArrayList;
import java.util.List;

final class Chapter4ScriptedPlanningPolicy implements PlanGenerator {

    enum Scenario {
        SUCCESS,
        FEEDBACK,
        NO_FEEDBACK,
        MAX_ROUNDS
    }

    private final Scenario scenario;
    private final List<PlanGenerationInput> inputs = new ArrayList<>();
    private boolean secondRoundReceivedFeedback;

    Chapter4ScriptedPlanningPolicy(Scenario scenario) {
        this.scenario = scenario;
    }

    @Override
    public PlanGenerationResult generate(PlanGenerationInput input) {
        inputs.add(input);
        if (input.round() == 2) {
            secondRoundReceivedFeedback = scenario != Scenario.NO_FEEDBACK
                    && input.feedbackProblems().contains("缺少第 3 天安排");
        }

        TripPlan plan = switch (scenario) {
            case SUCCESS -> TestTripPlans.complete(3);
            case FEEDBACK -> input.round() == 1 ? TestTripPlans.missingLastDay() : TestTripPlans.complete(3);
            case NO_FEEDBACK, MAX_ROUNDS -> TestTripPlans.missingLastDay();
        };
        return PlanGenerationResult.success(plan, "scripted", 1);
    }

    List<PlanGenerationInput> inputs() {
        return List.copyOf(inputs);
    }

    boolean secondRoundReceivedFeedback() {
        return secondRoundReceivedFeedback;
    }
}
