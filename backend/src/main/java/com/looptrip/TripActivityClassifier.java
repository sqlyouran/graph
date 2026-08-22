package com.looptrip;

import java.util.Locale;

final class TripActivityClassifier {
    private TripActivityClassifier() {}

    static boolean isAttraction(TripActivity activity) {
        if (activity == null) return false;
        String type = activity.type() == null ? "" : activity.type().trim().toLowerCase(Locale.ROOT);
        return !(type.contains("餐") || type.contains("吃饭") || type.contains("交通")
                || type.contains("航班") || type.contains("火车") || type.contains("打车")
                || type.contains("步行") || type.contains("transport") || type.contains("meal")
                || type.contains("food"));
    }
}
