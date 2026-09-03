package com.looptrip;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 第 8-3 节：偏好学习闭环的契约——观察只认会话数、方向不一致拒收、
 * 确认是写入画像的唯一入口、相反修改与衰减都走遗忘、召回有条件有上限。
 */
class Chapter8PreferenceLearningTest {

    private static final String USER = "u-test";
    private static final Instant START = Instant.parse("2026-09-01T00:00:00Z");

    @TempDir
    java.nio.file.Path storage;

    private final AtomicReference<Instant> now = new AtomicReference<>(START);
    private PreferenceMemoryService service;
    private ProfileRepository repository;

    @BeforeEach
    void setUp() {
        PreferenceProperties properties = new PreferenceProperties(
                storage.toString(), USER, 3, 90, 0.25, 0.3, 8);
        repository = new ProfileRepository(
                new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule()),
                properties);
        Clock clock = new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        service = new PreferenceMemoryService(repository, properties,
                new PreferenceLearner(), new PromotionPolicy(), clock);
    }

    @Test
    void budgetTrendWaitsBelowThresholdThenConfirmsAtThirdSession() {
        assertThat(decide("s1", diff("budget", 3000, 3500))).isEqualTo(PromotionDecision.WAIT);
        assertThat(decide("s2", diff("budget", 3500, 4000))).isEqualTo(PromotionDecision.WAIT);

        PreferenceMemoryService.ObservationResult result = service.observeRevision(USER, "s3", diff("budget", 4000, 4500));
        PreferenceMemoryService.Observation observation = result.observations().get(0);
        assertThat(observation.decision()).isEqualTo(PromotionDecision.CONFIRM_UNCONDITIONAL);
        assertThat(observation.sessionCount()).isEqualTo(3);
        assertThat(observation.content()).isEqualTo("budget倾向于上调");

        // 确认之前画像必须是空的——候选不等于记忆
        assertThat(service.profileFor(USER).profile().preferences()).isEmpty();

        PreferenceMemoryService.Confirmation confirmation = service.confirm(USER, observation.candidateId(), true);
        assertThat(confirmation.preference()).isNotNull();
        assertThat(service.profileFor(USER).profile().preferences())
                .extracting(Preference::text).containsExactly("budget倾向于上调");
        assertThat(service.export(USER).candidates()).isEmpty();
    }

    @Test
    void flipFlopDirectionIsRejected() {
        service.observeRevision(USER, "s1", diff("budget", 3000, 3500));
        service.observeRevision(USER, "s2", diff("budget", 3500, 3200));

        PreferenceMemoryService.ObservationResult result = service.observeRevision(USER, "s3", diff("budget", 3200, 3800));
        assertThat(result.observations().get(0).decision()).isEqualTo(PromotionDecision.REJECT);
        assertThat(service.profileFor(USER).profile().preferences()).isEmpty();
    }

    @Test
    void conditionalTextBecomesConfirmedWithCondition() {
        service.observeRevision(USER, "s1", diff("preferences", "轻松", "当带孩子时，节奏放慢"));
        service.observeRevision(USER, "s2", diff("preferences", "轻松", "当带孩子时，节奏放慢"));

        PreferenceMemoryService.ObservationResult result = service.observeRevision(
                USER, "s3", diff("preferences", "轻松", "当带孩子时，节奏放慢"));
        PreferenceMemoryService.Observation observation = result.observations().get(0);
        assertThat(observation.decision()).isEqualTo(PromotionDecision.CONFIRM_WITH_CONDITION);
        assertThat(observation.condition()).isEqualTo("带孩子");
        assertThat(observation.content()).isEqualTo("节奏放慢");

        service.confirm(USER, observation.candidateId(), true);
        Preference saved = service.profileFor(USER).profile().preferences().get(0);
        assertThat(saved.text()).isEqualTo("当带孩子时，节奏放慢");
        assertThat(saved.condition()).isEqualTo("带孩子");

        // 条件不成立的请求不召回；成立才召回
        assertThat(service.profileFor(USER).profile().relevantPreferences(request("轻松"))).isEmpty();
        assertThat(service.profileFor(USER).profile().relevantPreferences(request("带孩子出行")))
                .extracting(Preference::text).containsExactly("当带孩子时，节奏放慢");
    }

    @Test
    void repeatedRevisionsInsideOneSessionCountOnce() {
        service.observeRevision(USER, "s1", diff("budget", 3000, 3500));
        service.observeRevision(USER, "s1", diff("budget", 3500, 4000));
        service.observeRevision(USER, "s1", diff("budget", 4000, 4500));

        PreferenceMemoryService.ObservationResult result = service.observeRevision(USER, "s2", diff("budget", 4500, 5000));
        assertThat(result.observations().get(0).decision()).isEqualTo(PromotionDecision.WAIT);
        assertThat(result.observations().get(0).sessionCount()).isEqualTo(2);
    }

    @Test
    void dismissedCandidateNeverEntersProfile() {
        PreferenceMemoryService.ObservationResult result = observeThreeSessions("budget", 3000, 3500, 4000, 4500);
        PreferenceMemoryService.Observation observation = result.observations().get(0);

        PreferenceMemoryService.Confirmation confirmation = service.confirm(USER, observation.candidateId(), false);
        assertThat(confirmation.preference()).isNull();
        assertThat(service.profileFor(USER).profile().preferences()).isEmpty();
        assertThat(service.export(USER).candidates()).isEmpty();
        // 已处理过的候选不能重复确认
        assertThat(service.confirm(USER, observation.candidateId(), true)).isNull();
    }

    @Test
    void unusedPreferenceDecaysAfterNinetyOneDaysAndIsEventuallyForgotten() {
        Preference fresh = new Preference("", "budget", "budget倾向于上调", 1.0, null,
                START, START, START, List.of("s1"), 1);
        repository.save(new ProfileDocument(USER, true, List.of(), List.of(), List.of(fresh)));

        now.set(START.plusSeconds(91L * 24 * 3600));
        // 每次加载画像都会结算一次衰减：1.0 → 0.75 → 0.5，再降就跌破 0.3 阈值被删
        assertThat(service.profileFor(USER).profile().preferences().get(0).confidence()).isEqualTo(0.75);
        assertThat(service.profileFor(USER).profile().preferences().get(0).confidence()).isEqualTo(0.5);

        PreferenceMemoryService.ProfileLoad load = service.profileFor(USER);
        assertThat(load.forgotten())
                .extracting(PreferenceMemoryService.Forgotten::reason).containsExactly("衰减删除");
        assertThat(load.profile().preferences()).isEmpty();
    }

    @Test
    void oppositeRevisionDeletesConfirmedPreference() {
        Preference saved = new Preference("", "budget", "budget倾向于上调", 1.0, null,
                START, START, START, List.of("s1"), 1);
        repository.save(new ProfileDocument(USER, true, List.of(), List.of(), List.of(saved)));

        PreferenceMemoryService.ObservationResult result = service.observeRevision(USER, "s9", diff("budget", 4000, 3000));
        assertThat(result.forgotten())
                .extracting(PreferenceMemoryService.Forgotten::reason).containsExactly("相反修改");
        assertThat(service.profileFor(USER).profile().preferences()).isEmpty();
    }

    @Test
    void confirmedPreferenceEntersPromptAndRecallUpdatesLastUsed() {
        PreferenceMemoryService.ObservationResult result = service.observeRevision(
                USER, "s1", diff("preferences", "轻松", "喜欢靠窗座位"));
        result = service.observeRevision(USER, "s2", diff("preferences", "轻松", "喜欢靠窗座位"));
        result = service.observeRevision(USER, "s3", diff("preferences", "轻松", "喜欢靠窗座位"));
        service.confirm(USER, result.observations().get(0).candidateId(), true);

        PreferenceMemoryService.ProfileLoad load = service.profileFor(USER);
        PromptContext context = new ContextAssembler().assemble(
                new PlanGenerationInput(request("轻松"), 1, null, List.of()), load.profile(), 6000, 0.15, 8);
        assertThat(context.includedSections()).contains("PROFILE");
        assertThat(context.userPrompt()).contains("喜欢靠窗座位").doesNotContain("用户画像：暂无");

        // 刚确认时还没被召回过；markRecalled 之后才有 lastUsedAt
        assertThat(load.profile().preferences().get(0).lastUsedAt()).isNull();
        service.markRecalled(USER, request("轻松"), 8);
        assertThat(service.profileFor(USER).profile().preferences().get(0).lastUsedAt()).isNotNull();
    }

    @Test
    void recallIsCappedAndSortedByConfidenceThenFreshness() {
        List<Preference> confirmed = List.of(
                new Preference("", null, "偏好甲", 0.9, null, START, START, START, List.of(), null),
                new Preference("", null, "偏好乙", 1.0, null, START, START, START.plusSeconds(60), List.of(), null),
                new Preference("", null, "偏好丙", 1.0, null, START, START, START, List.of(), null));
        repository.save(new ProfileDocument(USER, true, List.of(), List.of(), confirmed));

        UserProfile profile = service.profileFor(USER).profile();
        assertThat(profile.relevantPreferences(request("轻松"), 2))
                .extracting(Preference::text).containsExactly("偏好乙", "偏好丙");
        assertThat(profile.relevantPreferences(request("轻松"), 8))
                .extracting(Preference::text).containsExactly("偏好乙", "偏好丙", "偏好甲");
    }

    @Test
    void sameTopicRequestOverridesTextPreferenceButUnrelatedTextDoesNot() {
        Preference homestay = new Preference("", "preferences", "住宿喜欢民宿", 1.0, null,
                START, START, START, List.of(), null);
        repository.save(new ProfileDocument(USER, true, List.of(), List.of(), List.of(homestay)));
        UserProfile profile = service.profileFor(USER).profile();

        // 本次请求谈到了住宿 → 画像让位；只谈吃 → 画像保留
        assertThat(profile.relevantPreferences(request("想住住宿条件好的酒店"))).isEmpty();
        assertThat(profile.relevantPreferences(request("想多吃本地小吃")))
                .extracting(Preference::text).containsExactly("住宿喜欢民宿");
    }

    @Test
    void manualForgetAndClearAndLearningSwitch() {
        Preference saved = new Preference("", "budget", "budget倾向于上调", 1.0, null,
                START, START, START, List.of(), 1);
        repository.save(new ProfileDocument(USER, true, List.of(), List.of(), List.of(saved)));

        assertThat(service.forgetAt(USER, 5)).isNull();
        PreferenceMemoryService.Forgotten forgotten = service.forgetAt(USER, 0);
        assertThat(forgotten.reason()).isEqualTo("用户手动删除");
        assertThat(service.profileFor(USER).profile().preferences()).isEmpty();

        service.setLearningEnabled(USER, false);
        PreferenceMemoryService.ObservationResult result = service.observeRevision(USER, "s1", diff("budget", 3000, 3500));
        assertThat(result.observations()).isEmpty();
        assertThat(service.export(USER).revisionHistory()).isEmpty();

        service.setLearningEnabled(USER, true);
        service.observeRevision(USER, "s1", diff("budget", 3000, 3500));
        assertThat(service.export(USER).revisionHistory()).hasSize(1);
        service.clear(USER);
        assertThat(service.export(USER).revisionHistory()).isEmpty();
        assertThat(service.export(USER).learningEnabled()).isTrue();
    }

    private PromotionDecision decide(String sessionId, Map<String, Object> diff) {
        PreferenceMemoryService.ObservationResult result = service.observeRevision(USER, sessionId, diff);
        return result.observations().get(0).decision();
    }

    private PreferenceMemoryService.ObservationResult observeThreeSessions(
            String field, int first, int... rest) {
        int before = first;
        PreferenceMemoryService.ObservationResult result = null;
        for (int index = 0; index < rest.length; index++) {
            result = service.observeRevision(USER, "s" + (index + 1), diff(field, before, rest[index]));
            before = rest[index];
        }
        return result;
    }

    private Map<String, Object> diff(String field, Object before, Object after) {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("before", before);
        change.put("after", after);
        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put(field, change);
        return diff;
    }

    private PlanRequest request(String preferences) {
        return new PlanRequest("上海", "杭州", LocalDate.of(2026, 10, 1), 3,
                3000, 700, preferences, List.of("西湖"), 2);
    }
}
