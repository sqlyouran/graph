package com.looptrip;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.core.io.DefaultResourceLoader;

import static org.assertj.core.api.Assertions.assertThat;

class TravelToolsTests {

    private TravelTools travelTools;

    @BeforeEach
    void setUp() {
        TravelDataService dataService = new TravelDataService(
                new ObjectMapper().findAndRegisterModules(),
                new DefaultResourceLoader());
        dataService.loadFacts();
        travelTools = new TravelTools(dataService, new InMemoryPlanningEventSink());
    }

    @Test
    void registersFourDetailedToolDefinitions() {
        Map<String, ToolCallback> callbacks = callbacksByName();

        assertThat(callbacks).containsOnlyKeys(
                "searchFlights", "searchHotels", "searchAttractions", "queryWeather");
        assertTool(callbacks.get("searchFlights"), "origin", "destination", "空列表");
        assertTool(callbacks.get("searchHotels"), "destination", "maxPricePerNight", "0 表示不限");
        assertTool(callbacks.get("searchAttractions"), "destination", "ticketPrice", "空列表");
        assertTool(callbacks.get("queryWeather"), "destination", "date", "yyyy-MM-dd");
    }

    @Test
    void serializesStableToolReturnStructures() {
        Map<String, ToolCallback> callbacks = callbacksByName();

        assertThat(callbacks.get("searchFlights").call("{\"origin\":\"上海\",\"destination\":\"杭州\"}"))
                .contains("flightNumber", "departureTime", "arrivalTime", "price");
        assertThat(callbacks.get("searchHotels").call("{\"destination\":\"杭州\",\"maxPricePerNight\":0}"))
                .contains("name", "area", "pricePerNight", "rating");
        assertThat(callbacks.get("searchAttractions").call("{\"destination\":\"杭州\"}"))
                .contains("ticketPrice", "openTime", "closeTime", "closedDays", "recommendedDurationMinutes");
        assertThat(callbacks.get("queryWeather").call("{\"destination\":\"杭州\",\"date\":\"2026-10-01\"}"))
                .contains("weather", "minTemperature", "maxTemperature", "precipitationProbability");
    }

    @Test
    void allToolsReturnEmptyListsForUnknownCity() {
        assertThat(travelTools.searchFlights("上海", "火星市")).isEmpty();
        assertThat(travelTools.searchHotels("火星市", 0)).isEmpty();
        assertThat(travelTools.searchAttractions("火星市")).isEmpty();
        assertThat(travelTools.queryWeather("火星市", "2026-10-01")).isEmpty();
    }

    private Map<String, ToolCallback> callbacksByName() {
        return Arrays.stream(MethodToolCallbackProvider.builder()
                        .toolObjects(travelTools)
                        .build()
                        .getToolCallbacks())
                .collect(Collectors.toMap(
                        callback -> callback.getToolDefinition().name(),
                        Function.identity()));
    }

    private void assertTool(ToolCallback callback, String... expectedMetadata) {
        assertThat(callback.getToolDefinition().description()).isNotBlank();
        String metadata = callback.getToolDefinition().description()
                + callback.getToolDefinition().inputSchema();
        assertThat(metadata).contains(expectedMetadata);
    }
}
