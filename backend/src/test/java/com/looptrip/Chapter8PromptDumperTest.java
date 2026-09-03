package com.looptrip;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class Chapter8PromptDumperTest {

    @Mock private ChatClient.Builder chatClientBuilder;
    @Mock private ChatClient chatClient;
    @Mock private ChatClient.ChatClientRequestSpec requestSpec;
    @Mock private ChatClient.CallResponseSpec responseSpec;
    @Mock private TravelTools travelTools;

    @TempDir Path tempDir;

    @Test
    void writesSnapshotWithRoleBoundaryAndSectionReport() throws Exception {
        PromptDumper dumper = new PromptDumper(tempDir.resolve("prompts"));

        dumper.dump("sess-A", 1, "### SECTION:INSTRUCTION\n你是旅行规划师。",
                "### SECTION:CONSTRAINT\n原始需求内容。\n### SECTION:PROFILE\n用户画像：暂无。");

        Path file = tempDir.resolve("prompts/sess-A-1.txt");
        assertThat(file).exists();
        String content = Files.readString(file);
        assertThat(content)
                .contains("session: sess-A")
                .contains("round: 1")
                .contains("=== ROLE: SYSTEM ===")
                .contains("你是旅行规划师。")
                .contains("=== ROLE: USER ===")
                .contains("原始需求内容。");
        assertThat(content)
                .contains("INSTRUCTION: chars=8")
                .contains("CONSTRAINT: chars=7")
                .contains("PROFILE: chars=8")
                .contains("HISTORY: chars=0")
                .contains("INSTRUCTION: chars=8, estTokens=6, share=34.8%");
        assertThat(content).contains("TOTAL: chars=");
        assertThat(content).doesNotContain("estTokens=0, share=NaN");
    }

    @Test
    void aggregatesRepeatedSectionsAndEstimatesTokens() throws Exception {
        PromptDumper dumper = new PromptDumper(tempDir);

        dumper.dump("sess-B", 2, "### SECTION:INSTRUCTION\n指令",
                "### SECTION:CONSTRAINT\n约束一。\n### SECTION:HISTORY\n历史。\n### SECTION:CONSTRAINT\n约束二。");

        String content = Files.readString(tempDir.resolve("sess-B-2.txt"));
        assertThat(content).contains("CONSTRAINT: chars=8");
        assertThat(content).contains("HISTORY: chars=3");
    }

    @Test
    void sameRoundRetryKeepsBothSnapshots() throws Exception {
        PromptDumper dumper = new PromptDumper(tempDir);

        dumper.dump("sess-C", 1, "系统第一次", "用户第一次");
        dumper.dump("sess-C", 1, "系统第二次", "用户第二次");

        Path first = tempDir.resolve("sess-C-1.txt");
        Path second = tempDir.resolve("sess-C-1-2.txt");
        assertThat(first).exists();
        assertThat(second).exists();
        assertThat(Files.readString(first)).contains("用户第一次").doesNotContain("用户第二次");
        assertThat(Files.readString(second)).contains("用户第二次");
    }

    @Test
    void unwritableDirectoryNeverThrows() throws Exception {
        Path blocked = tempDir.resolve("blocked");
        Files.createFile(blocked);
        PromptDumper dumper = new PromptDumper(blocked.resolve("prompts"));

        assertThatCode(() -> dumper.dump("sess-D", 1, "sys", "user")).doesNotThrowAnyException();
    }

    @Test
    void generateDumpsSnapshotNamedBySessionId() throws Exception {
        when(chatClientBuilder.defaultTools(travelTools)).thenReturn(chatClientBuilder);
        when(chatClientBuilder.build()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.entity(TripPlan.class)).thenReturn(TestTripPlans.complete(3));

        PlanningSessionContext context = new PlanningSessionContext();
        PlanChatService service = new PlanChatService(chatClientBuilder, travelTools, "qwen-test",
                null, new PromptDumper(tempDir), context);
        PlanRequest request = new PlanRequest("上海", "杭州", LocalDate.of(2026, 10, 1), 3, 3000, 700, "轻松", 2);
        PlanningSession session = new PlanningSession("plan-session-1", request);

        context.bind(session, () -> service.generate(new PlanGenerationInput(request, 1, null, List.of())));

        Path file = tempDir.resolve("plan-session-1-1.txt");
        assertThat(file).exists();
        String content = Files.readString(file);
        assertThat(content)
                .contains("session: plan-session-1")
                .contains("=== ROLE: SYSTEM ===")
                .contains("【原始需求】")
                .contains("SECTION:INSTRUCTION")
                .contains("SECTION:PROFILE");
    }
}
