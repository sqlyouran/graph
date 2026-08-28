package com.looptrip;

import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "looptrip.session")
public record SessionProperties(int maxLive, Duration ttl, Path storagePath) {
    public SessionProperties {
        if (maxLive <= 0) maxLive = 20;
        if (ttl == null || ttl.isNegative() || ttl.isZero()) ttl = Duration.ofMinutes(30);
        if (storagePath == null) storagePath = Path.of("data", "planning-sessions");
    }
}
