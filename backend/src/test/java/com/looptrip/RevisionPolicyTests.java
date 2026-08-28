package com.looptrip;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class RevisionPolicyTests {
    private final RevisionPolicy policy = new RevisionPolicy();
    private final PlanRequest current = new PlanRequest("上海", "杭州", LocalDate.of(2026, 10, 1),
            3, 3000, 700, "轻松", List.of("西湖", "灵隐寺"), 2);

    @Test
    void allowsOnlyReusablePlanChangesAndMustVisitAdditions() {
        RevisionDecision result = policy.apply(current, new RevisionRequest(
                3500, 800, "美食", 4, List.of("西湖", "灵隐寺", "雷峰塔"), null, null, null));

        assertThat(result.allowed()).isTrue();
        assertThat(result.diff()).containsKeys("budget", "maxHotelPrice", "preferences", "maxRounds", "mustVisit");
        assertThat(result.request().mustVisit()).containsExactly("西湖", "灵隐寺", "雷峰塔");
    }

    @Test
    void rejectsRouteIdentityChangesWithNewSessionAdvice() {
        RevisionDecision destination = policy.apply(current, new RevisionRequest(
                null, null, null, null, null, "苏州", null, null));
        RevisionDecision date = policy.apply(current, new RevisionRequest(
                null, null, null, null, null, null, LocalDate.of(2026, 10, 2), null));
        RevisionDecision days = policy.apply(current, new RevisionRequest(
                null, null, null, null, null, null, null, 4));

        assertThat(List.of(destination, date, days)).allSatisfy(result -> {
            assertThat(result.allowed()).isFalse();
            assertThat(result.suggestion()).contains("开新会话");
        });
    }

    @Test
    void rejectsRemovingOrReplacingMustVisitItems() {
        RevisionDecision removed = policy.apply(current, new RevisionRequest(
                null, null, null, null, List.of("西湖"), null, null, null));
        RevisionDecision replaced = policy.apply(current, new RevisionRequest(
                null, null, null, null, List.of("西湖", "雷峰塔"), null, null, null));

        assertThat(List.of(removed, replaced)).allSatisfy(result -> {
            assertThat(result.allowed()).isFalse();
            assertThat(result.reason()).contains("只能增加", "不能删除");
            assertThat(result.suggestion()).contains("开新会话");
        });
    }
}
