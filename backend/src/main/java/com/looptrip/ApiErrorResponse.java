package com.looptrip;

import java.util.List;

public record ApiErrorResponse(String errorCode, String message, String suggestion,
        List<Integer> availableVersions) {
    public ApiErrorResponse {
        availableVersions = availableVersions == null ? List.of() : List.copyOf(availableVersions);
    }

    public ApiErrorResponse(String errorCode, String message) {
        this(errorCode, message, null, List.of());
    }
}
