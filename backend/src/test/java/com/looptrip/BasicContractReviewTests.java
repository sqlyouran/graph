package com.looptrip;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BasicContractReviewTests {

    private final BasicContractReview review = new BasicContractReview();

    @Test
    void acceptsCommonChineseAndEnglishDayHeadings() {
        String markdown = """
                ## 第一天：西湖
                游览景点，晚上入住酒店。

                **第 2 天** - 运河
                参观博物馆。

                ### Day 3 · 返程
                安排返程。

                预计总花费 2000 元
                """;

        assertThat(review.review(request(), markdown).passed()).isTrue();
    }

    @Test
    void acceptsDayHeadingsWithIconsAndUnrestrictedPunctuation() {
        String markdown = """
                ### 🗓️ Day 1（10月1日）
                游览景点，晚上入住酒店。
                ### 行程｜Day 2【西湖】
                参观博物馆。
                ### **DAY3 / 返程**
                安排返程。
                预计总花费 2000 元
                """;

        assertThat(review.review(request(), markdown).passed()).isTrue();
    }

    @Test
    void reportsOnlyTheActuallyMissingDay() {
        String markdown = """
                ### 第1天
                游览景点并入住酒店。
                ### 第三日
                返程。
                预计总花费 1800 元
                """;

        assertThat(review.review(request(), markdown).problems())
                .containsExactly("缺少第 2 天安排");
    }

    @Test
    void doesNotTreatDayTenAsDayOne() {
        String markdown = """
                ## Day 10
                游览景点并入住酒店。
                预计总花费 1000 元
                """;
        PlanRequest oneDay = new PlanRequest(
                "上海", "杭州", LocalDate.of(2026, 10, 1), 1, 3000, 700, "轻松", 2);

        assertThat(review.review(oneDay, markdown).problems()).contains("缺少第 1 天安排");
    }

    private PlanRequest request() {
        return new PlanRequest("上海", "杭州", LocalDate.of(2026, 10, 1), 3, 3000, 700, "轻松", 2);
    }
}
