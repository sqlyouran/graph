package com.looptrip;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

class SsePlanningEventSinkDesensitizationTests {
    @TempDir Path temporaryDirectory;

    private PlanningSessionStore store;
    private SsePlanningEventSink sink;
    private PlanningSession session;

    @BeforeEach
    void setUp() {
        store = new PlanningSessionStore(new ObjectMapper().findAndRegisterModules(),
                new SessionProperties(20, Duration.ofMinutes(30), temporaryDirectory), Clock.systemUTC());
        sink = new SsePlanningEventSink(store);
        session = new PlanningSession("desensitization", new PlanRequest(
                "上海", "杭州", java.time.LocalDate.of(2026, 10, 1), 3,
                3000, 700, "轻松", List.of(), 2));
    }

    @Test
    void sensitiveDetailsAreDroppedBeforePublish() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("apiKey", "sk-secret");
        details.put("prompt", "完整的系统提示词正文");
        details.put("stackTrace", "java.lang.Exception at ...");
        details.put("internalClass", "com.looptrip.SecretHelper$1");
        details.put("model", "qwen-test");

        sink.forSession(session, () -> {
            sink.emit(PlanningEventType.GENERATION_COMPLETED, "生成完成", details);
            return null;
        });

        PlanningSessionStore.PublicEvent event = session.events().getFirst();
        assertThat(event.details()).containsOnlyKeys("model");
        assertThat(event.details()).doesNotContainKeys("apiKey", "prompt", "stackTrace", "internalClass");
    }

    @Test
    void whitelistedKeysPassThrough() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("elapsedMs", 120L);
        details.put("summary", "第一轮方案已生成");
        details.put("action", "REVISE_BUDGET");
        details.put("echo", "我理解成：降低总预算。");
        details.put("version", 1);

        sink.forSession(session, () -> {
            sink.emit(PlanningEventType.INTENT_RECOGNIZED, "意图已识别", details);
            return null;
        });

        assertThat(session.events().getFirst().details())
                .containsKeys("elapsedMs", "summary", "action", "echo", "version");
    }

    @Test
    void reviewCompletedPublishesProblemsCountOnly() {
        sink.forSession(session, () -> {
            sink.emit(PlanningEventType.REVIEW_COMPLETED, "两层验收未通过",
                    Map.of("passed", false, "problems", List.of("缺少住宿安排", "预算超限")));
            return null;
        });

        Map<String, Object> details = session.events().getFirst().details();
        assertThat(details).containsEntry("problemsCount", 2).containsEntry("passed", false);
        assertThat(details).doesNotContainKey("problems");
    }

    @Test
    void heartbeatIsNeverPublished() {
        sink.forSession(session, () -> {
            sink.emit(PlanningEventType.HEARTBEAT, "", Map.of());
            return null;
        });

        assertThat(session.events()).isEmpty();
    }
}
