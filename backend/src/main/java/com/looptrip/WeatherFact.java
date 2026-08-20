package com.looptrip;

import java.time.LocalDate;

public record WeatherFact(
        String city,
        LocalDate date,
        String weather,
        int minTemperature,
        int maxTemperature,
        int precipitationProbability) {
}
