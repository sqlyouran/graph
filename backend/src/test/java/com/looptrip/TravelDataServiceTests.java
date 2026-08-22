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
                .containsExactly("MU5211", "HO1123", "FM9301", "CA1788");
        assertThat(service.searchHotels("杭州", 500))
                .extracting(HotelFact::name)
                .containsExactly("运河课程客栈");
        assertThat(service.searchAttractions("杭州"))
                .extracting(AttractionFact::name)
                .contains("西湖风景名胜区", "中国大运河博物馆", "灵隐飞来峰景区",
                        "雷峰塔景区", "西溪国家湿地公园", "良渚博物院");
        assertThat(service.queryWeather("杭州", "2026-10-01"))
                .singleElement()
                .satisfies(item -> assertThat(item.weather()).isEqualTo("多云"));
    }

    @Test
    void supportsOnlyExplicitCommonCitySpellings() {
        assertThat(service.searchFlights(" 上海市 ", " 浙江杭州 ")).hasSize(4);
        assertThat(service.searchHotels("浙江省杭州市", 0)).hasSize(6);
        assertThat(service.searchAttractions(" 杭州市 ")).hasSize(8);
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
    void looksUpFactsByExactOriginalNames() {
        assertThat(service.findFlightByNumber(" MU5211 ")).hasValueSatisfying(
                item -> assertThat(item.price()).isEqualTo(520));
        assertThat(service.findHotelByName("湖滨课程酒店")).hasValueSatisfying(
                item -> assertThat(item.pricePerNight()).isEqualTo(680));
        assertThat(service.findAttractionByName("中国大运河博物馆")).hasValueSatisfying(
                item -> assertThat(item.closedDays()).contains(java.time.DayOfWeek.MONDAY));
        assertThat(service.findFlightByNumber("mu5211")).isEmpty();
        assertThat(service.findHotelByName("不存在")).isEmpty();
        assertThat(service.findAttractionByName("不存在")).isEmpty();
    }

    @Test
    void enrichedSnapshotsProvideVariedConstraintFactsAndFullDemoWeek() {
        assertThat(service.searchHotels("杭州", 700))
                .extracting(HotelFact::pricePerNight)
                .contains(420, 560, 620, 680)
                .doesNotContain(760, 920);
        assertThat(service.searchAttractions("杭州"))
                .anyMatch(item -> item.ticketPrice() > 0)
                .anyMatch(item -> !item.closedDays().isEmpty())
                .anyMatch(item -> item.closeTime().isBefore(java.time.LocalTime.of(18, 0)));
        for (int day = 1; day <= 7; day++) {
            String date = "2026-10-%02d".formatted(day);
            assertThat(service.queryWeather("杭州", date)).as("杭州 " + date).hasSize(1);
            assertThat(service.queryWeather("上海", date)).as("上海 " + date).hasSize(1);
        }
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
