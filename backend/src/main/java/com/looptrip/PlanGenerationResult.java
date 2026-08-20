package com.looptrip;

public record PlanGenerationResult(String markdown, String model, long elapsedMs) {
}
