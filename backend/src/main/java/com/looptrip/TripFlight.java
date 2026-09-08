package com.looptrip;

import java.time.LocalDateTime;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

public record TripFlight(
        String flightNumber,
        String origin,
        String destination,
        @JsonDeserialize(using = LenientLocalDateTimeDeserializer.class) LocalDateTime departureTime,
        @JsonDeserialize(using = LenientLocalDateTimeDeserializer.class) LocalDateTime arrivalTime,
        Integer price) {
}
