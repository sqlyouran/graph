package com.looptrip;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 课堂回放：不联网、不调模型，只回放 PromptDumper 的落盘格式。
 * 由 scripts/course/ch08-prompt-dumper-replay.sh 触发，快照写到 backend/logs/prompts/。
 */
class Chapter8PromptDumperReplayTest {

    private static final String SESSION = "demo-session";

    @Test
    void replayFixedRequestAndKeepSameRoundRetrySnapshot() throws Exception {
        Path directory = Path.of("logs", "prompts");
        cleanOldSnapshots(directory);

        PromptDumper dumper = new PromptDumper(directory);
        PlanRequest request = new PlanRequest("上海", "杭州", LocalDate.of(2026, 10, 1), 3,
                2600, 700, "轻松", List.of("雷峰塔"), 2);
        PromptContext context = new ContextAssembler().assemble(
                new PlanGenerationInput(request, 1, null, List.of()), UserProfile.empty(), 6000, 0.15);

        dumper.dump(SESSION, 1, context.systemPrompt(), context.userPrompt());
        dumper.dump(SESSION, 1, context.systemPrompt(), context.userPrompt());

        Path first = directory.resolve(SESSION + "-1.txt");
        Path second = directory.resolve(SESSION + "-1-2.txt");
        assertThat(first).exists();
        assertThat(second).exists();
        String content = Files.readString(first);
        assertThat(content)
                .contains("session: demo-session")
                .contains("round: 1")
                .contains("=== ROLE: SYSTEM ===")
                .contains("=== ROLE: USER ===")
                .contains("前往杭州")
                .contains("必去景点：雷峰塔")
                .contains("=== SECTION REPORT ===")
                .contains("INSTRUCTION: chars=")
                .contains("CONSTRAINT: chars=")
                .contains("HISTORY: chars=0")
                .contains("PROFILE: chars=")
                .contains("TOTAL: chars=");
    }

    @Test
    void unwritableDirectoryOnlyWarns() throws Exception {
        Path blocked = Files.createTempFile("ch8-replay-blocked", ".tmp");
        try {
            PromptDumper dumper = new PromptDumper(blocked.resolve("prompts"));

            assertThatCode(() -> dumper.dump(SESSION, 1, "sys", "user")).doesNotThrowAnyException();
        } finally {
            Files.deleteIfExists(blocked);
        }
    }

    private void cleanOldSnapshots(Path directory) throws Exception {
        if (!Files.isDirectory(directory)) return;
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.filter(path -> path.getFileName().toString().startsWith(SESSION + "-")).toList()) {
                Files.deleteIfExists(file);
            }
        }
    }
}
