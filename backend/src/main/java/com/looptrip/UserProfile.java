package com.looptrip;

import java.util.List;

/**
 * 最小用户画像：本节生产流程一律传 empty()，真实的读取、学习与晋升在第三节接入。
 * relevantPreferences 只做两种基础过滤：条件不匹配的不进来，本次请求已显式给出的字段不被画像覆盖。
 */
public record UserProfile(String userId, List<Preference> preferences) {

    public UserProfile {
        preferences = preferences == null ? List.of() : List.copyOf(preferences);
    }

    public static UserProfile empty() {
        return new UserProfile(null, List.of());
    }

    public List<Preference> relevantPreferences(PlanRequest request) {
        return preferences.stream()
                .filter(preference -> preference.appliesTo(request))
                .filter(preference -> !preference.isOverriddenBy(request))
                .toList();
    }
}
