package com.looptrip;

import java.time.Instant;
import java.util.List;

/**
 * 偏好条目：destination 为适用目的地（空表示不限），category 对应 PlanRequest 的可覆盖字段
 * （budget/maxHotelPrice/preferences/mustVisit，空表示纯文本偏好）。
 * 第三节补上学习链路需要的字段：置信度、适用条件、学习与确认时间、最近使用、来源会话、数值方向。
 * 覆盖语义：硬字段本次给了就压过画像；preferences 这类自由文本只有谈到同一主题才算覆盖——
 * 否则用户随口一句无关偏好就会抹掉所有文本偏好。
 */
public record Preference(
        String destination,
        String category,
        String text,
        double confidence,
        String condition,
        Instant learnedAt,
        Instant confirmedAt,
        Instant lastUsedAt,
        List<String> sourceSessions,
        Integer direction) {

    private static final List<String> TOPICS = List.of("住宿", "酒店", "民宿", "爬山", "登山", "徒步",
            "孩子", "购物", "用餐", "餐饮", "小吃", "节奏", "交通", "航班", "火车", "风景", "园林", "湖泊", "寺庙");

    public Preference {
        sourceSessions = sourceSessions == null ? List.of() : List.copyOf(sourceSessions);
    }

    public Preference(String destination, String category, String text) {
        this(destination, category, text, 1.0, null, null, null, null, List.of(), null);
    }

    public boolean appliesTo(PlanRequest request) {
        return destination == null || destination.isBlank() || destination.equals(request.destination());
    }

    public boolean conditionHolds(PlanRequest request) {
        if (condition == null || condition.isBlank()) return true;
        return request.preferences() != null && request.preferences().contains(condition);
    }

    public boolean isOverriddenBy(PlanRequest request) {
        if (category == null || category.isBlank()) return false;
        // 方向型偏好（budget倾向于上调）是跨会话的观察结论，不是具体字段值，
        // 本次请求给了数字也压不掉它——只有"值偏好"才让位给显式输入。
        if (direction != null) return false;
        return switch (category) {
            case "budget" -> request.budget() > 0;
            case "maxHotelPrice" -> request.maxHotelPrice() > 0;
            case "preferences" -> condition != null && !condition.isBlank()
                    ? !conditionHolds(request)
                    : topicOverlap(text, request.preferences());
            case "mustVisit" -> !request.mustVisit().isEmpty();
            default -> false;
        };
    }

    static boolean topicOverlap(String left, String right) {
        if (left == null || right == null) return false;
        for (String topic : TOPICS) {
            if (left.contains(topic) && right.contains(topic)) return true;
        }
        return false;
    }
}
