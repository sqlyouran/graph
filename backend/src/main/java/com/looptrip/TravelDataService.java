package com.looptrip;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class TravelDataService {

    private static final String FLIGHTS_PATH = "classpath:facts/flights.json";
    private static final String HOTELS_PATH = "classpath:facts/hotels.json";
    private static final String ATTRACTIONS_PATH = "classpath:facts/attractions.json";
    private static final String WEATHER_PATH = "classpath:facts/weather.json";

    private static final Map<String, String> CITY_ALIASES = Map.ofEntries(
            Map.entry("杭州", "杭州"),
            Map.entry("杭州市", "杭州"),
            Map.entry("浙江杭州", "杭州"),
            Map.entry("浙江省杭州", "杭州"),
            Map.entry("浙江省杭州市", "杭州"),
            Map.entry("上海", "上海"),
            Map.entry("上海市", "上海"));

    private final ObjectMapper objectMapper;
    private final ResourceLoader resourceLoader;

    private List<FlightFact> flights = List.of();
    private List<HotelFact> hotels = List.of();
    private List<AttractionFact> attractions = List.of();
    private List<WeatherFact> weather = List.of();

    public TravelDataService(ObjectMapper objectMapper, ResourceLoader resourceLoader) {
        this.objectMapper = objectMapper;
        this.resourceLoader = resourceLoader;
    }

    @PostConstruct
    void loadFacts() {
        flights = readAndValidate(FLIGHTS_PATH, new TypeReference<>() {}, this::validateFlight);
        hotels = readAndValidate(HOTELS_PATH, new TypeReference<>() {}, this::validateHotel);
        attractions = readAndValidate(ATTRACTIONS_PATH, new TypeReference<>() {}, this::validateAttraction);
        weather = readAndValidate(WEATHER_PATH, new TypeReference<>() {}, this::validateWeather);
    }

    public List<FlightFact> searchFlights(String origin, String destination) {
        String normalizedOrigin = normalizeCity(origin);
        String normalizedDestination = normalizeCity(destination);
        if (normalizedOrigin == null || normalizedDestination == null) {
            return List.of();
        }
        return flights.stream()
                .filter(flight -> flight.origin().equals(normalizedOrigin))
                .filter(flight -> flight.destination().equals(normalizedDestination))
                .toList();
    }

    public List<HotelFact> searchHotels(String destination, int maxPricePerNight) {
        String normalizedDestination = normalizeCity(destination);
        if (normalizedDestination == null || maxPricePerNight < 0) {
            return List.of();
        }
        return hotels.stream()
                .filter(hotel -> hotel.city().equals(normalizedDestination))
                .filter(hotel -> maxPricePerNight == 0 || hotel.pricePerNight() <= maxPricePerNight)
                .toList();
    }

    public List<AttractionFact> searchAttractions(String destination) {
        String normalizedDestination = normalizeCity(destination);
        if (normalizedDestination == null) {
            return List.of();
        }
        return attractions.stream()
                .filter(attraction -> attraction.city().equals(normalizedDestination))
                .toList();
    }

    public List<WeatherFact> queryWeather(String destination, String date) {
        String normalizedDestination = normalizeCity(destination);
        if (normalizedDestination == null || !StringUtils.hasText(date)) {
            return List.of();
        }

        LocalDate parsedDate;
        try {
            parsedDate = LocalDate.parse(date.trim());
        } catch (DateTimeParseException exception) {
            return List.of();
        }

        return weather.stream()
                .filter(item -> item.city().equals(normalizedDestination))
                .filter(item -> item.date().equals(parsedDate))
                .toList();
    }

    private <T> List<T> readAndValidate(
            String path,
            TypeReference<List<T>> type,
            Consumer<T> validator) {
        Resource resource = resourceLoader.getResource(path);
        if (!resource.exists()) {
            throw new TravelDataConfigurationException("Travel facts file not found: " + path);
        }

        try (InputStream input = resource.getInputStream()) {
            List<T> items = objectMapper.readValue(input, type);
            if (items == null || items.isEmpty()) {
                throw new TravelDataConfigurationException("Travel facts file must contain at least one item: " + path);
            }
            for (int index = 0; index < items.size(); index++) {
                try {
                    validator.accept(items.get(index));
                } catch (TravelDataConfigurationException exception) {
                    throw new TravelDataConfigurationException(
                            "Invalid travel fact in " + path + " at index " + index + ": " + exception.getMessage(),
                            exception);
                }
            }
            return List.copyOf(items);
        } catch (TravelDataConfigurationException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new TravelDataConfigurationException("Cannot parse travel facts file: " + path, exception);
        }
    }

    private void validateFlight(FlightFact flight) {
        requireText(flight.flightNumber(), "flightNumber");
        requireText(flight.origin(), "origin");
        requireText(flight.destination(), "destination");
        requirePresent(flight.departureTime(), "departureTime");
        requirePresent(flight.arrivalTime(), "arrivalTime");
        if (!flight.arrivalTime().isAfter(flight.departureTime())) {
            invalid("arrivalTime must be after departureTime");
        }
        requireNonNegative(flight.price(), "price");
    }

    private void validateHotel(HotelFact hotel) {
        requireText(hotel.name(), "name");
        requireText(hotel.city(), "city");
        requireText(hotel.area(), "area");
        requireNonNegative(hotel.pricePerNight(), "pricePerNight");
        if (hotel.rating() < 0 || hotel.rating() > 5) {
            invalid("rating must be between 0 and 5");
        }
    }

    private void validateAttraction(AttractionFact attraction) {
        requireText(attraction.name(), "name");
        requireText(attraction.city(), "city");
        requireText(attraction.area(), "area");
        requireNonNegative(attraction.ticketPrice(), "ticketPrice");
        requirePresent(attraction.openTime(), "openTime");
        requirePresent(attraction.closeTime(), "closeTime");
        requirePresent(attraction.closedDays(), "closedDays");
        if (attraction.recommendedDurationMinutes() <= 0) {
            invalid("recommendedDurationMinutes must be greater than 0");
        }
    }

    private void validateWeather(WeatherFact item) {
        requireText(item.city(), "city");
        requirePresent(item.date(), "date");
        requireText(item.weather(), "weather");
        if (item.minTemperature() > item.maxTemperature()) {
            invalid("minTemperature must not exceed maxTemperature");
        }
        if (item.precipitationProbability() < 0 || item.precipitationProbability() > 100) {
            invalid("precipitationProbability must be between 0 and 100");
        }
    }

    private String normalizeCity(String city) {
        if (!StringUtils.hasText(city)) {
            return null;
        }
        String compact = city.trim().replaceAll("\\s+", "");
        return CITY_ALIASES.getOrDefault(compact, compact);
    }

    private void requireText(String value, String field) {
        if (!StringUtils.hasText(value)) invalid(field + " must not be blank");
    }

    private void requirePresent(Object value, String field) {
        if (value == null) invalid(field + " must not be null");
    }

    private void requireNonNegative(int value, String field) {
        if (value < 0) invalid(field + " must be greater than or equal to 0");
    }

    private void invalid(String message) {
        throw new TravelDataConfigurationException(message);
    }
}
