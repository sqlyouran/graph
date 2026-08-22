package com.looptrip;

import java.time.LocalDate;
import java.util.List;

public record TripPlan(
        String origin,
        String destination,
        LocalDate startDate,
        int days,
        TripFlight outboundFlight,
        TripFlight returnFlight,
        List<TripDayPlan> dailyPlans) {

    public TripPlan {
        dailyPlans = dailyPlans == null ? List.of() : List.copyOf(dailyPlans);
    }
}
