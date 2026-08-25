package com.looptrip;

import java.util.List;

public record PlanningLedgerSnapshot(List<PlanningLedgerEntry> entries) {
    public PlanningLedgerSnapshot { entries = List.copyOf(entries); }
    public long totalEstimatedTokens() { return entries.stream().mapToLong(PlanningLedgerEntry::estimatedTokens).sum(); }
    public long totalElapsedMs() { return entries.stream().mapToLong(PlanningLedgerEntry::elapsedMs).sum(); }
    public PlanningLedgerEntry latest() { return entries.getLast(); }
}
