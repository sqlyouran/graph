package com.looptrip;

@FunctionalInterface
public interface RetrySleeper {
    void sleep(long millis) throws InterruptedException;
}
