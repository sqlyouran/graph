package com.looptrip;

import java.util.List;
import org.springframework.http.HttpStatus;

public class SessionOperationException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final String suggestion;
    private final List<Integer> availableVersions;

    public SessionOperationException(HttpStatus status, String code, String reason, String suggestion) {
        this(status, code, reason, suggestion, List.of());
    }

    public SessionOperationException(HttpStatus status, String code, String reason, String suggestion,
            List<Integer> availableVersions) {
        super(reason);
        this.status = status;
        this.code = code;
        this.suggestion = suggestion;
        this.availableVersions = List.copyOf(availableVersions);
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
    public String suggestion() { return suggestion; }
    public List<Integer> availableVersions() { return availableVersions; }
}
