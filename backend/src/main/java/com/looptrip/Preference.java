package com.looptrip;

/**
 * 最小偏好条目：destination 为适用条件（空表示不限目的地），category 对应
 * PlanRequest 的可覆盖字段（budget/maxHotelPrice/preferences/mustVisit，空表示纯文本偏好）。
 * 真正的学习与存储在第三节接入，本节只提供组装器需要的过滤语义。
 */
public record Preference(String destination, String category, String text) {

    public boolean appliesTo(PlanRequest request) {
        return destination == null || destination.isBlank() || destination.equals(request.destination());
    }

    public boolean isOverriddenBy(PlanRequest request) {
        if (category == null || category.isBlank()) return false;
        return switch (category) {
            case "budget" -> request.budget() > 0;
            case "maxHotelPrice" -> request.maxHotelPrice() > 0;
            case "preferences" -> request.preferences() != null && !request.preferences().isBlank();
            case "mustVisit" -> !request.mustVisit().isEmpty();
            default -> false;
        };
    }
}
