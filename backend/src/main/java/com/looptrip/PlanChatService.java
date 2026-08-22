package com.looptrip;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.concurrent.TimeUnit;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class PlanChatService implements PlanGenerator {

    static final String SYSTEM_PROMPT = """
            你是一名旅行规划师。请直接回答用户的旅行规划问题，不要寒暄。
            航班、酒店、景点和天气的价格、日期与时间必须来自对应工具，引用价格时保持工具返回的原值。
            需要这些事实时主动调用工具；工具返回空列表时，明确回复“该城市/日期暂无数据”，不得用常识、平均值、默认项目或其他日期补造事实。
            返回完整 TripPlan 结构。保留航班号、酒店名和景点名的工具原值，日期使用 yyyy-MM-dd，时间使用 HH:mm。
            活动类型只使用 ATTRACTION、MEAL、TRANSFER；景点、用餐、交通分别对应这三个值。
            price 字段可以转述工具价格用于排查；不要计算或返回总花费。
            """;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    static final String PARSE_FAILURE = "结构化行程解析失败，请返回完整且字段类型正确的 TripPlan";

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
        ChatClient.CallResponseSpec response;
        try {
            response = chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(buildPrompt(input))
                    .call();
        } catch (ModelCallException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ModelCallException("模型调用失败，请稍后重试", exception);
        }

        long elapsedMs;
        try {
            TripPlan plan = response.entity(TripPlan.class);
            elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            if (plan == null) {
                return new PlanGenerationResult(null, model, elapsedMs, java.util.List.of(PARSE_FAILURE));
            }
            return PlanGenerationResult.success(plan, model, elapsedMs);
        } catch (RuntimeException exception) {
            elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            return new PlanGenerationResult(null, model, elapsedMs, java.util.List.of(PARSE_FAILURE));
        }
    }

    static String buildPrompt(PlanGenerationInput input) {
        String originalRequest = formatRequest(input.originalRequest());
        if (input.round() == 1) {
            return """
                    【原始需求】
                    %s

                    请生成一份完整 TripPlan，并遵守系统要求。
                    """.formatted(originalRequest);
        }

        String feedback = input.feedbackProblems().isEmpty()
                ? "无"
                : String.join("\n", input.feedbackProblems());
        return """
                【原始需求】
                %s

                【上一版完整 TripPlan】
                %s

                【上一轮全部问题】
                %s

                【修订要求】
                保留已合格部分，逐项修复上述问题，并返回完整修订版，不要只返回差异或补丁。
                """.formatted(originalRequest, serialize(input.previousPlan()), feedback);
    }

    private static String serialize(TripPlan plan) {
        if (plan == null) return "无可解析的上一版 TripPlan";
        try {
            return OBJECT_MAPPER.writeValueAsString(plan);
        } catch (JsonProcessingException exception) {
            return plan.toString();
        }
    }

    private static String formatRequest(PlanRequest request) {
        String preferences = StringUtils.hasText(request.preferences())
                ? request.preferences().trim()
                : "没有特别偏好";
        String mustVisit = request.mustVisit().isEmpty() ? "无" : String.join("、", request.mustVisit());
        return """
                从%s出发，前往%s，开始日期%s，共%d天，总预算%d元，酒店每晚不超过%d元，偏好：%s，必去景点：%s。
                请查询航班、酒店、景点和每天的天气，并按天输出。
                """.formatted(
                request.origin().trim(),
                request.destination().trim(),
                request.startDate(),
                request.days(),
                request.budget(),
                request.maxHotelPrice(),
                preferences,
                mustVisit);
    }
}
