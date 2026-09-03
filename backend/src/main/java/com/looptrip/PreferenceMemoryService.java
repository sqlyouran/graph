package com.looptrip;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 偏好记忆的编排层：观察修改、晋升判断、确认写入、相反删除、衰减遗忘、召回触达。
 * 它只做 IO 与策略编排；汇总在 PreferenceLearner，门槛在 PromotionPolicy，
 * 召回过滤在 UserProfile——每一件事都有单独的名字，课堂上可以逐个指认。
 */
@Component
public class PreferenceMemoryService {

    private static final Logger log = LoggerFactory.getLogger(PreferenceMemoryService.class);
    private static final List<String> NUMERIC_FIELDS = List.of("budget", "maxHotelPrice", "maxRounds");

    private final ProfileRepository repository;
    private final PreferenceProperties properties;
    private final PreferenceLearner learner;
    private final PromotionPolicy policy;
    private final Clock clock;

    @Autowired
    public PreferenceMemoryService(ProfileRepository repository, PreferenceProperties properties,
            PreferenceLearner learner, PromotionPolicy policy, Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.learner = learner;
        this.policy = policy;
        this.clock = clock;
    }

    public record Forgotten(String field, String content, String reason) {
    }

    public record Observation(String field, String candidateId, PromotionDecision decision,
            int sessionCount, String condition, String content) {
    }

    public record ObservationResult(List<Observation> observations, List<Forgotten> forgotten) {
    }

    public record ProfileLoad(UserProfile profile, List<Forgotten> forgotten) {
    }

    public record Confirmation(PreferenceCandidate candidate, Preference preference) {
    }

