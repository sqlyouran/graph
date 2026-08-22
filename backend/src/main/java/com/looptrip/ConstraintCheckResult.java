package com.looptrip;

import java.util.List;

public record ConstraintCheckResult(
        String code,
        String name,
        ConstraintSeverity severity,
        boolean passed,
        List<String> evidence,
        List<String> suggestions) {

    public ConstraintCheckResult {
        evidence = List.copyOf(evidence);
        suggestions = List.copyOf(suggestions);
    }
}
