package com.looptrip;

import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TravelDataServiceTests {

    private TravelDataService service;

    @BeforeEach
    void setUp() {
        service = new TravelDataService(new ObjectMapper().findAndRegisterModules(), new DefaultResourceLoader());
        service.loadFacts();
    }

    @Test
    void queriesAllFourFactTypes() {
        assertThat(service.searchFlights("上海", "杭州"))
                .extracting(FlightFact::flightNumber)
                .containsExactly("MU5211", "HO1123");
        assertThat(service.searchHotels("杭州", 500))
                .extracting(HotelFact::name)
                .containsExactly("运河课程客栈");
        assertThat(service.searchAttractions("杭州"))
                .extracting(AttractionFact::name)
                .containsExactly("西湖风景名胜区", "中国大运河博物馆");
        assertThat(service.queryWeather("杭州", "2026-10-01"))
                .singleElement()
                .satisfies(item -> assertThat(item.weather()).isEqualTo("多云"));
    }

    @Test
    void supportsOnlyExplicitCommonCitySpellings() {
        assertThat(service.searchFlights(" 上海市 ", " 浙江杭州 ")).hasSize(2);
        assertThat(service.searchHotels("浙江省杭州市", 0)).hasSize(2);
        assertThat(service.searchAttractions(" 杭州市 ")).hasSize(2);
        assertThat(service.queryWeather("浙江省杭州", " 2026-10-02 ")).hasSize(1);
    }

    @Test
    void returnsEmptyListsForUnknownCitiesAndDates() {
        assertThat(service.searchFlights("北京", "火星市")).isEmpty();
        assertThat(service.searchHotels("火星市", 0)).isEmpty();
        assertThat(service.searchAttractions("火星市")).isEmpty();
        assertThat(service.queryWeather("火星市", "2026-10-01")).isEmpty();
        assertThat(service.queryWeather("杭州", "2099-01-01")).isEmpty();
        assertThat(service.queryWeather("杭州", "明天")).isEmpty();
    }

    @Test
    void reportsMalformedSnapshotAtStartup() {
        TravelDataService brokenService = serviceWithFlights("{not-json");

        assertThatThrownBy(brokenService::loadFacts)
                .isInstanceOf(TravelDataConfigurationException.class)
                .hasMessageContaining("Cannot parse travel facts file")
                .hasMessageContaining("facts/flights.json");
    }

    @Test
    void reportsMissingCriticalFieldAtStartup() {
        String invalidFlight = """
                [{
                  "origin": "上海",
                  "destination": "杭州",
                  "departureTime": "2026-10-01T08:10:00",
                  "arrivalTime": "2026-10-01T09:15:00",
                  "price": 520
                }]
                """;
        TravelDataService brokenService = serviceWithFlights(invalidFlight);

        assertThatThrownBy(brokenService::loadFacts)
                .isInstanceOf(TravelDataConfigurationException.class)
                .hasMessageContaining("facts/flights.json")
                .hasMessageContaining("flightNumber must not be blank");
    }

    private TravelDataService serviceWithFlights(String flightsJson) {
        ResourceLoader resourceLoader = new DefaultResourceLoader() {
            @Override
            public Resource getResource(String location) {
                if (location.endsWith("flights.json")) {
                    return new ByteArrayResource(flightsJson.getBytes(StandardCharsets.UTF_8));
                }
                return super.getResource(location);
            }
        };
        return new TravelDataService(new ObjectMapper().findAndRegisterModules(), resourceLoader);
    }
}
