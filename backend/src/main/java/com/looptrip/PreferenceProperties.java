package com.looptrip;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * looptrip.preference.* ：学习门槛、衰减规则、召回上限和用户身份兜底。
 */
@ConfigurationProperties(prefix = "looptrip.preference")
public record PreferenceProperties(
        String storagePath,
        String defaultUser,
        int minSessions,
        int decayDays,
        double decayDrop,
        double deleteThreshold,
        int maxRecall) {

    public PreferenceProperties {
        if (storagePath == null || storagePath.isBlank()) storagePath = "data/profiles";
        if (defaultUser == null || defaultUser.isBlank()) defaultUser = "course-demo-user";
        if (minSessions <= 0) minSessions = 3;
        if (decayDays <= 0) decayDays = 90;
        if (decayDrop <= 0) decayDrop = 0.25;
        if (deleteThreshold <= 0) deleteThreshold = 0.3;
        if (maxRecall <= 0) maxRecall = 8;
    }
}
