package com.looptrip;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({LoopGuardProperties.class, SessionProperties.class})
class LoopRuntimeConfiguration {
    @Bean Clock planningClock() { return Clock.systemUTC(); }
    @Bean RetrySleeper retrySleeper() { return Thread::sleep; }
    @Bean PlanningCancellationSignal planningCancellationSignal(PlanningSessionContext context) {
        return new SessionCancellationSignal(context);
    }
    @Bean java.util.concurrent.Executor planningSessionExecutor() {
        return java.util.concurrent.Executors.newFixedThreadPool(4);
    }
}
