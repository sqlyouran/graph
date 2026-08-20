package com.looptrip;

import java.util.List;

public record BasicContractReviewResult(boolean passed, List<String> problems) {

    public BasicContractReviewResult {
        problems = List.copyOf(problems);
    }
}
