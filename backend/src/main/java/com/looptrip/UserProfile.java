package com.looptrip;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * 用户画像：确认过的偏好集合。召回 = 条件匹配 + 条件成立 + 未被本次请求覆盖，
 * 再按置信度、新鲜度排序取前 maxRecall 条——记忆不是越多越好，是越准越好。
 */
public record UserProfile(String userId, List<Preference> preferences) {

    public UserProfile {
        preferences = preferences == null ? List.of() : List.copyOf(preferences);
    }

    public static UserProfile empty() {
        return new UserProfile(null, List.of());
    }

    public List<Preference> relevantPreferences(PlanRequest request) {
        return relevantPreferences(request, 8);
    }

    public List<Preference> relevantPreferences(PlanRequest request, int maxRecall) {
        return preferences.stream()
                .filter(preference -> preference.appliesTo(request))
                .filter(preference -> preference.conditionHolds(request))
                .filter(preference -> !preference.isOverriddenBy(request))
                .sorted(Comparator.comparingDouble(Preference::confidence).reversed()
                        .thenComparing(preference -> preference.lastUsedAt() == null
                                ? Instant.EPOCH : preference.lastUsedAt(), Comparator.reverseOrder())
                        .thenComparing(Preference::text))
                .limit(maxRecall)
                .toList();
    }
}
