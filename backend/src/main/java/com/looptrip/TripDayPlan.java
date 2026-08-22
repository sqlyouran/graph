package com.looptrip;

import java.time.LocalDate;
import java.util.List;

public record TripDayPlan(
        LocalDate date,
        TripHotel hotel,
        List<TripActivity> activities) {

    public TripDayPlan {
        activities = activities == null ? List.of() : List.copyOf(activities);
    }
}
