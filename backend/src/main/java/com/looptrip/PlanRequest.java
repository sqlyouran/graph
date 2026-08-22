package com.looptrip;

import java.time.LocalDate;
import java.util.List;

public record PlanRequest(
        String origin,
        String destination,
        LocalDate startDate,
        int days,
        int budget,
        int maxHotelPrice,
        String preferences,
        List<String> mustVisit,
        int maxRounds) {

    public PlanRequest {
        mustVisit = mustVisit == null ? List.of() : mustVisit.stream()
                .filter(item -> item != null && !item.isBlank())
                .map(String::trim)
                .toList();
    }

    public PlanRequest(
            String origin,
            String destination,
            LocalDate startDate,
            int days,
            int budget,
            int maxHotelPrice,
            String preferences,
            int maxRounds) {
        this(origin, destination, startDate, days, budget, maxHotelPrice, preferences, List.of(), maxRounds);
    }
}
