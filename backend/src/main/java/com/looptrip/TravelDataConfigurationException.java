package com.looptrip;

public class TravelDataConfigurationException extends RuntimeException {

    public TravelDataConfigurationException(String message) {
        super(message);
    }

    public TravelDataConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
