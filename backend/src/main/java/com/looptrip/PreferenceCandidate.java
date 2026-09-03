package com.looptrip;

import java.time.Instant;
import java.util.List;

/**
 * 按字段汇总出的偏好候选：content 是文本字段的目标值或数值字段的方向描述，
 * condition 是"当孩子时"这类场景条件，sessionIds 去重后就是频次，
 * directionConsistent 记录这组修改的方向是否稳定（数值看符号、文本看目标值是否同一）。
 */
public record PreferenceCandidate(
        String id,
        String field,
        String content,
        String condition,
        Integer direction,
        boolean directionConsistent,
        List<String> sessionIds,
        Instant updatedAt) {

    public PreferenceCandidate {
        sessionIds = sessionIds == null ? List.of() : List.copyOf(sessionIds);
    }

    public int sessionCount() {
        return sessionIds.size();
    }
}
