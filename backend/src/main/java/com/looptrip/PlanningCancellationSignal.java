package com.looptrip;

@FunctionalInterface
public interface PlanningCancellationSignal {
    boolean isCancelled();
}
