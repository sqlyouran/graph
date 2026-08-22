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
            OpeningHoursConstraintCheck openingHours) {
        checks = List.of(budget, timeConflict, closedDay, openingHours);
    }

    public List<ConstraintCheckResult> review(PlanRequest request, TripPlan plan) {
        return checks.stream().map(check -> check.check(request, plan)).toList();
    }
}
