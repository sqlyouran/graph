package com.looptrip;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "looptrip")
public record LoopGuardProperties(
        long maxTokens,
        Duration maxDuration,
        double multiplier,
        int sameFingerprintLimit) {

    public LoopGuardProperties {
        if (maxTokens <= 0) maxTokens = 20_000;
        if (maxDuration == null || maxDuration.isNegative() || maxDuration.isZero()) maxDuration = Duration.ofSeconds(30);
        if (multiplier < 1) multiplier = 1.2;
        if (sameFingerprintLimit < 2) sameFingerprintLimit = 2;
    }
}
