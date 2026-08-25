package com.looptrip;

record PreflightResult(PlanningTerminalState state, PlanningStopReport report) {
    static PreflightResult feasible() { return new PreflightResult(null, null); }
    boolean canStart() { return state == null; }
}
