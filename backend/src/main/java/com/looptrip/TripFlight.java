package com.looptrip;

import java.time.LocalDateTime;

public record TripFlight(
        String flightNumber,
        String origin,
        String destination,
        LocalDateTime departureTime,
        LocalDateTime arrivalTime,
        Integer price) {
}
