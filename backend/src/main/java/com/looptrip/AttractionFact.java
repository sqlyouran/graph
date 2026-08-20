package com.looptrip;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

public record AttractionFact(
        String name,
        String city,
        String area,
        int ticketPrice,
        LocalTime openTime,
        LocalTime closeTime,
        List<DayOfWeek> closedDays,
        int recommendedDurationMinutes) {
}
