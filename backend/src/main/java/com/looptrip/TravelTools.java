package com.looptrip;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class TravelTools {

    private static final Logger log = LoggerFactory.getLogger(TravelTools.class);

    private final TravelDataService travelDataService;
    private final PlanningEventSink eventSink;

    public TravelTools(TravelDataService travelDataService, PlanningEventSink eventSink) {
        this.travelDataService = travelDataService;
        this.eventSink = eventSink;
    }

    @Tool(
            name = "searchFlights",
            description = """
                    查询课程快照中的航班事实。当用户给出明确出发地和目的地并需要航班号、出发/到达时间或价格时调用。
                    参数 origin 和 destination 使用中文城市名，例如 origin=上海、destination=杭州。
                    返回 flightNumber、origin、destination、departureTime、arrivalTime、price；时间为 ISO-8601，价格为人民币数值。
                    快照仅覆盖课程示例，覆盖范围外返回空列表，不得用模型常识补充航班。
                    """)
    public List<FlightFact> searchFlights(
            @ToolParam(description = "出发城市，例如：上海、上海市") String origin,
            @ToolParam(description = "到达城市，例如：杭州、杭州市、浙江杭州") String destination) {
        List<FlightFact> results = travelDataService.searchFlights(origin, destination);
        log.info("tool=searchFlights origin={} destination={} resultCount={}", origin, destination, results.size());
        emitToolEvent("查询航班", Map.of(
                "tool", "searchFlights",
                "origin", String.valueOf(origin),
                "destination", String.valueOf(destination),
                "resultCount", results.size(),
                "summary", flightSummary(origin, destination, results),
                "candidates", results.stream().map(this::flightCandidate).toList()));
        return results;
    }

    @Tool(
            name = "searchHotels",
            description = """
                    查询课程快照中的酒店事实。当用户需要目的地酒店名称、区域、每晚价格或评分时调用。
                    参数 destination 使用中文城市名，例如 destination=杭州；maxPricePerNight 是人民币数值，0 表示不限每晚价格。
                    返回 name、city、area、pricePerNight、rating。覆盖范围外或没有符合限价的酒店时返回空列表，绝不返回默认酒店或平均价。
                    """)
    public List<HotelFact> searchHotels(
            @ToolParam(description = "目的城市，例如：杭州、杭州市、浙江杭州") String destination,
            @ToolParam(description = "最高每晚价格，人民币数值；0 表示不限价，例如：600") int maxPricePerNight) {
        List<HotelFact> results = travelDataService.searchHotels(destination, maxPricePerNight);
        log.info("tool=searchHotels destination={} maxPricePerNight={} resultCount={}",
                destination, maxPricePerNight, results.size());
        emitToolEvent("查询酒店", Map.of(
                "tool", "searchHotels",
                "destination", String.valueOf(destination),
                "maxPricePerNight", maxPricePerNight,
                "resultCount", results.size(),
                "summary", hotelSummary(results),
                "candidates", results.stream().map(this::hotelCandidate).toList()));
        return results;
    }

    @Tool(
            name = "searchAttractions",
            description = """
                    查询课程快照中的景点事实。当用户需要目的地景点、门票、每日开放时间、闭馆日或建议游玩时长时调用。
                    参数 destination 使用中文城市名，例如 destination=杭州。
                    返回 name、city、area、ticketPrice、openTime、closeTime、closedDays、recommendedDurationMinutes。
                    覆盖范围外返回空列表，不得用模型常识补充开放时间、闭馆日或门票。
                    """)
    public List<AttractionFact> searchAttractions(
            @ToolParam(description = "目的城市，例如：杭州、杭州市、浙江杭州") String destination) {
        List<AttractionFact> results = travelDataService.searchAttractions(destination);
        log.info("tool=searchAttractions destination={} resultCount={}", destination, results.size());
        emitToolEvent("查询景点", Map.of(
                "tool", "searchAttractions",
                "destination", String.valueOf(destination),
                "resultCount", results.size(),
                "summary", attractionSummary(results),
                "candidates", results.stream().map(this::attractionCandidate).toList()));
        return results;
    }

    @Tool(
            name = "queryWeather",
            description = """
                    查询课程天气快照。当用户给出目的地和明确日期并需要天气、最低/最高温度或降水概率时调用。
                    参数 destination 使用中文城市名；date 必须为 yyyy-MM-dd，例如 destination=杭州、date=2026-10-01。
                    返回 city、date、weather、minTemperature、maxTemperature、precipitationProbability。
                    城市或日期不在快照范围时返回空列表，不得改用其他日期或模型常识补天气。
                    """)
    public List<WeatherFact> queryWeather(
            @ToolParam(description = "目的城市，例如：杭州、杭州市、浙江杭州") String destination,
            @ToolParam(description = "查询日期，严格使用 yyyy-MM-dd，例如：2026-10-01") String date) {
        List<WeatherFact> results = travelDataService.queryWeather(destination, date);
        log.info("tool=queryWeather destination={} date={} resultCount={}", destination, date, results.size());
        emitToolEvent("查询天气", Map.of(
                "tool", "queryWeather",
                "destination", String.valueOf(destination),
                "date", String.valueOf(date),
                "resultCount", results.size(),
                "summary", weatherSummary(date, results),
                "candidates", results.stream().map(this::weatherCandidate).toList()));
        return results;
    }

    private String flightSummary(String origin, String destination, List<FlightFact> results) {
        if (results.isEmpty()) return origin + "至" + destination + "暂无航班";
        return origin + "至" + destination + "找到 " + results.size() + " 班：" + results.stream()
                .map(item -> item.flightNumber() + " " + item.departureTime().toLocalTime()
                        + "，" + item.price() + " 元")
                .reduce((left, right) -> left + "；" + right).orElse("");
    }

    private String hotelSummary(List<HotelFact> results) {
        if (results.isEmpty()) return "限价内暂无酒店";
        return "找到 " + results.size() + " 家：" + results.stream()
                .map(item -> item.name() + "（" + item.area() + "，" + item.pricePerNight() + " 元/晚）")
                .reduce((left, right) -> left + "；" + right).orElse("");
    }

    private String attractionSummary(List<AttractionFact> results) {
        if (results.isEmpty()) return "暂无景点数据";
        return "找到 " + results.size() + " 个：" + results.stream()
                .map(item -> item.name() + "（" + item.ticketPrice() + " 元，"
                        + item.openTime() + "-" + item.closeTime() + "）")
                .reduce((left, right) -> left + "；" + right).orElse("");
    }

    private String weatherSummary(String date, List<WeatherFact> results) {
        if (results.isEmpty()) return date + " 暂无天气数据";
        WeatherFact item = results.getFirst();
        return item.date() + " " + item.weather() + "，" + item.minTemperature() + "-"
                + item.maxTemperature() + "℃，降水概率 " + item.precipitationProbability() + "%";
    }

    private Map<String, Object> flightCandidate(FlightFact item) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", item.flightNumber()); result.put("label", item.flightNumber());
        result.put("departureTime", item.departureTime().toString()); result.put("arrivalTime", item.arrivalTime().toString());
        result.put("price", item.price()); return result;
    }

    private Map<String, Object> hotelCandidate(HotelFact item) {
        return Map.of("id", item.name(), "label", item.name(), "area", item.area(),
                "pricePerNight", item.pricePerNight(), "rating", item.rating());
    }

    private Map<String, Object> attractionCandidate(AttractionFact item) {
        return Map.of("id", item.name(), "label", item.name(), "area", item.area(),
                "ticketPrice", item.ticketPrice(), "openTime", item.openTime().toString(),
                "closeTime", item.closeTime().toString());
    }

    private Map<String, Object> weatherCandidate(WeatherFact item) {
        return Map.of("id", item.date().toString(), "label", item.weather(), "date", item.date().toString(),
                "minTemperature", item.minTemperature(), "maxTemperature", item.maxTemperature(),
                "precipitationProbability", item.precipitationProbability());
    }

    private void emitToolEvent(String message, Map<String, Object> details) {
        try {
            eventSink.emit(PlanningEventType.TOOL_CALLED, message, details);
        } catch (RuntimeException exception) {
            log.warn("Failed to record tool event tool={}", details.get("tool"), exception);
        }
    }
}
