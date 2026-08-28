package com.looptrip;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PlanningIntentGateTests {
    private final PlanningIntentGate gate = new PlanningIntentGate();

    @Test
    void allNineActionsHaveDefinedRouting() {
        assertThat(PlanningIntentAction.values()).hasSize(9);
        assertThat(PlanningIntentAction.REVISE_BUDGET.requiresConfirmation()).isTrue();
        assertThat(PlanningIntentAction.REVISE_HOTEL_PRICE.requiresSlots()).isTrue();
        assertThat(PlanningIntentAction.ADD_MUST_VISIT.requiresSlots()).isTrue();
        assertThat(PlanningIntentAction.ADJUST_PACE.dispatchesOperation()).isTrue();
        assertThat(PlanningIntentAction.CANCEL.requiresConfirmation()).isTrue();
        assertThat(PlanningIntentAction.ROLLBACK.requiresConfirmation()).isFalse();
        assertThat(PlanningIntentAction.NEW_SESSION.dispatchesOperation()).isFalse();
        assertThat(PlanningIntentAction.ASK_QUESTION.dispatchesOperation()).isTrue();
        assertThat(PlanningIntentAction.UNKNOWN.dispatchesOperation()).isFalse();
    }

    @Test
    void missingSlotsAreClarifiedByGate() {
        assertThat(gate.decide(intent(PlanningIntentAction.REVISE_HOTEL_PRICE, Map.of()), false, null).decision())
                .isEqualTo(PlanningChatDecision.CLARIFICATION_REQUIRED);
        assertThat(gate.decide(intent(PlanningIntentAction.REVISE_BUDGET, Map.of()), false, null).text())
                .contains("总预算");
    }

    @Test
    void unknownNeverDispatches() {
        assertThat(gate.decide(PlanningIntent.unknown("???"), true, null).decision())
                .isEqualTo(PlanningChatDecision.UNRECOGNIZED);
    }

    @Test
    void confirmationGateBlocksBudgetAndCancelUntilConfirmed() {
        PlanningIntent budget = intent(PlanningIntentAction.REVISE_BUDGET, Map.of("budget", 3000));
        PlanningIntent cancel = intent(PlanningIntentAction.CANCEL, Map.of());
        assertThat(gate.decide(budget, false, null).decision()).isEqualTo(PlanningChatDecision.CONFIRM_REQUIRED);
        assertThat(gate.decide(cancel, false, null).decision()).isEqualTo(PlanningChatDecision.CONFIRM_REQUIRED);
        assertThat(gate.decide(budget, true, null).decision()).isEqualTo(PlanningChatDecision.EXECUTED);
        assertThat(gate.decide(cancel, true, null).decision()).isEqualTo(PlanningChatDecision.EXECUTED);
    }

    @Test
    void rollbackExecutesWithoutConfirmation() {
        assertThat(gate.decide(intent(PlanningIntentAction.ROLLBACK, Map.of()), false, null).decision())
                .isEqualTo(PlanningChatDecision.EXECUTED);
    }

    private PlanningIntent intent(PlanningIntentAction action, Map<String, Object> slots) {
        return new PlanningIntent(action, slots, "理解", action.name());
    }
}
