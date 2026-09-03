package com.looptrip;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Observability-only component: snapshots the exact system/user text handed to
 * the model before each call, plus a per-section size report. Failures never
 * propagate into the planning flow.
 */
@Component
public class PromptDumper {

    static final String SECTION_MARKER = "### SECTION:";
    static final List<String> SECTIONS = List.of("INSTRUCTION", "CONSTRAINT", "HISTORY", "PROFILE");

    private static final Logger log = LoggerFactory.getLogger(PromptDumper.class);

    private final Path directory;

    public PromptDumper() {
        this(Path.of("logs", "prompts"));
    }

    PromptDumper(Path directory) {
        this.directory = directory;
    }

    public void dump(String sessionId, int round, String systemPrompt, String userPrompt) {
        try {
            Files.createDirectories(directory);
            Path target = nextAvailableFile(sessionId, round);
            Files.writeString(target, render(sessionId, round, systemPrompt, userPrompt), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            log.warn("PromptDumper 快照落盘失败：{}", exception.toString());
        }
    }

    private Path nextAvailableFile(String sessionId, int round) {
        Path base = directory.resolve(sessionId + "-" + round + ".txt");
        if (!Files.exists(base)) return base;
        for (int suffix = 2; ; suffix++) {
            Path candidate = directory.resolve(sessionId + "-" + round + "-" + suffix + ".txt");
            if (!Files.exists(candidate)) return candidate;
        }
    }

    private String render(String sessionId, int round, String systemPrompt, String userPrompt) {
        StringBuilder out = new StringBuilder();
        out.append("session: ").append(sessionId).append('\n');
        out.append("round: ").append(round).append('\n');
        out.append('\n');
        out.append("=== ROLE: SYSTEM ===\n");
        out.append(systemPrompt).append('\n');
        out.append('\n');
        out.append("=== ROLE: USER ===\n");
        out.append(userPrompt).append('\n');
        out.append('\n');
        out.append(sectionReport(systemPrompt + "\n" + userPrompt));
        return out.toString();
    }

    private String sectionReport(String fullText) {
        Map<String, Integer> charsBySection = new LinkedHashMap<>();
        for (String section : SECTIONS) charsBySection.put(section, 0);
        String current = null;
        StringBuilder buffer = new StringBuilder();
        for (String line : fullText.split("\n", -1)) {
            if (line.startsWith(SECTION_MARKER)) {
                accumulate(charsBySection, current, buffer);
                current = line.substring(SECTION_MARKER.length()).trim();
                continue;
            }
            if (current != null) buffer.append(line).append('\n');
        }
        accumulate(charsBySection, current, buffer);

        int sectionTotal = charsBySection.values().stream().mapToInt(Integer::intValue).sum();
        StringBuilder out = new StringBuilder();
        out.append("=== SECTION REPORT ===\n");
        for (Map.Entry<String, Integer> entry : charsBySection.entrySet()) {
            int chars = entry.getValue();
            double share = sectionTotal == 0 ? 0.0 : chars * 100.0 / sectionTotal;
            out.append(entry.getKey())
                    .append(": chars=").append(chars)
                    .append(", estTokens=").append(estimateTokens(chars))
                    .append(", share=").append(String.format("%.1f", share)).append("%\n");
        }
        int totalChars = fullText.length();
        out.append("TOTAL: chars=").append(totalChars)
                .append(", estTokens=").append(estimateTokens(totalChars)).append('\n');
        return out.toString();
    }

    private void accumulate(Map<String, Integer> charsBySection, String current, StringBuilder buffer) {
        if (current == null) {
            buffer.setLength(0);
            return;
        }
        String content = buffer.toString().replaceAll("\\s+$", "");
        charsBySection.merge(current, content.length(), Integer::sum);
        buffer.setLength(0);
    }

    static long estimateTokens(int chars) {
        return (long) Math.ceil(chars / 1.5);
    }
}
