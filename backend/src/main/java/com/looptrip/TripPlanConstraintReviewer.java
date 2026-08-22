package com.looptrip;

import java.util.List;

import org.springframework.stereotype.Component;

@Component
public class TripPlanConstraintReviewer {
    private final List<TripPlanConstraint> checks;

    public TripPlanConstraintReviewer(
            BudgetConstraintCheck budget,
            TimeConflictConstraintCheck timeConflict,
            ClosedDayConstraintCheck closedDay,
            OpeningHoursConstraintCheck openingHours,
            RouteContinuityConstraintCheck routeContinuity,
            MustVisitConstraintCheck mustVisit,
            DailyPaceConstraintCheck dailyPace) {
        checks = List.of(budget, timeConflict, closedDay, openingHours, routeContinuity, mustVisit, dailyPace);
    }

    public List<ConstraintCheckResult> review(PlanRequest request, TripPlan plan) {
        return checks.stream().map(check -> check.check(request, plan)).toList();
    }
}
