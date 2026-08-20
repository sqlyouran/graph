package com.looptrip;

import java.util.concurrent.TimeUnit;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class PlanChatService implements PlanGenerator {

    static final String SYSTEM_PROMPT = """
            你是一名旅行规划师。请直接回答用户的旅行规划问题，不要寒暄。
            使用清晰的 Markdown 按天组织行程，总字数控制在 400 字以内。
            航班、酒店、景点和天气的价格、日期与时间必须来自对应工具，引用价格时保持工具返回的原值。
            需要这些事实时主动调用工具；工具返回空列表时，明确回复“该城市/日期暂无数据”，不得用常识、平均值、默认项目或其他日期补造事实。
            总花费只汇总工具实际返回且被行程采用的数值，不补估没有数据的项目。
            回答末行必须严格使用格式：预计总花费 XXXX 元
            """;

    private final ChatClient chatClient;
    private final String model;

    public PlanChatService(
            ChatClient.Builder chatClientBuilder,
            TravelTools travelTools,
            @Value("${spring.ai.openai.chat.options.model}") String model) {
        this.chatClient = chatClientBuilder.defaultTools(travelTools).build();
        this.model = model;
    }

    @Override
    public PlanGenerationResult generate(PlanGenerationInput input) {
        long startedAt = System.nanoTime();
        try {
            String answer = chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(buildPrompt(input))
                    .call()
                    .content();
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

            if (!StringUtils.hasText(answer)) {
                throw new ModelCallException("模型返回了空回答");
            }
            return new PlanGenerationResult(answer, model, elapsedMs);
        } catch (ModelCallException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ModelCallException("模型调用失败，请稍后重试", exception);
        }
    }

    static String buildPrompt(PlanGenerationInput input) {
        String originalRequest = formatRequest(input.originalRequest());
        if (input.round() == 1) {
            return """
                    【原始需求】
                    %s

                    请生成一份完整 Markdown 行程，并遵守系统要求。
                    """.formatted(originalRequest);
        }

        String feedback = input.feedbackProblems().stream()
                .map(problem -> "- " + problem)
                .reduce((left, right) -> left + "\n" + right)
                .orElse("- 无");
        return """
                【原始需求】
                %s

                【上一版完整行程】
                %s

                【上一轮全部问题】
                %s

                【修订要求】
                保留已合格部分，逐项修复上述问题，并返回一份完整修订版。不要只返回差异或补丁。
                """.formatted(originalRequest, input.previousMarkdown(), feedback);
    }

    private static String formatRequest(PlanRequest request) {
        String preferences = StringUtils.hasText(request.preferences())
                ? request.preferences().trim()
                : "没有特别偏好";
        return """
                从%s出发，前往%s，开始日期%s，共%d天，总预算%d元，酒店每晚不超过%d元，偏好：%s。
                请查询航班、酒店、景点和每天的天气，并按天输出。
                """.formatted(
                request.origin().trim(),
                request.destination().trim(),
                request.startDate(),
                request.days(),
                request.budget(),
                request.maxHotelPrice(),
                preferences);
    }
}
