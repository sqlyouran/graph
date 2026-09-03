package com.looptrip;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * 偏好学习器：把修改记录按字段分组汇总成候选。
 * 同一会话里反复修改只算一次（取该会话最后一条）；频次不足的候选也保留，不过滤——
 * 过滤掉它们，PromotionPolicy 的 WAIT 就没有真实输入。
 */
@Component
public class PreferenceLearner {

    private static final Pattern CONDITION = Pattern.compile("^当\\s*(.{1,8}?)时\\s*[，,：:]?\\s*(.+)$");

    public List<PreferenceCandidate> analyze(List<RevisionRecord> history) {
        Map<String, List<RevisionRecord>> byField = new LinkedHashMap<>();
        for (RevisionRecord record : history) {
            byField.computeIfAbsent(record.field(), key -> new ArrayList<>()).add(record);
        }
        List<PreferenceCandidate> candidates = new ArrayList<>();
        for (Map.Entry<String, List<RevisionRecord>> entry : byField.entrySet()) {
            candidates.add(summarize(entry.getKey(), entry.getValue()));
        }
        return candidates;
    }

    private PreferenceCandidate summarize(String field, List<RevisionRecord> records) {
        Map<String, RevisionRecord> lastPerSession = new LinkedHashMap<>();
        for (RevisionRecord record : records) {
            lastPerSession.put(record.sessionId(), record);
        }
        List<RevisionRecord> perSession = List.copyOf(lastPerSession.values());
        RevisionRecord latest = perSession.get(perSession.size() - 1);
        boolean numeric = latest.direction() != null;
        boolean consistent = numeric
                ? perSession.stream().allMatch(item -> latest.direction().equals(item.direction()))
                : perSession.stream().allMatch(item -> item.toValue().equals(latest.toValue()));
        String condition = null;
        String content;
        if (numeric) {
            content = field + (latest.direction() > 0 ? "倾向于上调" : "倾向于下调");
        } else {
            Matcher matcher = CONDITION.matcher(latest.toValue().trim());
            if (matcher.matches()) {
                condition = matcher.group(1).trim();
                content = matcher.group(2).trim();
            } else {
                content = latest.toValue().trim();
            }
        }
        return new PreferenceCandidate(stableId(field, content, condition), field, content, condition,
                latest.direction(), consistent, List.copyOf(lastPerSession.keySet()), latest.occurredAt());
    }

    static String stableId(String field, String content, String condition) {
        String key = field + "|" + content + "|" + (condition == null ? "" : condition);
        return Integer.toHexString(key.hashCode());
    }
}
