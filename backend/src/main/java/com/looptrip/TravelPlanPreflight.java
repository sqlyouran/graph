package com.looptrip;

import java.util.List;

import org.springframework.stereotype.Component;

@Component
public class TravelPlanPreflight {
    private final TravelDataService data;

    public TravelPlanPreflight(TravelDataService data) { this.data = data; }

    PreflightResult check(PlanRequest request) {
        List<HotelFact> hotels = data.searchHotels(request.destination(), 0);
        List<AttractionFact> attractions = data.searchAttractions(request.destination());
        if (hotels.isEmpty() && attractions.isEmpty()) return stop(PlanningTerminalState.UNSUPPORTED, "P0_UNSUPPORTED",
                "事实快照不覆盖目的地", request.destination(), "选择快照已覆盖的城市");
        for (String required : request.mustVisit()) {
            boolean found = attractions.stream().anyMatch(item -> matches(required, item.name()));
            if (!found) return stop(PlanningTerminalState.INFEASIBLE, "P1_MUST_VISIT",
                    "必去景点在事实快照中查不到", required, "移除或更正该必去景点");
        }
        List<FlightFact> outbound = data.searchFlights(request.origin(), request.destination());
        List<FlightFact> inbound = data.searchFlights(request.destination(), request.origin());
        List<HotelFact> affordableHotels = data.searchHotels(request.destination(), request.maxHotelPrice());
        if (outbound.isEmpty() || inbound.isEmpty() || affordableHotels.isEmpty()) return stop(PlanningTerminalState.INFEASIBLE,
                "P2_NO_FEASIBLE_FACTS", "缺少满足请求的往返航班或酒店", "outbound=" + outbound.size()
                        + ", inbound=" + inbound.size() + ", hotels=" + affordableHotels.size(), "调整日期、出发地或酒店限价");
        long minimum = outbound.stream().mapToLong(FlightFact::price).min().orElseThrow()
                + inbound.stream().mapToLong(FlightFact::price).min().orElseThrow()
                + (long) Math.max(0, request.days() - 1) * affordableHotels.stream().mapToLong(HotelFact::pricePerNight).min().orElseThrow()
                + request.mustVisit().stream().mapToLong(name -> attractions.stream().filter(item -> matches(name, item.name()))
                        .mapToLong(AttractionFact::ticketPrice).min().orElse(0)).sum();
        if (request.budget() < minimum) return stop(PlanningTerminalState.INFEASIBLE, "P3_MINIMUM_BUDGET",
                "预算低于快照可计算的最低可行成本", request.budget() + "<" + minimum, "提高预算至至少 " + minimum + " 元");
        return PreflightResult.feasible();
    }

    private boolean matches(String expected, String actual) {
        String left = expected == null ? "" : expected.trim().replace("景区", "").replace("寺", "");
        String right = actual == null ? "" : actual.trim().replace("景区", "").replace("寺", "");
        return !left.isBlank() && (right.contains(left) || left.contains(right));
    }
    private PreflightResult stop(PlanningTerminalState state, String code, String reason, String evidence, String suggestion) {
        return new PreflightResult(state, new PlanningStopReport(code, reason, evidence, suggestion));
    }
}
