package com.looptrip;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * 意图识别评测入口：默认禁用，只有显式 -Dlooptrip.eval=true 才跑。
 * 它调真实模型（与 application.yml 同配置），把结果写成 markdown 报告，不进二值测试通道。
 */
@EnabledIfSystemProperty(named = "looptrip.eval", matches = "true")
class PlanningIntentEvaluationTests {

    @Test
    void evaluatesIntentRecognitionAgainstLabeledDataset() throws Exception {
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        assumeThat(apiKey).as("DASHSCOPE_API_KEY 未设置，跳过评测").isNotBlank();

        OpenAiApi api = OpenAiApi.builder()
                .baseUrl("https://dashscope.aliyuncs.com/compatible-mode")
                .apiKey(apiKey)
                .build();
        OpenAiChatModel model = OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model("qwen3.8-flash")
                        .temperature(0.2)
                        .build())
                .build();
        CascadingPlanningIntentRecognizer recognizer =
                new CascadingPlanningIntentRecognizer(ChatClient.builder(model));
        PlanningIntentGate gate = new PlanningIntentGate();

        ObjectMapper mapper = new ObjectMapper();
        Path dataset = Path.of("..", "eval", "intents.jsonl");
        List<JsonNode> cases = Files.readAllLines(dataset).stream()
                .filter(line -> !line.isBlank())
                .map(line -> {
                    try { return mapper.readTree(line); } catch (Exception e) { throw new IllegalStateException(e); }
                }).toList();

        int actionHits = 0;
        int slotChecked = 0;
        int slotHits = 0;
        int exitHits = 0;
        List<JsonNode> dangerous = new ArrayList<>();
        Map<String, Integer> matrix = new TreeMap<>();
        List<String> detail = new ArrayList<>();

        for (JsonNode item : cases) {
            String utterance = item.get("utterance").asText();
            PlanningIntentAction expectedAction = PlanningIntentAction.valueOf(item.get("action").asText());
            String expectedExit = item.get("exit").asText();

            PlanningIntent intent = recognizer.recognize(utterance, null);
            PlanningChatDecision decision = gate.decide(intent, false, null).decision();

            boolean actionOk = intent.action() == expectedAction;
            if (actionOk) actionHits++;
            matrix.merge(expectedAction.name() + " -> " + intent.action().name(), 1, Integer::sum);

            JsonNode expectedSlots = item.get("slots");
            boolean slotOk = true;
            if (expectedSlots != null && !expectedSlots.isEmpty()) {
                slotChecked++;
                for (Iterator<Map.Entry<String, JsonNode>> it = expectedSlots.fields(); it.hasNext(); ) {
                    Map.Entry<String, JsonNode> entry = it.next();
                    Object predicted = intent.slots().get(entry.getKey());
                    if (!slotMatches(entry.getValue(), predicted)) { slotOk = false; break; }
                }
                if (slotOk) slotHits++;
            }

            boolean exitOk = decision.name().equals(expectedExit);
            if (exitOk) exitHits++;

            boolean isDangerous = !actionOk
                    && (expectedAction == PlanningIntentAction.ASK_QUESTION || expectedAction == PlanningIntentAction.NEW_SESSION)
                    && intent.action().name().startsWith("REVISE_");
            if (isDangerous) dangerous.add(item);

            detail.add(String.format("| %d | %s | %s | %s | %s | %s | %s | %s |",
                    item.get("id").asInt(), utterance, expectedAction.name(), intent.action().name(),
                    actionOk ? "✓" : "✗", expectedExit, decision.name(), exitOk ? "✓" : "✗"));
        }

        int total = cases.size();
        StringBuilder report = new StringBuilder();
        report.append("# 意图识别评测报告 ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))).append("\n\n");
        report.append("- 模型：qwen3.8-flash（temperature 0.2，与 application.yml 一致）\n");
        report.append("- 数据集：eval/intents.jsonl（").append(total).append(" 条）\n");
        report.append("- 门禁：-Dlooptrip.eval=true\n\n");
        report.append("## 总指标\n\n");
        report.append("| 指标 | 命中 | 总数 | 准确率 |\n|---|---|---|---|\n");
        report.append(String.format("| 动作准确率 | %d | %d | %.1f%% |%n", actionHits, total, 100.0 * actionHits / total));
        report.append(String.format("| 槽位准确率 | %d | %d | %.1f%% |%n", slotHits, slotChecked, slotChecked == 0 ? 100.0 : 100.0 * slotHits / slotChecked));
        report.append(String.format("| 出口准确率 | %d | %d | %.1f%% |%n", exitHits, total, 100.0 * exitHits / total));

        report.append("\n## 危险误判（单独报，不平摊进总准确率）\n\n");
        if (dangerous.isEmpty()) {
            report.append("本次运行没有危险误判：没有出现把提问或重新开始判成修改需求的情况。\n");
        } else {
            report.append("以下话术被误判为修改需求（用户只是提问或想重新开始，系统却会烧掉多轮规划）：\n\n");
            for (JsonNode item : dangerous) {
                report.append("- ").append(item.get("utterance").asText()).append("\n");
            }
        }
        report.append(String.format("%n危险误判数：**%d**%n", dangerous.size()));

        report.append("\n## 混淆矩阵（期望 -> 预测）\n\n| 方向 | 次数 |\n|---|---|\n");
        for (Map.Entry<String, Integer> entry : matrix.entrySet()) {
            report.append("| ").append(entry.getKey()).append(" | ").append(entry.getValue()).append(" |\n");
        }

        report.append("\n## 逐条明细\n\n| # | 话术 | 期望动作 | 预测动作 | 动作 | 期望出口 | 预测出口 | 出口 |\n|---|---|---|---|---|---|---|---|\n");
        detail.forEach(line -> report.append(line).append("\n"));

        Path reports = Path.of("..", "eval", "reports");
        Files.createDirectories(reports);
        Path reportPath = reports.resolve("intent-eval-"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) + ".md");
        Files.writeString(reportPath, report.toString());
        System.out.println("[eval] 报告已写入 " + reportPath.toAbsolutePath().normalize());

        assertThat(actionHits).as("评测必须跑满全部 %d 条", total).isGreaterThan(0);
    }

    private boolean slotMatches(JsonNode expected, Object predicted) {
        if (expected.isNumber()) {
            return predicted instanceof Number number && number.intValue() == expected.asInt();
        }
        if (expected.isArray()) {
            if (!(predicted instanceof List<?> list)) return false;
            List<String> expectedItems = new ArrayList<>();
            expected.forEach(node -> expectedItems.add(node.asText()));
            return list.stream().map(String::valueOf).toList().containsAll(expectedItems);
        }
        return predicted != null;
    }
}
