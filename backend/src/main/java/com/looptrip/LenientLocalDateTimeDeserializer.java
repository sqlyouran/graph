package com.looptrip;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

/**
 * 模型偶尔给时间带上时区偏移后缀（2026-10-01T08:10:00+08:00），
 * Jackson 的 LocalDateTime 默认格式会因此报错，把整份计划拖进 fact-fallback。
 * 这里宽容处理：先按本地时间解析，失败再按偏移时间解析并取墙钟时间
 * （课程快照全为境内场景，不做跨时区换算）。
 */
public class LenientLocalDateTimeDeserializer extends JsonDeserializer<LocalDateTime> {

    // 无参构造是 protected，这里复用 Jackson 自带的单例：JavaTimeModule 注册的默认实现就是它。
    private static final com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer DELEGATE =
            com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer.INSTANCE;

    @Override
    public LocalDateTime deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        // 会话持久化等裸 mapper 链路会把时间写成数字时间戳或字段数组，只有字符串才需要宽容解析，
        // 其余形态一律交还 Jackson 默认反序列化器。
        if (!parser.hasToken(JsonToken.VALUE_STRING)) {
            return DELEGATE.deserialize(parser, context);
        }
        String text = parser.getText().trim();
        try {
            return LocalDateTime.parse(text);
        } catch (DateTimeParseException localFailure) {
            return OffsetDateTime.parse(text).toLocalDateTime();
        }
    }
}
