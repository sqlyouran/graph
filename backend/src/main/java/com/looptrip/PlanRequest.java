package com.looptrip;

import java.time.LocalDate;

public record PlanRequest(
        String origin,
        String destination,
        LocalDate startDate,
        int days,
        int budget,
        int maxHotelPrice,
        String preferences,
        int maxRounds) {
}
