package com.looptrip;

import java.util.ArrayList;
import java.util.List;

/** 贯穿全程的随身工单：不可变，节点只读，变化一律经 StateDelta 合并。 */
public final class GraphState implements GraphStateContract<GraphState> {

    private final PlanRequest request;
    private final PlanningSeed seed;
    private final boolean guardrailAbsent;
    private final int round;
    private final int firstEventIndex;
    private final List<PlanningRoundSnapshot> rounds;
    private final List<PlanningLedgerEntry> ledgerEntries;
    private final List<String> feedback;
    private final TripPlan previousPlan;
    private final PlanningRoundSnapshot bestRound;
    private final long totalElapsedMs;
    private final PreflightResult preflightStop;
    private final Boolean passed;
    private final PlanGenerationInput lastGenerationInput;
    private final PlanGenerationResult lastGenerated;
    private final PlanningFacts facts;
    private final Boolean coreFactsMissing;

    private GraphState(PlanRequest request, PlanningSeed seed, boolean guardrailAbsent, int round,
            int firstEventIndex, List<PlanningRoundSnapshot> rounds, List<PlanningLedgerEntry> ledgerEntries,
            List<String> feedback, TripPlan previousPlan, PlanningRoundSnapshot bestRound, long totalElapsedMs,
            PreflightResult preflightStop, Boolean passed, PlanGenerationInput lastGenerationInput,
            PlanGenerationResult lastGenerated, PlanningFacts facts, Boolean coreFactsMissing) {
        this.request = request;
        this.seed = seed;
        this.guardrailAbsent = guardrailAbsent;
        this.round = round;
        this.firstEventIndex = firstEventIndex;
        this.rounds = rounds;
        this.ledgerEntries = ledgerEntries;
        this.feedback = feedback;
        this.previousPlan = previousPlan;
        this.bestRound = bestRound;
        this.totalElapsedMs = totalElapsedMs;
        this.preflightStop = preflightStop;
        this.passed = passed;
        this.lastGenerationInput = lastGenerationInput;
        this.lastGenerated = lastGenerated;
        this.facts = facts;
        this.coreFactsMissing = coreFactsMissing;
    }

    static GraphState initial(PlanRequest request, PlanningSeed seed, boolean guardrailAbsent) {
        return new GraphState(request, seed, guardrailAbsent, 0, 0, List.of(), List.of(),
                seed.feedback(), seed.previousPlan(), null, 0, null, null, null, null, PlanningFacts.empty(), null);
    }

    @Override
    public GraphState apply(GraphDelta delta) {
        if (!(delta instanceof StateDelta d)) {
            return this;
        }
        return new GraphState(
                request,
                seed,
                guardrailAbsent,
                d.round() != null ? d.round() : round,
                d.firstEventIndex() != null ? d.firstEventIndex() : firstEventIndex,
                d.addRound() != null ? appended(rounds, d.addRound()) : rounds,
                d.addLedgerEntry() != null ? appended(ledgerEntries, d.addLedgerEntry()) : ledgerEntries,
                d.feedback() != null ? d.feedback() : feedback,
                d.previousPlan() != null ? d.previousPlan() : previousPlan,
                d.bestRound() != null ? d.bestRound() : bestRound,
                totalElapsedMs + (d.elapsedAdd() != null ? d.elapsedAdd() : 0L),
                d.preflightStop() != null ? d.preflightStop() : preflightStop,
                d.passed() != null ? d.passed() : passed,
                d.lastGenerationInput() != null ? d.lastGenerationInput() : lastGenerationInput,
                d.lastGenerated() != null ? d.lastGenerated() : lastGenerated,
                d.factSlot() != null ? facts.with(d.factSlot()) : facts,
                d.coreFactsMissing() != null ? d.coreFactsMissing() : coreFactsMissing);
    }

    @Override
    public boolean satisfies(String fromNode, String condition) {
        switch (fromNode) {
            case "START":
            case "generate":
                if (condition.equals("always")) return true;
                break;
            case "preflight":
                if (condition.equals("feasible")) return preflightStop == null;
                if (condition.equals("infeasible")) return preflightStop != null;
                break;
            case "validate":
                boolean legacyMaxRounds = guardrailAbsent && round >= request.maxRounds();
                boolean coreMissing = Boolean.TRUE.equals(coreFactsMissing);
                if (condition.equals("accepted")) return Boolean.TRUE.equals(passed);
                if (condition.equals("cannot_deliver"))
                    return Boolean.FALSE.equals(passed) && (legacyMaxRounds || coreMissing);
                if (condition.equals("needs_revision"))
                    return Boolean.FALSE.equals(passed) && !legacyMaxRounds && !coreMissing;
                break;
            default:
                throw new IllegalStateException("未知的选边起点节点：" + fromNode);
        }
        throw new IllegalStateException("节点 " + fromNode + " 不认识边条件：" + condition);
    }

    private static <T> List<T> appended(List<T> source, T item) {
        List<T> copy = new ArrayList<>(source);
        copy.add(item);
        return List.copyOf(copy);
    }

    public PlanRequest request() {
        return request;
    }

    public int round() {
        return round;
    }

    public int firstEventIndex() {
        return firstEventIndex;
    }

    public List<PlanningRoundSnapshot> rounds() {
        return rounds;
    }

    public PlanningLedgerSnapshot ledgerSnapshot() {
        return new PlanningLedgerSnapshot(ledgerEntries);
    }

    public List<String> feedback() {
        return feedback;
    }

    public TripPlan previousPlan() {
        return previousPlan;
    }

    public PlanningRoundSnapshot bestRound() {
        return bestRound;
    }

    public long totalElapsedMs() {
        return totalElapsedMs;
    }

    public PreflightResult preflightStop() {
        return preflightStop;
    }

    public PlanGenerationInput lastGenerationInput() {
        return lastGenerationInput;
    }

    public PlanGenerationResult lastGenerated() {
        return lastGenerated;
    }

    public PlanningFacts facts() {
        return facts;
    }

    public boolean coreFactsMissing() {
        return Boolean.TRUE.equals(coreFactsMissing);
    }
}
