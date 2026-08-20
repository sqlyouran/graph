package com.looptrip;

public record HotelFact(
        String name,
        String city,
        String area,
        int pricePerNight,
        double rating) {
}