    public ObservationResult observeRevision(String userId, String sessionId, Map<String, Object> diff) {
        ProfileDocument document = repository.load(userId);
        if (!document.learningEnabled() || diff == null || diff.isEmpty()) {
            return new ObservationResult(List.of(), List.of());
        }
        Instant now = clock.instant();
        List<RevisionRecord> history = new ArrayList<>(document.revisionHistory());
        for (Map.Entry<String, Object> entry : diff.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> change)) continue;
            String before = String.valueOf(change.get("before"));
            String after = String.valueOf(change.get("after"));
            history.add(new RevisionRecord(sessionId, entry.getKey(), before, after,
                    directionOf(entry.getKey(), change), now));
        }
        List<Forgotten> forgotten = new ArrayList<>();
        List<Preference> confirmed = new ArrayList<>();
        for (Preference preference : document.confirmed()) {
            boolean opposite = history.stream()
                    .filter(record -> record.sessionId().equals(sessionId))
                    .filter(record -> record.field().equals(preference.category()))
                    .anyMatch(record -> contradicts(preference, record));
            if (opposite) {
                forgotten.add(new Forgotten(preference.category(), preference.text(), "相反修改"));
                audit(userId, "forget", preference.text(), "相反修改");
            } else {
                confirmed.add(preference);
            }
        }
        List<PreferenceCandidate> candidates = learner.analyze(history);
        List<Observation> observations = new ArrayList<>();
        for (String field : diff.keySet()) {
            candidates.stream().filter(candidate -> candidate.field().equals(field)).findFirst()
                    .ifPresent(candidate -> observations.add(new Observation(field, candidate.id(),
                            policy.evaluate(candidate, properties.minSessions()), candidate.sessionCount(),
                            candidate.condition(), candidate.content())));
        }
        repository.save(new ProfileDocument(userId, document.learningEnabled(), history, candidates, confirmed));
        return new ObservationResult(observations, forgotten);
    }

    public Confirmation confirm(String userId, String candidateId, boolean accepted) {
        ProfileDocument document = repository.load(userId);
        PreferenceCandidate candidate = document.candidates().stream()
                .filter(item -> item.id().equals(candidateId)).findFirst().orElse(null);
        if (candidate == null) return null;
        List<PreferenceCandidate> candidates = document.candidates().stream()
                .filter(item -> !item.id().equals(candidateId)).toList();
        Preference preference = null;
        if (accepted) {
            Instant now = clock.instant();
            boolean conditional = candidate.condition() != null && !candidate.condition().isBlank();
            String text = conditional ? "当" + candidate.condition() + "时，" + candidate.content() : candidate.content();
            // destination 留空 = 不限目的地；lastUsedAt 留空 = 还没被召回过，衰减从确认那天算起
            preference = new Preference(null, candidate.field(), text, 1.0, candidate.condition(),
                    candidate.updatedAt(), now, null, candidate.sessionIds(), candidate.direction());
            audit(userId, "confirm", text, "candidate=" + candidateId);
        } else {
            audit(userId, "dismiss", candidate.content(), "candidate=" + candidateId);
        }
        List<Preference> confirmed = new ArrayList<>(document.confirmed());
        if (preference != null) confirmed.add(preference);
        repository.save(new ProfileDocument(userId, document.learningEnabled(),
                document.revisionHistory(), candidates, confirmed));
        return new Confirmation(candidate, preference);
    }

    public ProfileLoad profileFor(String userId) {
        ProfileDocument document = repository.load(userId);
        if (!document.learningEnabled()) return new ProfileLoad(UserProfile.empty(), List.of());
        Instant now = clock.instant();
        List<Forgotten> forgotten = new ArrayList<>();
        List<Preference> kept = new ArrayList<>();
        boolean changed = false;
        for (Preference preference : document.confirmed()) {
            long idleDays = preference.lastUsedAt() == null
                    ? 0 : Duration.between(preference.lastUsedAt(), now).toDays();
            if (idleDays > properties.decayDays()) {
                double confidence = preference.confidence() - properties.decayDrop();
                changed = true;
                if (confidence < properties.deleteThreshold()) {
                    forgotten.add(new Forgotten(preference.category(), preference.text(), "衰减删除"));
                    audit(userId, "forget", preference.text(), "衰减：置信度降到 " + confidence);
                } else {
                    kept.add(withConfidence(preference, confidence));
                }
            } else {
                kept.add(preference);
            }
        }
        if (changed) {
            repository.save(new ProfileDocument(userId, document.learningEnabled(),
                    document.revisionHistory(), document.candidates(), kept));
        }
        return new ProfileLoad(new UserProfile(userId, kept), forgotten);
    }

    public void markRecalled(String userId, PlanRequest request, int maxRecall) {
        ProfileDocument document = repository.load(userId);
        List<Preference> recalled = new UserProfile(userId, document.confirmed())
                .relevantPreferences(request, maxRecall);
        if (recalled.isEmpty()) return;
        Set<String> texts = new HashSet<>();
        recalled.forEach(preference -> texts.add(preference.text()));
        Instant now = clock.instant();
        List<Preference> updated = document.confirmed().stream()
                .map(preference -> texts.contains(preference.text()) ? withLastUsed(preference, now) : preference)
                .toList();
        repository.save(new ProfileDocument(userId, document.learningEnabled(),
                document.revisionHistory(), document.candidates(), updated));
    }

    public ProfileDocument export(String userId) {
        return repository.load(userId);
    }

    public void clear(String userId) {
        audit(userId, "clear", "*", "用户请求忘记我");
        repository.save(ProfileDocument.empty(userId));
    }

    public void setLearningEnabled(String userId, boolean enabled) {
        ProfileDocument document = repository.load(userId);
        repository.save(new ProfileDocument(userId, enabled, document.revisionHistory(),
                document.candidates(), document.confirmed()));
        audit(userId, enabled ? "learning-on" : "learning-off", "*", "用户切换偏好学习开关");
    }

    public Forgotten forgetAt(String userId, int index) {
        ProfileDocument document = repository.load(userId);
        if (index < 0 || index >= document.confirmed().size()) return null;
        Preference preference = document.confirmed().get(index);
        List<Preference> confirmed = new ArrayList<>(document.confirmed());
        confirmed.remove(index);
        repository.save(new ProfileDocument(userId, document.learningEnabled(),
                document.revisionHistory(), document.candidates(), confirmed));
        audit(userId, "forget", preference.text(), "用户手动删除");
        return new Forgotten(preference.category(), preference.text(), "用户手动删除");
    }

    private Integer directionOf(String field, Map<?, ?> change) {
        if (!NUMERIC_FIELDS.contains(field)) return null;
        try {
            int before = Integer.parseInt(String.valueOf(change.get("before")));
            int after = Integer.parseInt(String.valueOf(change.get("after")));
            return Integer.compare(after, before);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private boolean contradicts(Preference preference, RevisionRecord record) {
        if (record.direction() != null && preference.direction() != null) {
            return !record.direction().equals(preference.direction());
        }
        if (record.direction() != null || preference.direction() != null) return false;
        String newValue = record.toValue() == null ? "" : record.toValue().trim();
        String text = preference.text() == null ? "" : preference.text();
        return Preference.topicOverlap(text, newValue)
                && !text.contains(newValue) && !newValue.contains(text);
    }

    private Preference withConfidence(Preference preference, double confidence) {
        return new Preference(preference.destination(), preference.category(), preference.text(), confidence,
                preference.condition(), preference.learnedAt(), preference.confirmedAt(),
                preference.lastUsedAt(), preference.sourceSessions(), preference.direction());
    }

    private Preference withLastUsed(Preference preference, Instant lastUsedAt) {
        return new Preference(preference.destination(), preference.category(), preference.text(),
                preference.confidence(), preference.condition(), preference.learnedAt(),
                preference.confirmedAt(), lastUsedAt, preference.sourceSessions(), preference.direction());
    }

    private void audit(String userId, String action, String target, String reason) {
        log.info("[PREFERENCE-AUDIT] user={} action={} target={} reason={}", userId, action, target, reason);
    }
}
