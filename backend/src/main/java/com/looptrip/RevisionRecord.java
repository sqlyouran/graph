package com.looptrip;

import java.time.Instant;

/**
 * 一次被 RevisionPolicy 接受的字段修改留下的学习原料。
 * direction 仅数值字段有值（+1 上调 / -1 下调），文本字段为 null。
 */
public record RevisionRecord(
        String sessionId,
        String field,
        String fromValue,
        String toValue,
        Integer direction,
        Instant occurredAt) {
}
