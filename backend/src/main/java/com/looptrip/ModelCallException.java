package com.looptrip;

public class ModelCallException extends RuntimeException {

    private final boolean retryable;

    public ModelCallException(String message) {
        this(message, null, false);
    }

    public ModelCallException(String message, Throwable cause) {
        this(message, cause, false);
    }

    public ModelCallException(String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean retryable() { return retryable; }
}
