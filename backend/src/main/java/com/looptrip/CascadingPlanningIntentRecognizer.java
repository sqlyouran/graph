package com.looptrip;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class CascadingPlanningIntentRecognizer implements PlanningIntentRecognizer {
    private static final Pattern NUMBER = Pattern.compile("(\\d+)");
    private final ChatClient chat;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Autowired
    public CascadingPlanningIntentRecognizer(ChatClient.Builder builder) {
        this.chat = builder.build();
    }

    @Override
    public PlanningIntent recognize(String utterance, PlanningSession session) {
        String text = utterance == null ? "" : utterance.trim();
        if (text.isBlank()) return PlanningIntent.unknown(text);
        if (text.matches(".*(酒店).*(太贵|便宜点|贵了|省一点).*" ) && !text.matches(".*\\d+.*"))
            return intent(PlanningIntentAction.REVISE_HOTEL_PRICE, Map.of(), "我理解成：希望降低酒店每晚价格。", text);
        if (text.matches(".*(太贵|便宜点|贵了|省一点|预算少点).*" ) && !text.matches(".*\\d+.*"))
            return intent(PlanningIntentAction.REVISE_BUDGET, Map.of(), "我理解成：希望降低总预算。", text);
        PlanningIntent rule = rule(text);
        return rule != null ? rule : model(text);
    }

    private PlanningIntent rule(String text) {
        if (text.matches(".*(算了|不弄了|取消|停|停止|别规划).*"))
            return intent(PlanningIntentAction.CANCEL, Map.of(), "我理解成：取消当前规划。", text);
        if (text.matches(".*(还是上一版|上一版好|恢复上一版|回到上一版).*"))
            return intent(PlanningIntentAction.ROLLBACK, Map.of(), "我理解成：回到上一版方案。", text);
        if (text.matches(".*(回滚|恢复).*") && text.matches(".*\\d+.*")) {
            Matcher m = NUMBER.matcher(text);
            m.find();
            return intent(PlanningIntentAction.ROLLBACK, Map.of("rollbackVersion", Integer.valueOf(m.group(1))),
                    "我理解成：回滚到第 " + m.group(1) + " 个版本。", text);
        }
        return null;
    }

    private PlanningIntent model(String text) {
        try {
            String raw = chat.prompt().system("""
                    你是旅行规划意图分类器，只输出 JSON，不要解释。
                    action 只能是 REVISE_BUDGET、REVISE_HOTEL_PRICE、ADD_MUST_VISIT、ADJUST_PACE、CANCEL、ROLLBACK、NEW_SESSION、ASK_QUESTION、UNKNOWN。
                    slots 只能包含 budget、maxHotelPrice、mustVisit（字符串数组）、preferences、destination、rollbackVersion。
                    没有明确数字时不得猜预算或价格；相对表达返回 UNKNOWN。问具体景点信息用 ASK_QUESTION。
                    JSON 示例：{"action":"ASK_QUESTION","slots":{"question":"雷峰塔几点关门"},"echo":"我理解成：询问雷峰塔的闭馆时间。"}
                    """).user(text).call().content();
            Map<String, Object> value = mapper.readValue(raw, new TypeReference<>() {});
            PlanningIntentAction action;
            try { action = PlanningIntentAction.valueOf(String.valueOf(value.get("action"))); }
            catch (Exception e) { return PlanningIntent.unknown(text); }
            Object slotsValue = value.get("slots");
            if (slotsValue != null && !(slotsValue instanceof Map<?, ?>)) return PlanningIntent.unknown(text);
            Map<String, Object> slots = new LinkedHashMap<>();
            if (slotsValue instanceof Map<?, ?> map) for (var entry : map.entrySet()) slots.put(String.valueOf(entry.getKey()), entry.getValue());
            if (!validSlots(action, slots)) return PlanningIntent.unknown(text);
            return intent(action, slots, String.valueOf(value.getOrDefault("echo", text)), text);
        } catch (Exception ignored) {
            return PlanningIntent.unknown(text);
        }
    }

    private boolean validSlots(PlanningIntentAction action, Map<String, Object> slots) {
        for (String key : List.of("budget", "maxHotelPrice", "rollbackVersion")) {
            if (slots.containsKey(key) && (!(slots.get(key) instanceof Number n) || n.intValue() <= 0 || n.doubleValue() != n.intValue())) return false;
        }
        if (slots.containsKey("mustVisit")) {
            if (!(slots.get("mustVisit") instanceof List<?> list) || list.stream().anyMatch(item -> !(item instanceof String))) return false;
        }
        return true;
    }

    private PlanningIntent intent(PlanningIntentAction action, Map<String, Object> slots, String echo, String utterance) {
        return new PlanningIntent(action, slots, echo, utterance);
    }
}
