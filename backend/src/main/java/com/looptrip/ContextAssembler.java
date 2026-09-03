package com.looptrip;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 有预算意识的上下文组装器：无状态、不读 session、不做 IO、不调模型。
 * 物理排布五段：指令（system）→ 约束 → 历史摘要 → 画像 → 末尾任务复述（user）。
 * 淘汰级联：全放 → 去画像 → 历史降级为最小摘要；指令、约束、返工意见、末尾复述永不淘汰。
 * 所有取舍进 PromptContext 账本——看不见的决策等于不存在的决策。
 */
@Component
public class ContextAssembler {

    static final String INSTRUCTION = """
            ### SECTION:INSTRUCTION
            你是一名旅行规划师。请直接回答用户的旅行规划问题，不要寒暄。
            航班、酒店、景点和天气的价格、日期与时间必须来自对应工具，引用价格时保持工具返回的原值。
            需要这些事实时主动调用工具；工具返回空列表时，明确回复“该城市/日期暂无数据”，不得用常识、平均值、默认项目或其他日期补造事实。
            返回完整 TripPlan 结构。保留航班号、酒店名和景点名的工具原值，日期使用 yyyy-MM-dd，时间使用 HH:mm。
            活动类型只使用 ATTRACTION、MEAL、TRANSFER；景点、用餐、交通分别对应这三个值。
            price 字段可以转述工具价格用于排查；不要计算或返回总花费。
            """;

    static final String PROFILE_PLACEHOLDER = "用户画像：暂无（跨会话记忆未接入）";

    public PromptContext assemble(PlanGenerationInput input, UserProfile profile,
            int tokenBudget, double safetyMargin) {
        int usableBudget = (int) Math.floor(tokenBudget * (1 - safetyMargin));
        PlanRequest request = input.originalRequest();

        String constraint = constraintSection(input);
        String fullHistory = input.round() == 1 ? "" : historySection(input.previousPlan());
        String minimalHistory = input.round() == 1 ? "" : minimalHistorySection(input.previousPlan());
        List<Preference> preferences = profile == null
                ? List.of()
                : profile.relevantPreferences(request);
        String profileBody = preferences.isEmpty()
                ? PROFILE_PLACEHOLDER
                : preferences.stream().map(Preference::text).reduce((a, b) -> a + "\n" + b).orElseThrow();
        String tail = tailSection(input);

        List<PromptContext.DroppedSection> dropped = new ArrayList<>();
        String history = fullHistory;
        String profileText = profileBody;
        boolean profileIncluded = true;

        if (!fits(constraint, history, profileText, tail, usableBudget)) {
            dropped.add(new PromptContext.DroppedSection("profile", "剩余预算不足"));
            profileIncluded = false;
            profileText = "";
            // 首轮没有历史区，第二级级联无从发生，不能误报 history-detail。
            if (!history.isEmpty() && !fits(constraint, history, profileText, tail, usableBudget)) {
                dropped.add(new PromptContext.DroppedSection("history-detail", "超出剩余预算"));
                history = minimalHistory;
            }
        }

        String userPrompt = renderUser(constraint, history, profileText, tail);
        String systemPrompt = INSTRUCTION.stripTrailing();
        List<String> included = new ArrayList<>(List.of("INSTRUCTION", "CONSTRAINT"));
        if (!history.isEmpty()) included.add("HISTORY");
        if (profileIncluded) included.add("PROFILE");
        return new PromptContext(systemPrompt, userPrompt, included, dropped,
                estimateTokens(systemPrompt + "\n" + userPrompt), usableBudget);
    }

    private boolean fits(String constraint, String history, String profileText, String tail, int usableBudget) {
        String systemPrompt = INSTRUCTION.stripTrailing();
        return estimateTokens(systemPrompt + "\n" + renderUser(constraint, history, profileText, tail)) <= usableBudget;
    }

