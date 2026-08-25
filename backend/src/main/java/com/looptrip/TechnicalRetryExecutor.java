package com.looptrip;

import org.springframework.stereotype.Component;

@Component
public class TechnicalRetryExecutor {
    private static final int MAX_RETRIES = 2;
    private static final long BACKOFF_MS = 500;
    private final RetrySleeper sleeper;

    public TechnicalRetryExecutor(RetrySleeper sleeper) { this.sleeper = sleeper; }

    PlanGenerationResult execute(PlanGenerator generator, PlanGenerationInput input) {
        for (int attempt = 0; ; attempt++) {
            try {
                return generator.generate(input);
            } catch (ModelCallException exception) {
                if (!exception.retryable() || attempt >= MAX_RETRIES) throw exception;
                try {
                    sleeper.sleep(BACKOFF_MS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new ModelCallException("模型调用重试被中断", interrupted);
                }
            }
        }
    }
}
