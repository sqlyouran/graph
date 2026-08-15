package com.looptrip;

import java.util.concurrent.TimeUnit;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class PlanChatService {

    static final String SYSTEM_PROMPT = """
            你是一名旅行规划师。请直接回答用户的旅行规划问题，不要寒暄。
            使用清晰的 Markdown 格式组织回答，总字数控制在 400 字以内。
            本章没有接入事实查询工具，不要编造酒店价格、景点开放时间等实时信息。
            """;

    private final ChatClient chatClient;
    private final String model;

    public PlanChatService(
            ChatClient.Builder chatClientBuilder,
            @Value("${spring.ai.openai.chat.options.model}") String model) {
        this.chatClient = chatClientBuilder.build();
        this.model = model;
    }

    public AskResponse ask(String question) {
        if (!StringUtils.hasText(question)) {
            throw new PlanValidationException("question must not be blank");
        }

        long startedAt = System.nanoTime();
        try {
            String answer = chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(question.trim())
                    .call()
                    .content();
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

            if (!StringUtils.hasText(answer)) {
                throw new ModelCallException("模型返回了空回答");
            }
            return new AskResponse(answer, model, elapsedMs);
        } catch (ModelCallException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ModelCallException("模型调用失败，请稍后重试", exception);
        }
    }
}
