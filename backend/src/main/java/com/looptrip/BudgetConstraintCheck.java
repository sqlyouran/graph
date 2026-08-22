package com.looptrip;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class BudgetConstraintCheck implements TripPlanConstraint {
    private final TravelDataService travelData;

    public BudgetConstraintCheck(TravelDataService travelData) {
        this.travelData = travelData;
    }

    @Override
    public ConstraintCheckResult check(PlanRequest request, TripPlan plan) {
        List<String> evidence = new ArrayList<>();
        List<String> suggestions = new ArrayList<>();
        int flightsCost = flightCost("去程", plan.outboundFlight(), evidence, suggestions);
        flightsCost += flightCost("返程", plan.returnFlight(), evidence, suggestions);

        int hotelCost = 0;
        int nights = Math.max(0, request.days() - 1);
        for (int offset = 0; offset < nights; offset++) {
            LocalDate nightDate = request.startDate().plusDays(offset);
            TripDayPlan day = plan.dailyPlans().stream()
                    .filter(item -> nightDate.equals(item.date())).findFirst().orElse(null);
            if (day == null || day.hotel() == null || !StringUtils.hasText(day.hotel().name())) {
                evidence.add(nightDate + " 缺少酒店名称，无法核价");
                suggestions.add("为 " + nightDate + " 补充快照中的酒店原名");
                continue;
            }
            var fact = travelData.findHotelByName(day.hotel().name());
            if (fact.isEmpty()) {
                evidence.add("酒店“" + day.hotel().name() + "”在事实快照中不存在");
                suggestions.add("将酒店改为事实快照中的原始名称");
                continue;
            }
            HotelFact hotel = fact.get();
            hotelCost += hotel.pricePerNight();
            evidence.add(nightDate + " 住宿“" + hotel.name() + "”=" + hotel.pricePerNight() + " 元");
            if (hotel.pricePerNight() > request.maxHotelPrice()) {
                suggestions.add("将“" + hotel.name() + "”替换为每晚不超过 " + request.maxHotelPrice() + " 元的酒店");
            }
        }

        int ticketsCost = 0;
        Set<String> countedAttractions = new HashSet<>();
        for (TripDayPlan day : plan.dailyPlans()) {
            for (TripActivity activity : day.activities()) {
                if (!TripActivityClassifier.isAttraction(activity)) continue;
                if (!StringUtils.hasText(activity.name())) {
                    evidence.add("景点活动缺少名称，无法核价");
                    suggestions.add("为景点活动补充事实快照中的原始名称");
                    continue;
                }
                String name = activity.name().trim();
                if (!countedAttractions.add(name)) continue;
                var fact = travelData.findAttractionByName(name);
                if (fact.isEmpty()) {
                    evidence.add("景点“" + name + "”在事实快照中不存在");
                    suggestions.add("将景点改为事实快照中的原始名称，或删除该活动");
                    continue;
                }
                ticketsCost += fact.get().ticketPrice();
                evidence.add("去重门票“" + name + "”=" + fact.get().ticketPrice() + " 元");
            }
        }

        int total = flightsCost + hotelCost + ticketsCost;
        evidence.add("事实总价=" + flightsCost + "（航班）+" + hotelCost + "（" + nights
                + " 晚住宿）+" + ticketsCost + "（去重门票）=" + total + " 元；预算=" + request.budget() + " 元");
        if (total > request.budget()) suggestions.add("降低航班、酒店或门票成本，使事实总价不超过 " + request.budget() + " 元");

        boolean passed = suggestions.isEmpty();
        if (passed) suggestions.add("无需修改，事实总价和酒店每晚价格均满足限制");
        return new ConstraintCheckResult("C1", "预算", ConstraintSeverity.HARD, passed, evidence, suggestions);
    }

    private int flightCost(String direction, TripFlight flight, List<String> evidence, List<String> suggestions) {
        if (flight == null || !StringUtils.hasText(flight.flightNumber())) {
            evidence.add(direction + "缺少航班号，无法核价");
            suggestions.add("为" + direction + "补充事实快照中的航班号");
            return 0;
        }
        var fact = travelData.findFlightByNumber(flight.flightNumber());
        if (fact.isEmpty()) {
            evidence.add(direction + "航班“" + flight.flightNumber() + "”在事实快照中不存在");
            suggestions.add("将" + direction + "航班改为事实快照中的航班号");
            return 0;
        }
        evidence.add(direction + "航班“" + fact.get().flightNumber() + "”=" + fact.get().price() + " 元");
        return fact.get().price();
    }
}
