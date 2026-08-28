package com.looptrip;

import java.util.Map;

public class PlanningIntentGate {
    public record Result(PlanningChatDecision decision, String text) {}

    public Result decide(PlanningIntent intent, boolean confirmed, PlanningSession session) {
        if (intent == null || intent.action() == PlanningIntentAction.UNKNOWN) {
            return new Result(PlanningChatDecision.UNRECOGNIZED, "我没能确定你的意思，请换一种说法。");
        }
        if (intent.action() == PlanningIntentAction.REVISE_BUDGET && !intent.slots().containsKey("budget"))
            return new Result(PlanningChatDecision.CLARIFICATION_REQUIRED, "你希望把总预算调整到多少？请给出具体金额。");
        if (intent.action() == PlanningIntentAction.REVISE_HOTEL_PRICE && !intent.slots().containsKey("maxHotelPrice"))
            return new Result(PlanningChatDecision.CLARIFICATION_REQUIRED, "你希望把酒店每晚限价调整到多少？请给出具体金额。");
        if (intent.action() == PlanningIntentAction.ADD_MUST_VISIT && !intent.slots().containsKey("mustVisit"))
            return new Result(PlanningChatDecision.CLARIFICATION_REQUIRED, "想新增哪些必去景点？");
        if (intent.action() == PlanningIntentAction.ADJUST_PACE && !intent.slots().containsKey("preferences"))
            return new Result(PlanningChatDecision.CLARIFICATION_REQUIRED, "希望怎么调整节奏？例如减少景点或增加活动间隔。");
        if (intent.action() == PlanningIntentAction.NEW_SESSION && !intent.slots().containsKey("destination"))
            return new Result(PlanningChatDecision.CLARIFICATION_REQUIRED, "想把目的地改到哪里？");
        if (intent.action() == PlanningIntentAction.ASK_QUESTION && !intent.slots().containsKey("question"))
            return new Result(PlanningChatDecision.CLARIFICATION_REQUIRED, "请告诉我你想查询什么。");
        if (intent.action().requiresConfirmation() && !confirmed)
            return new Result(PlanningChatDecision.CONFIRM_REQUIRED, intent.echo() + " 这就开始改？");
        return new Result(PlanningChatDecision.EXECUTED, "");
    }
}
