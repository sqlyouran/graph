package com.looptrip;

import java.util.List;

/** 一轮节点执行交回的增量：null 字段表示"不改"。 */
public record StateDelta(
        Integer round,
        Integer firstEventIndex,
        PlanGenerationInput lastGenerationInput,
        PlanGenerationResult lastGenerated,
        Long elapsedAdd,
        List<String> feedback,
        TripPlan previousPlan,
        PlanningRoundSnapshot bestRound,
        PlanningRoundSnapshot addRound,
        PlanningLedgerEntry addLedgerEntry,
        PreflightResult preflightStop,
        Boolean passed,
        PlanningFacts.FactSlot factSlot,
        Boolean coreFactsMissing) implements GraphDelta {

    public static StateDelta none() {
        return new StateDelta(null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    static StateDelta roundStarted(int round, int firstEventIndex, PlanGenerationInput input,
            PlanGenerationResult generated, long elapsedAdd) {
        return new StateDelta(round, firstEventIndex, input, generated, elapsedAdd,
                null, null, null, null, null, null, null, null, null);
    }

    static StateDelta preflightStopped(PreflightResult stop) {
        return new StateDelta(null, null, null, null, null, null, null, null, null, null, stop, null, null, null);
    }

    static StateDelta validated(List<String> feedback, TripPlan previousPlan, PlanningRoundSnapshot bestRound,
            PlanningRoundSnapshot addRound, PlanningLedgerEntry addLedgerEntry, boolean passed,
            boolean coreFactsMissing) {
        return new StateDelta(null, null, null, null, null, feedback, previousPlan,
                bestRound, addRound, addLedgerEntry, null, passed, null, coreFactsMissing);
    }

    static StateDelta factReported(PlanningFacts.FactSlot slot) {
        return new StateDelta(null, null, null, null, null, null, null, null, null, null, null, null, slot, null);
    }
}
