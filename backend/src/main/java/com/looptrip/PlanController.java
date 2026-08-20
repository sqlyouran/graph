package com.looptrip;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/plan")
public class PlanController {

    private final TravelPlanningEngine planningEngine;

    public PlanController(TravelPlanningEngine planningEngine) {
        this.planningEngine = planningEngine;
    }

    @PostMapping("/ask")
    public PlanResponse ask(@RequestBody PlanRequest request) {
        return planningEngine.plan(request);
    }
}
