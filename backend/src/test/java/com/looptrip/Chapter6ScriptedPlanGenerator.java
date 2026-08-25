package com.looptrip;

import java.time.Clock;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

final class Chapter6ScriptedPlanGenerator implements PlanGenerator {
    enum Scene { PASS, CONVERGE, C6_STUCK, TOKEN, TIME, OSCILLATE, RETRY_SUCCESS, RETRY_FAIL }

    private final Scene scene;
    private final Clock clock;
    private final AtomicInteger calls = new AtomicInteger();

    Chapter6ScriptedPlanGenerator(Scene scene, Clock clock) {
        this.scene = scene;
        this.clock = clock;
    }

    @Override
    public PlanGenerationResult generate(PlanGenerationInput input) {
        int call = calls.incrementAndGet();
        clock.instant(); // 课堂回放显式使用注入时钟，不读取系统时间。
        return switch (scene) {
            case PASS -> success(completeWithMustVisit(), 100);
            case CONVERGE -> switch (input.round()) {
                case 1 -> success(TestTripPlans.complete(3), 100);
                case 2 -> success(withBadOutbound(completeWithMustVisit(), "BAD-C1-ROUND-2"), 100);
                default -> success(completeWithMustVisit(), 100);
            };
            case C6_STUCK -> success(TestTripPlans.complete(3), 100);
            case TOKEN -> success(withBadOutbound(completeWithMustVisit(),
                    "BAD-C1-" + input.round() + "-" + "X".repeat(10_000)), 100);
            case TIME -> success(withBadOutbound(completeWithMustVisit(), "BAD-TIME-" + input.round()), 4_000);
            case OSCILLATE -> input.round() % 2 == 1
                    ? success(TestTripPlans.complete(3), 100)
                    : success(withBadOutbound(completeWithMustVisit(), "BAD-C1-" + input.round()), 100);
            case RETRY_SUCCESS -> {
                if (call <= 2) throw new ModelCallException("HTTP 429", null, true);
                yield success(completeWithMustVisit(), 100);
            }
            case RETRY_FAIL -> throw new ModelCallException("HTTP 503", null, true);
        };
    }

    int calls() { return calls.get(); }

    private PlanGenerationResult success(TripPlan plan, long elapsedMs) {
        return PlanGenerationResult.success(plan, "chapter6-scripted", elapsedMs);
    }

    private TripPlan completeWithMustVisit() {
        TripPlan base = TestTripPlans.complete(3);
        List<TripDayPlan> days = new ArrayList<>(base.dailyPlans());
        TripDayPlan first = days.getFirst();
        List<TripActivity> activities = new ArrayList<>(first.activities());
        activities.add(new TripActivity("灵隐飞来峰景区", "ATTRACTION", LocalTime.of(11, 0),
                LocalTime.of(14, 0), "西湖区", 45));
        days.set(0, new TripDayPlan(first.date(), first.hotel(), activities));
        return new TripPlan(base.origin(), base.destination(), base.startDate(), base.days(),
                base.outboundFlight(), base.returnFlight(), days);
    }

    private TripPlan withBadOutbound(TripPlan base, String flightNumber) {
        TripFlight old = base.outboundFlight();
        TripFlight bad = new TripFlight(flightNumber, old.origin(), old.destination(), old.departureTime(),
                old.arrivalTime(), old.price());
        return new TripPlan(base.origin(), base.destination(), base.startDate(), base.days(), bad,
                base.returnFlight(), base.dailyPlans());
    }
}
