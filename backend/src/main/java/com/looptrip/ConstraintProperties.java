package com.looptrip;

import java.time.LocalTime;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "looptrip.constraints")
public record ConstraintProperties(Route route, Pace pace) {
    public record Route(int maxCrossAreaPerDay, int minTransferMinutes) {}

    public record Pace(
            int minAttractionsPerDay,
            int maxAttractionsPerDay,
            int maxActivityMinutesPerDay,
            LocalTime mealWindowStart,
            LocalTime mealWindowEnd) {}
}
