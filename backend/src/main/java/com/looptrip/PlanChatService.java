package com.looptrip;

import java.time.Clock;
import java.util.Map;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class PlanChatService implements PlanGenerator {

    private static final Logger log = LoggerFactory.getLogger(PlanChatService.class);

    static final String SYSTEM_PROMPT = ContextAssembler.INSTRUCTION;

    static final String PARSE_FAILURE = "结构化行程解析失败，请返回完整且字段类型正确的 TripPlan";

    private final ChatClient chatClient;
    private final String model;
    private final Clock clock;
    private final FactBackedPlanGenerator fallback;
    private final PromptDumper promptDumper;
    private final PlanningSessionContext sessionContext;
    private final ContextAssembler contextAssembler;
    private final ContextProperties contextProperties;
    private final PlanningEventSink eventSink;
    private final PreferenceMemoryService preferenceMemory;
    private final PreferenceProperties preferenceProperties;

    @Autowired
    public PlanChatService(
            ChatClient.Builder chatClientBuilder,
            TravelTools travelTools,
            @Value("${spring.ai.openai.chat.options.model}") String model,
            Clock clock,
            FactBackedPlanGenerator fallback,
            PromptDumper promptDumper,
            PlanningSessionContext sessionContext,
            ContextAssembler contextAssembler,
            ContextProperties contextProperties,
            PlanningEventSink eventSink,
            PreferenceMemoryService preferenceMemory,
            PreferenceProperties preferenceProperties) {
        this.chatClient = chatClientBuilder.defaultTools(travelTools).build();
        this.model = model;
        this.clock = clock;
        this.fallback = fallback;
        this.promptDumper = promptDumper;
        this.sessionContext = sessionContext;
        this.contextAssembler = contextAssembler;
        this.contextProperties = contextProperties;
        this.eventSink = eventSink;
        this.preferenceMemory = preferenceMemory;
        this.preferenceProperties = preferenceProperties;
    }

    PlanChatService(ChatClient.Builder chatClientBuilder, TravelTools travelTools, String model) {
        this(chatClientBuilder, travelTools, model, null, null, null);
    }

    PlanChatService(ChatClient.Builder chatClientBuilder, TravelTools travelTools, String model,
            FactBackedPlanGenerator fallback) {
        this(chatClientBuilder, travelTools, model, fallback, null, null);
    }

    PlanChatService(ChatClient.Builder chatClientBuilder, TravelTools travelTools, String model,
            FactBackedPlanGenerator fallback, PromptDumper promptDumper, PlanningSessionContext sessionContext) {
        this(chatClientBuilder, travelTools, model, Clock.systemUTC(), fallback, promptDumper, sessionContext,
                new ContextAssembler(), new ContextProperties(6000, 0.15), null, null, null);
    }

    @Override
    public PlanGenerationResult generate(PlanGenerationInput input) {
        long startedAt = clock.millis();
        PlanningSession current = sessionContext == null ? null : sessionContext.current();
        String userId = current == null ? null : current.userId();
        UserProfile profile = UserProfile.empty();
        if (preferenceMemory != null && userId != null) {
            PreferenceMemoryService.ProfileLoad load = preferenceMemory.profileFor(userId);
            emitForgotten(load.forgotten());
            profile = load.profile();
        }
        int maxRecall = preferenceProperties == null ? 8 : preferenceProperties.maxRecall();
        PromptContext context = contextAssembler.assemble(input, profile,
                contextProperties.maxTokens(), contextProperties.safetyMargin(), maxRecall);
        emitContextAssembled(context);
        dumpCurrentPrompt(input, context);
        if (preferenceMemory != null && userId != null && context.includedSections().contains("PROFILE")) {
            preferenceMemory.markRecalled(userId, input.originalRequest(), maxRecall);
        }
        ChatClient.CallResponseSpec response;
        try {
            response = chatClient.prompt()
                    .system(context.systemPrompt())
                    .user(context.userPrompt())
                    .call();
        } catch (ModelCallException exception) {
            return fallbackOrThrow(input, startedAt, exception);
        } catch (Exception exception) {
            return fallbackOrThrow(input, startedAt,
                    new ModelCallException("模型调用失败，请稍后重试", exception, isTransient(exception)));
        }

        long elapsedMs;
        try {
            TripPlan plan = response.entity(TripPlan.class);
            elapsedMs = clock.millis() - startedAt;
            if (plan == null) {
                return new PlanGenerationResult(null, model, elapsedMs, java.util.List.of(PARSE_FAILURE));
            }
            return PlanGenerationResult.success(plan, model, elapsedMs);
        } catch (RuntimeException exception) {
            if (!isTimeout(exception)) {
                // 模型偶发用散文代替结构化 JSON，属于一次性抽风：先原样重试一轮，仍失败再走兜底。
                TripPlan retried = retryParseOnce(context);
                if (retried != null) {
                    return PlanGenerationResult.success(retried, model, clock.millis() - startedAt);
                }
            }
            elapsedMs = clock.millis() - startedAt;
            if (fallback != null) {
                log.warn(isTimeout(exception)
                        ? "Model response timed out; using fact-backed fallback"
                        : "Structured TripPlan parsing failed after one retry; using fact-backed fallback", exception);
                return PlanGenerationResult.success(fallback.generate(input.originalRequest()),
                        model + "+fact-fallback", elapsedMs);
            }
            return new PlanGenerationResult(null, model, elapsedMs, java.util.List.of(PARSE_FAILURE));
        }
    }

    private TripPlan retryParseOnce(PromptContext context) {
        try {
            return chatClient.prompt()
                    .system(context.systemPrompt())
                    .user(context.userPrompt())
                    .call()
                    .entity(TripPlan.class);
        } catch (RuntimeException retryFailure) {
            log.warn("TripPlan parse retry also failed; falling back", retryFailure);
            return null;
        }
    }

    private PlanGenerationResult fallbackOrThrow(PlanGenerationInput input, long startedAt,
            ModelCallException exception) {
        if (fallback == null || !exception.retryable()) throw exception;
        log.warn("Model call failed; using fact-backed fallback", exception);
        return PlanGenerationResult.success(fallback.generate(input.originalRequest()),
                model + "+fact-fallback", clock.millis() - startedAt);
    }

    private boolean isTransient(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            String text = (current.getClass().getName() + " " + current.getMessage()).toLowerCase();
            // A full model timeout is unlikely to recover immediately and retrying it can
            // multiply one request into several minutes of silent waiting.
            if (text.contains("timeout") || text.contains("timed out") || text.contains("request cancelled")) {
                return false;
            }
            if (text.contains("429") || text.matches(".*\\b5\\d\\d\\b.*")) return true;
        }
        return false;
    }

    private boolean isTimeout(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            String text = (current.getClass().getName() + " " + current.getMessage()).toLowerCase();
            if (text.contains("timeout") || text.contains("timed out") || text.contains("request cancelled")) {
                return true;
            }
        }
        return false;
    }

    private void dumpCurrentPrompt(PlanGenerationInput input, PromptContext context) {
        if (promptDumper == null) return;
        PlanningSession session = sessionContext == null ? null : sessionContext.current();
        String sessionId = session == null ? "no-session" : session.id();
        promptDumper.dump(sessionId, input.round(), context.systemPrompt(), context.userPrompt());
    }

    private void emitContextAssembled(PromptContext context) {
        if (eventSink == null) return;
        eventSink.emit(PlanningEventType.CONTEXT_ASSEMBLED, "上下文组装完成", Map.of(
                "includedSections", context.includedSections(),
                "droppedSections", context.droppedSections().stream()
                        .map(PromptContext.DroppedSection::toString)
                        .toList(),
                "estimatedTokens", context.estimatedTokens(),
                "usableBudget", context.usableBudget()));
    }

    private void emitForgotten(java.util.List<PreferenceMemoryService.Forgotten> forgotten) {
        if (eventSink == null || forgotten == null) return;
        for (PreferenceMemoryService.Forgotten entry : forgotten) {
            eventSink.emit(PlanningEventType.PREFERENCE_FORGOTTEN, "偏好已遗忘：" + entry.content(), Map.of(
                    "field", entry.field(),
                    "content", entry.content(),
                    "forgetReason", entry.reason()));
        }
    }
}
