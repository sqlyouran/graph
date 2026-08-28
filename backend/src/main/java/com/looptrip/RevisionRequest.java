package com.looptrip;

import java.time.LocalDate;
import java.util.List;

public record RevisionRequest(
        Integer budget,
        Integer maxHotelPrice,
        String preferences,
        Integer maxRounds,
        List<String> mustVisit,
        String destination,
        LocalDate startDate,
        Integer days) {
}
