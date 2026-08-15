package com.looptrip;

public record AskResponse(String answer, String model, long elapsedMs) {
}
