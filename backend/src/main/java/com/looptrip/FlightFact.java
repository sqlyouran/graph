package com.looptrip;

import java.time.LocalDateTime;

public record FlightFact(
        String flightNumber,
        String origin,
        String destination,
        LocalDateTime departureTime,
        LocalDateTime arrivalTime,
        int price) {
}
