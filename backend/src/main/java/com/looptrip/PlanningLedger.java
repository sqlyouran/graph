package com.looptrip;

import java.util.ArrayList;
import java.util.List;

final class PlanningLedger {
    private final List<PlanningLedgerEntry> entries = new ArrayList<>();
    void record(PlanningLedgerEntry entry) { entries.add(entry); }
    PlanningLedgerSnapshot snapshot() { return new PlanningLedgerSnapshot(entries); }
}
