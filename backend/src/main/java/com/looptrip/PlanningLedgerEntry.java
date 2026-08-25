package com.looptrip;

public record PlanningLedgerEntry(int round, long estimatedTokens, long elapsedMs, String hardFailureFingerprint) {}
