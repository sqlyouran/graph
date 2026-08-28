package com.looptrip;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

@Component
public class FactBackedPlanGenerator {

    private final TravelDataService data;

    public FactBackedPlanGenerator(TravelDataService data) {
        this.data = data;
    }

    public TripPlan generate(PlanRequest request) {
        FlightFact outbound = cheapestFlight(request.origin(), request.destination(), request.startDate());
        LocalDate returnDate = request.startDate().plusDays(request.days() - 1L);
        FlightFact inbound = latestFlight(request.destination(), request.origin(), returnDate);
        HotelFact hotel = data.searchHotels(request.destination(), request.maxHotelPrice()).stream()
                .min(Comparator.comparingInt(HotelFact::pricePerNight)
                        .thenComparing(Comparator.comparingDouble(HotelFact::rating).reversed()))
                .orElseThrow(() -> new IllegalStateException("没有满足限价的酒店事实"));

        List<AttractionFact> available = data.searchAttractions(request.destination());
        List<AttractionFact> ordered = prioritizeMustVisit(request.mustVisit(), available);
        List<TripDayPlan> days = new ArrayList<>();
        int attractionIndex = 0;
        for (int offset = 0; offset < request.days(); offset++) {
            LocalDate date = request.startDate().plusDays(offset);
            List<TripActivity> activities = new ArrayList<>();
            activities.add(new TripActivity("市内交通", "TRANSFER", LocalTime.of(8, 20),
                    LocalTime.of(8, 50), hotel.area(), 0));

            AttractionFact morning = nextOpen(ordered, date, attractionIndex);
            if (morning != null) {
                attractionIndex = ordered.indexOf(morning) + 1;
                LocalTime start = later(LocalTime.of(9, 0), morning.openTime());
                LocalTime end = earlier(start.plusMinutes(Math.min(150, morning.recommendedDurationMinutes())),
                        morning.closeTime());
                activities.add(activity(morning, start, end));
            }
            activities.add(new TripActivity("本地午餐", "MEAL", LocalTime.of(12, 0),
                    LocalTime.of(13, 0), morning == null ? hotel.area() : morning.area(), 0));

            AttractionFact afternoon = nextOpen(ordered, date, attractionIndex);
            if (afternoon != null) {
                attractionIndex = ordered.indexOf(afternoon) + 1;
                LocalTime start = later(LocalTime.of(13, 30), afternoon.openTime());
                LocalTime end = earlier(start.plusMinutes(Math.min(150, afternoon.recommendedDurationMinutes())),
                        afternoon.closeTime());
                activities.add(activity(afternoon, start, end));
            }
            days.add(new TripDayPlan(date,
                    new TripHotel(hotel.name(), hotel.area(), hotel.pricePerNight()), activities));
        }
        return new TripPlan(request.origin().trim(), request.destination().trim(), request.startDate(), request.days(),
                tripFlight(outbound), tripFlight(inbound), days);
    }

    private FlightFact cheapestFlight(String origin, String destination, LocalDate date) {
        List<FlightFact> flights = data.searchFlights(origin, destination);
        return flights.stream().filter(item -> item.departureTime().toLocalDate().equals(date))
                .min(Comparator.comparingInt(FlightFact::price))
                .orElseGet(() -> flights.stream().min(Comparator.comparingInt(FlightFact::price))
                        .orElseThrow(() -> new IllegalStateException("没有可用航班事实")));
    }

    private FlightFact latestFlight(String origin, String destination, LocalDate date) {
        List<FlightFact> flights = data.searchFlights(origin, destination);
        return flights.stream().filter(item -> item.departureTime().toLocalDate().equals(date))
                .max(Comparator.comparing(FlightFact::departureTime))
                .orElseGet(() -> flights.stream().max(Comparator.comparing(FlightFact::departureTime))
                        .orElseThrow(() -> new IllegalStateException("没有可用返程航班事实")));
    }

    private List<AttractionFact> prioritizeMustVisit(List<String> mustVisit, List<AttractionFact> available) {
        Set<AttractionFact> result = new LinkedHashSet<>();
        for (String required : mustVisit) {
            available.stream().filter(item -> matches(required, item.name())).findFirst().ifPresent(result::add);
        }
        result.addAll(available);
        return List.copyOf(result);
    }

    private AttractionFact nextOpen(List<AttractionFact> ordered, LocalDate date, int fromIndex) {
        for (int index = fromIndex; index < ordered.size(); index++) {
            AttractionFact item = ordered.get(index);
            if (!item.closedDays().contains(date.getDayOfWeek())) return item;
        }
        return null;
    }

    private boolean matches(String expected, String actual) {
        String left = expected == null ? "" : expected.trim().replace("景区", "").replace("寺", "");
        String right = actual == null ? "" : actual.trim().replace("景区", "").replace("寺", "");
        return !left.isBlank() && (right.contains(left) || left.contains(right));
    }

    private TripActivity activity(AttractionFact fact, LocalTime start, LocalTime end) {
        return new TripActivity(fact.name(), "ATTRACTION", start, end, fact.area(), fact.ticketPrice());
    }

    private TripFlight tripFlight(FlightFact fact) {
        return new TripFlight(fact.flightNumber(), fact.origin(), fact.destination(), fact.departureTime(),
                fact.arrivalTime(), fact.price());
    }

    private LocalTime later(LocalTime left, LocalTime right) { return left.isAfter(right) ? left : right; }
    private LocalTime earlier(LocalTime left, LocalTime right) { return left.isBefore(right) ? left : right; }
}
