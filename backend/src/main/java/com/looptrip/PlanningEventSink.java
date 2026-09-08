package com.looptrip;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

public interface PlanningEventSink {

    <T> T capture(List<PlanningEvent> events, Supplier<T> action);

    void setRound(int round);

    void emit(PlanningEventType type, String message, Map<String, Object> details);

    /** 把当前线程的事件上下文（会话/轮次）带到并行线程池里执行的任务上；默认不传播。 */
    default <T> Callable<T> propagate(Callable<T> task) {
        return task;
    }
}
