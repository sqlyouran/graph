package com.looptrip;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.springframework.stereotype.Component;

/**
 * 7 节点 14 边 + 一张交卷名单：preflight 通过后四路事实同时出发，
 * 收齐"数据或失败说明"后 generate 只启动一次；返工边不在 join 组内，不重查事实。
 * 事实节点只读快照、只交回自己那一路的交代，失败矩阵的裁决留给 validate。
 */
@Component
public class TravelGraphFactory {

    private final TravelDataService dataService;
    private final PlanningEventSink eventSink;

    public TravelGraphFactory(TravelDataService dataService, PlanningEventSink eventSink) {
        this.dataService = dataService;
        this.eventSink = eventSink;
    }

    public GraphDefinition<GraphState> build(
            Function<GraphState, NodeResult> preflight,
            Function<GraphState, NodeResult> generate,
            Function<GraphState, NodeResult> validate) {
        return GraphDefinition.<GraphState>builder()
                .start("START")
                .node("preflight", preflight)
                .node("flights", this::runFlightsNode)
                .node("hotels", this::runHotelsNode)
                .node("attractions", this::runAttractionsNode)
                .node("weather", this::runWeatherNode)
                .node("generate", generate)
                .node("validate", validate)
                .terminals("END", "GUARDED", "PREFLIGHT_FAILED")
                .edge("START", "preflight", "always")
                .edge("preflight", "flights", "feasible")
                .edge("preflight", "hotels", "feasible")
                .edge("preflight", "attractions", "feasible")
                .edge("preflight", "weather", "feasible")
                .edge("preflight", "PREFLIGHT_FAILED", "infeasible")
                .edge("flights", "generate", "always")
                .edge("hotels", "generate", "always")
                .edge("attractions", "generate", "always")
                .edge("weather", "generate", "always")
                .edge("generate", "validate", "always")
                .edge("validate", "END", "accepted")
                .edge("validate", "generate", "needs_revision")
                .edge("validate", "GUARDED", "cannot_deliver")
                .join("generate", "flights", "hotels", "attractions", "weather")
                .build();
    }

    private NodeResult runFlightsNode(GraphState state) {
        PlanRequest request = state.request();
        if (dataService == null) {
            return notConnected("flights");
        }
        return query("flights", 1, () -> {
            List<FlightFact> facts = dataService.searchFlights(request.origin(), request.destination());
            if (facts.isEmpty()) {
                throw new FactQueryFailed(request.origin() + "至" + request.destination() + "暂无航班");
            }
            List<String> lines = facts.stream()
                    .map(item -> item.flightNumber() + " " + item.departureTime().toLocalTime()
                            + "-" + item.arrivalTime().toLocalTime() + " " + item.price() + "元")
                    .toList();
            return slot("flights", "查询成功", String.join("；", lines));
        });
    }

    private NodeResult runHotelsNode(GraphState state) {
        PlanRequest request = state.request();
        if (dataService == null) {
            return notConnected("hotels");
        }
        return query("hotels", 1, () -> {
            List<HotelFact> facts = dataService.searchHotels(request.destination(), request.maxHotelPrice());
            if (facts.isEmpty()) {
                throw new FactQueryFailed("限价 " + request.maxHotelPrice() + " 元内暂无酒店");
            }
            List<String> lines = facts.stream()
                    .map(item -> item.name() + "（" + item.area() + "，" + item.pricePerNight() + "元/晚）")
                    .toList();
            return slot("hotels", "查询成功", String.join("；", lines));
        });
    }

    private NodeResult runAttractionsNode(GraphState state) {
        PlanRequest request = state.request();
        if (dataService == null) {
            return notConnected("attractions");
        }
        return query("attractions", 0, () -> {
            List<AttractionFact> facts = dataService.searchAttractions(request.destination());
            if (facts.isEmpty()) {
                throw new FactQueryFailed(request.destination() + "暂无景点数据");
            }
            List<String> lines = facts.stream()
                    .map(item -> item.name() + "（" + item.ticketPrice() + "元，"
                            + item.openTime() + "-" + item.closeTime() + "）")
                    .toList();
            return slot("attractions", "查询成功", String.join("；", lines));
        });
    }

    private NodeResult runWeatherNode(GraphState state) {
        PlanRequest request = state.request();
        if (dataService == null) {
            return notConnected("weather");
        }
        return query("weather", 0, () -> {
            List<String> lines = new ArrayList<>();
            for (int day = 0; day < request.days(); day++) {
                String date = request.startDate().plusDays(day).toString();
                List<WeatherFact> facts = dataService.queryWeather(request.destination(), date);
                if (facts.isEmpty()) {
                    continue;
                }
                WeatherFact item = facts.getFirst();
                lines.add(date + " " + item.weather() + " " + item.minTemperature()
                        + "-" + item.maxTemperature() + "℃ 降水" + item.precipitationProbability() + "%");
            }
            if (lines.isEmpty()) {
                throw new FactQueryFailed(request.destination() + " 行程日期暂无天气数据");
            }
            return slot("weather", "查询成功", String.join("；", lines));
        });
    }

    /** 幂等读操作才允许重试：retries=1 表示失败后再查一次；增强信息不重试，直接带原因降级。 */
    private NodeResult query(String source, int retries, java.util.function.Supplier<PlanningFacts.FactSlot> supplier) {
        emit(PlanningEventType.FACT_QUERY_STARTED, "事实查询出发：" + source, Map.of("source", source));
        int attempts = 0;
        while (true) {
            try {
                PlanningFacts.FactSlot result = supplier.get();
                emit(PlanningEventType.FACT_QUERY_COMPLETED, "事实查询交卷：" + source,
                        Map.of("source", source, "ok", true, "attempts", attempts + 1));
                return NodeResult.of(StateDelta.factReported(result));
            } catch (RuntimeException exception) {
                if (attempts < retries) {
                    attempts++;
                    continue;
                }
                String reason = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
                emit(PlanningEventType.FACT_QUERY_FAILED, "事实查询降级：" + source + "（" + reason + "）",
                        Map.of("source", source, "ok", false, "reason", reason, "attempts", attempts + 1));
                return NodeResult.of(StateDelta.factReported(
                        new PlanningFacts.FactSlot(source, false, reason, "")));
            }
        }
    }

    private PlanningFacts.FactSlot slot(String source, String note, String text) {
        return new PlanningFacts.FactSlot(source, true, note, text);
    }

    /** 测试缝：事实源未接入时四路交"不参与失败矩阵"的空交代，生产构造器永远注入真实快照源。 */
    private NodeResult notConnected(String source) {
        return NodeResult.of(StateDelta.factReported(slot(source, "事实源未接入", "")));
    }

    private void emit(PlanningEventType type, String message, Map<String, Object> details) {
        try {
            eventSink.emit(type, message, details);
        } catch (RuntimeException ignored) {
            // Observability must not control the planning flow.
        }
    }

    /** 查询失败的明确交代：降级是返回值，不是异常往外抛。 */
    static final class FactQueryFailed extends RuntimeException {
        FactQueryFailed(String message) {
            super(message);
        }
    }
}
