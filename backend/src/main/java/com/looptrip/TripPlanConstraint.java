package com.looptrip;

public interface TripPlanConstraint {
    ConstraintCheckResult check(PlanRequest request, TripPlan plan);
}
