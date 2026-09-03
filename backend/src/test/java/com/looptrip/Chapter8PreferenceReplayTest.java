package com.looptrip;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 第 8-3 节课堂回放：不联网、不调模型，把"三个会话攒出候选 → 用户确认 →
 * 画像落盘 → 下一次组装进 Prompt"整条记忆闭环演一遍。
 * 由 scripts/course/ch08-preference-replay.sh 触发，
 * 画像写到 backend/data/profiles/replay-demo-user.json，Prompt 快照写到 backend/logs/prompts/。
 */
class Chapter8PreferenceReplayTest {

    private static final String USER = "replay-demo-user";
    private static final String SESSION = "preference-demo-session";
    private static final Instant START = Instant.parse("2026-09-01T00:00:00Z");

    @Test
    void replayThreeSessionsToConfirmedProfileThenIntoPrompt() throws Exception {
        Path profileDir = Path.of("data", "profiles");
        Path promptDir = Path.of("logs", "prompts");
        cleanOldArtifacts(profileDir, promptDir);

        AtomicReference<Instant> now = new AtomicReference<>(START);
        PreferenceProperties properties = new PreferenceProperties(
                profileDir.toString(), USER, 3, 90, 0.25, 0.3, 8);
        PreferenceMemoryService service = new PreferenceMemoryService(
                new ProfileRepository(objectMapper(), properties), properties,
                new PreferenceLearner(), new PromotionPolicy(), new Clock() {
                    @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
                    @Override public Clock withZone(ZoneId zone) { return this; }
                    @Override public Instant instant() { return now.get(); }
                });

        // 三个会话连续上调预算：前两次只是等待，第三次才够晋升门槛
        assertThat(decide(service, "replay-s1", 3000, 3500)).isEqualTo(PromotionDecision.WAIT);
        assertThat(decide(service, "replay-s2", 3500, 4000)).isEqualTo(PromotionDecision.WAIT);
        PreferenceMemoryService.ObservationResult third =
                service.observeRevision(USER, "replay-s3", diff("budget", 4000, 4500));
        PreferenceMemoryService.Observation observation = third.observations().get(0);
        assertThat(observation.decision()).isEqualTo(PromotionDecision.CONFIRM_UNCONDITIONAL);

        // 用户在确认卡片上点了"记住"
        PreferenceMemoryService.Confirmation confirmation = service.confirm(USER, observation.candidateId(), true);
        assertThat(confirmation.preference().text()).isEqualTo("budget倾向于上调");

        // 画像文件真实落盘，打开就能看
        Path profileFile = profileDir.resolve(USER + ".json");
        assertThat(profileFile).exists();
        assertThat(Files.readString(profileFile)).contains("budget倾向于上调");

        // 下一次规划：画像进入 Prompt，快照留档供课堂展示
        PlanRequest request = new PlanRequest("上海", "杭州", LocalDate.of(2026, 10, 1), 3,
                4500, 700, "轻松", List.of("西湖"), 2);
        UserProfile profile = service.profileFor(USER).profile();
        PromptContext context = new ContextAssembler().assemble(
                new PlanGenerationInput(request, 1, null, List.of()), profile, 6000, 0.15, 8);
        assertThat(context.includedSections()).contains("PROFILE");
        assertThat(context.userPrompt()).contains("budget倾向于上调").doesNotContain("用户画像：暂无");

        new PromptDumper(promptDir).dump(SESSION, 1, context.systemPrompt(), context.userPrompt());
        Path snapshot = promptDir.resolve(SESSION + "-1.txt");
        assertThat(snapshot).exists();
        assertThat(Files.readString(snapshot)).contains("budget倾向于上调");

        // 相反修改：用户又把预算调回去了，旧偏好当场被遗忘
        PreferenceMemoryService.ObservationResult opposite =
                service.observeRevision(USER, "replay-s4", diff("budget", 4500, 3000));
        assertThat(opposite.forgotten())
                .extracting(PreferenceMemoryService.Forgotten::reason).containsExactly("相反修改");
        assertThat(service.profileFor(USER).profile().preferences()).isEmpty();
    }

    private PromotionDecision decide(PreferenceMemoryService service, String sessionId, int before, int after) {
        return service.observeRevision(USER, sessionId, diff("budget", before, after))
                .observations().get(0).decision();
    }

    private Map<String, Object> diff(String field, Object before, Object after) {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("before", before);
        change.put("after", after);
        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put(field, change);
        return diff;
    }

    private ObjectMapper objectMapper() {
        return new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
    }

    private void cleanOldArtifacts(Path profileDir, Path promptDir) throws Exception {
        Files.deleteIfExists(profileDir.resolve(USER + ".json"));
        if (Files.isDirectory(promptDir)) {
            try (Stream<Path> files = Files.list(promptDir)) {
                for (Path file : files.filter(path -> path.getFileName().toString().startsWith(SESSION + "-")).toList()) {
                    Files.deleteIfExists(file);
                }
            }
        }
    }
}
