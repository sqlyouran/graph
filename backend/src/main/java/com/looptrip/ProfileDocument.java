package com.looptrip;

import java.util.List;

/**
 * data/profiles/{userId}.json 的内容：修改历史、待确认候选、已确认偏好和学习开关。
 * 候选和确认前的一切都不进 UserProfile——确认是写入画像的唯一入口。
 */
public record ProfileDocument(
        String userId,
        boolean learningEnabled,
        List<RevisionRecord> revisionHistory,
        List<PreferenceCandidate> candidates,
        List<Preference> confirmed) {

    public ProfileDocument {
        revisionHistory = revisionHistory == null ? List.of() : List.copyOf(revisionHistory);
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        confirmed = confirmed == null ? List.of() : List.copyOf(confirmed);
    }

    public static ProfileDocument empty(String userId) {
        return new ProfileDocument(userId, true, List.of(), List.of(), List.of());
    }
}
