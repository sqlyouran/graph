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

    private static final String COMPLETE = """
            ## 第 1 天
            参观景点，晚上入住酒店。
            ## 第 2 天
            游览景点并继续住宿。
            ## 第 3 天
            参观景点后返程。
            预计总花费 2000 元
            """;

    private static final String MISSING_DAY_THREE = """
            ## 第 1 天
            参观景点，晚上入住酒店。
            ## 第 2 天
            游览景点并继续住宿。
            预计总花费 1800 元
            """;

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

        String markdown = switch (scenario) {
            case SUCCESS -> COMPLETE;
            case FEEDBACK -> input.round() == 1 ? MISSING_DAY_THREE : COMPLETE;
            case NO_FEEDBACK, MAX_ROUNDS -> MISSING_DAY_THREE;
        };
        return new PlanGenerationResult(markdown, "scripted", 1);
    }

    List<PlanGenerationInput> inputs() {
        return List.copyOf(inputs);
    }

    boolean secondRoundReceivedFeedback() {
        return secondRoundReceivedFeedback;
    }
}
