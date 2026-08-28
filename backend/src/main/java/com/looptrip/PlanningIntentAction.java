package com.looptrip;

public enum PlanningIntentAction {
    REVISE_BUDGET(true, true), REVISE_HOTEL_PRICE(true, true), ADD_MUST_VISIT(true, true), ADJUST_PACE(true, true),
    CANCEL(false, true), ROLLBACK(false, false), NEW_SESSION(true, false), ASK_QUESTION(true, false), UNKNOWN(false, false);

    private final boolean slots;
    private final boolean confirmation;
    PlanningIntentAction(boolean slots, boolean confirmation) { this.slots = slots; this.confirmation = confirmation; }
    public boolean requiresSlots() { return slots; }
    public boolean requiresConfirmation() { return confirmation; }
    public boolean dispatchesOperation() { return this != NEW_SESSION && this != UNKNOWN; }
}
