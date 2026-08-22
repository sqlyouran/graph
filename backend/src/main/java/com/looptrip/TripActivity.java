package com.looptrip;

import java.time.LocalTime;

public record TripActivity(
        String name,
        String type,
        LocalTime startTime,
        LocalTime endTime,
        String area,
        Integer price) {
}
