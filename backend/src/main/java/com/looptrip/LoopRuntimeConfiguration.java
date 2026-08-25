package com.looptrip;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(LoopGuardProperties.class)
class LoopRuntimeConfiguration {
    @Bean Clock planningClock() { return Clock.systemUTC(); }
    @Bean RetrySleeper retrySleeper() { return Thread::sleep; }
    @Bean PlanningCancellationSignal planningCancellationSignal() { return () -> false; }
}
