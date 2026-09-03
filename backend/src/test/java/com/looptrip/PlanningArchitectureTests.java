package com.looptrip;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PlanningArchitectureTests {
    @Test
    void engineDependsOnSeedDataButNotSessionInfrastructure() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/looptrip/TravelPlanningEngine.java"));

        assertThat(source).contains("plan(PlanRequest request, PlanningSeed seed)");
        assertThat(source).doesNotContain("PlanningSessionStore", "PlanningSessionContext", "PlanningVersion",
                "ContextAssembler", "PromptDumper", "UserProfile");
    }
}