    private String renderUser(String constraint, String history, String profileText, String tail) {
        StringBuilder out = new StringBuilder();
        out.append("### SECTION:CONSTRAINT\n").append(constraint).append('\n');
        if (!history.isEmpty()) {
            out.append("\n### SECTION:HISTORY\n").append(history).append('\n');
        }
        if (!profileText.isEmpty()) {
            out.append("\n### SECTION:PROFILE\n").append(profileText).append('\n');
        }
        out.append("\n### SECTION:CONSTRAINT\n").append(tail);
        return out.toString();
    }

    private String constraintSection(PlanGenerationInput input) {
        StringBuilder out = new StringBuilder();
        out.append("【原始需求】\n").append(formatRequest(input.originalRequest())).append('\n');
        out.append("请生成一份完整 TripPlan，并遵守系统要求。");
        if (input.round() > 1) {
            String feedback = input.feedbackProblems().isEmpty()
                    ? "无"
                    : String.join("\n", input.feedbackProblems());
            out.append("\n\n【上一轮全部问题】\n").append(feedback);
            out.append("\n\n【修订要求】\n保留已合格部分，逐项修复上述问题，并返回完整修订版，不要只返回差异或补丁。");
        }
        return out.toString();
    }

    private String tailSection(PlanGenerationInput input) {
        PlanRequest request = input.originalRequest();
        String mustVisit = request.mustVisit().isEmpty() ? "无" : String.join("、", request.mustVisit());
        String task = input.round() == 1
                ? "生成本轮完整 TripPlan"
                : "在本轮修订中返回完整修订版 TripPlan";
        return "【本轮任务】\n%s：%s %d 天行程，严格遵守上述硬约束（总预算 %d 元、酒店每晚不超过 %d 元、必去景点：%s）。"
                .formatted(task, request.destination(), request.days(),
                        request.budget(), request.maxHotelPrice(), mustVisit);
    }

    private String historySection(TripPlan plan) {
        if (plan == null) return "【上一版摘要】\n无可解析的上一版 TripPlan";
        StringBuilder out = new StringBuilder();
        out.append("【上一版摘要】\n");
        out.append("destination=").append(orNone(plan.destination()))
                .append("; days=").append(plan.days())
                .append("; startDate=").append(plan.startDate() == null ? "无" : plan.startDate()).append('\n');
        out.append("outboundFlight=").append(flightNumber(plan.outboundFlight()))
                .append("; returnFlight=").append(flightNumber(plan.returnFlight())).append('\n');
        Set<String> hotels = new LinkedHashSet<>();
        Set<String> attractions = new LinkedHashSet<>();
        for (TripDayPlan day : plan.dailyPlans()) {
            if (day.hotel() != null && StringUtils.hasText(day.hotel().name())) {
                hotels.add(day.hotel().name() + "(" + day.date() + ")");
            }
            for (TripActivity activity : day.activities()) {
                if (StringUtils.hasText(activity.name())) attractions.add(activity.name());
            }
        }
        out.append("hotels=").append(hotels.isEmpty() ? "无" : String.join("、", hotels)).append('\n');
        out.append("attractions=").append(attractions.isEmpty() ? "无" : String.join("、", attractions));
        return out.toString();
    }

    private String minimalHistorySection(TripPlan plan) {
        if (plan == null) return "【上一版摘要】\n无可解析的上一版 TripPlan";
        return "【上一版摘要（最小）】\ndestination=" + orNone(plan.destination()) + "; days=" + plan.days();
    }

    private String orNone(String value) {
        return StringUtils.hasText(value) ? value : "无";
    }

    private String flightNumber(TripFlight flight) {
        return flight == null || !StringUtils.hasText(flight.flightNumber()) ? "无" : flight.flightNumber();
    }

    private String formatRequest(PlanRequest request) {
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

    static int estimateTokens(String text) {
        return (int) Math.ceil(text.length() / 1.5);
    }
}
