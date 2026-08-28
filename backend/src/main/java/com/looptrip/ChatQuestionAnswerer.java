package com.looptrip;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

@Component
public class ChatQuestionAnswerer implements PlanningQuestionAnswerer {
    private final ChatClient chat;
    public ChatQuestionAnswerer(ChatClient.Builder builder) { this.chat = builder.build(); }
    @Override public String answer(String question, PlanningSession session) {
        try { return chat.prompt().system("只回答用户的问题，简洁明确；无法确认事实时说明暂无可靠数据。").user(question).call().content(); }
        catch (Exception ignored) { return "暂时无法回答这个问题，请稍后再试。"; }
    }
}
