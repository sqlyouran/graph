package com.looptrip;

import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/plan")
public class PlanController {
    private final TravelPlanningEngine planningEngine;
    private final PlanningSessionStore sessions;
    private final SsePlanningEventSink sse;
    private final PlanningSessionContext sessionContext;
    private final RevisionPolicy revisionPolicy;
    private final Executor executor;
    private final PlanningChatServiceFacade chatService;
    private final PlanningIntentGate intentGate = new PlanningIntentGate();

    public PlanController(TravelPlanningEngine planningEngine) {
        this(planningEngine, null, null, null, null, Runnable::run, null);
    }

    @Autowired
    public PlanController(TravelPlanningEngine planningEngine, PlanningSessionStore sessions,
            SsePlanningEventSink sse, PlanningSessionContext sessionContext,
            RevisionPolicy revisionPolicy, Executor planningSessionExecutor,
            PlanningChatServiceFacade chatService) {
        this.planningEngine = planningEngine;
        this.sessions = sessions;
        this.sse = sse;
        this.sessionContext = sessionContext;
        this.revisionPolicy = revisionPolicy;
        this.executor = planningSessionExecutor;
        this.chatService = chatService;
    }

    public PlanController(TravelPlanningEngine engine, PlanningSessionStore sessions, SsePlanningEventSink sse,
            PlanningSessionContext context, RevisionPolicy policy, Executor executor) {
        this(engine, sessions, sse, context, policy, executor, null);
    }

    @PostMapping("/ask")
    public Object ask(@RequestBody PlanRequest request) {
        if (sessions == null) return planningEngine.plan(request);
        PlanningSession session = sessions.create(request);
        sessions.start(session, false);
        execute(session, request, PlanningSeed.empty(), Map.of());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("sessionId", session.id()));
    }

    @PostMapping("/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable String id) {
        PlanningSession session = requireSession(id);
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("sessionId", id);
        result.put("accepted", true);
        result.put("message", session.state() == PlanningSessionState.CLOSED
                ? "会话已结束，无需再次取消" : "取消请求已接收，正在收尾当前一轮");
        result.put("best", sessions.cancel(session));
        return result;
    }

    @PostMapping("/{id}/revisions")
    public ResponseEntity<Map<String, Object>> revise(@PathVariable String id,
            @RequestBody RevisionRequest revision) {
        PlanningSession session = requireSession(id);
        if (session.state() != PlanningSessionState.CLOSED) {
            throw new SessionOperationException(HttpStatus.CONFLICT, "SESSION_NOT_CLOSED",
                    "当前规划仍在运行，不能同时续修", "请等待当前规划结束后再提交修改");
        }
        RevisionDecision decision = revisionPolicy.apply(session.request(), revision);
        if (!decision.allowed()) {
            throw new SessionOperationException(HttpStatus.BAD_REQUEST, "REVISION_REJECTED",
                    decision.reason(), decision.suggestion());
        }
        PlanningVersion current = currentVersion(session);
        PlanningSeed seed = new PlanningSeed(current.plan(), current.problems());
        session.request(decision.request());
        sessions.start(session, true);
        sessions.publish(session, new PlanningSessionStore.PublicEvent(0, PlanningEventType.REVISION_STARTED,
                "续修请求已生效，正在基于版本 " + current.version() + " 继续规划",
                Map.of("version", current.version(), "requestDiff", decision.diff())));
        execute(session, decision.request(), seed, decision.diff());
        return ResponseEntity.accepted().body(Map.of("sessionId", id, "state", session.state(),
                "message", "续修已开始", "basedOnVersion", current.version(),
                "eventOffset", Math.max(0, session.events().size() - 1)));
    }

    @PostMapping("/{id}/rollback")
    public Map<String, Object> rollback(@PathVariable String id, @RequestParam int version) {
        PlanningSession session = requireSession(id);
        PlanResponse response = sessions.rollback(session, version);
        return Map.of("sessionId", id, "currentVersion", version, "response", response);
    }

    @PostMapping("/{id}/chat")
    public PlanningChatResponse chat(@PathVariable String id, @RequestBody PlanningChatRequest request) {
        PlanningSession session = requireSession(id);
        if (chatService == null) return new PlanningChatResponse(PlanningIntentAction.UNKNOWN, Map.of(), "", PlanningChatDecision.UNRECOGNIZED, "聊天功能不可用", null);
        String utterance = request == null ? "" : request.utterance();
        if (request != null && request.confirmed() && session.pendingIntent() == null)
            return new PlanningChatResponse(PlanningIntentAction.UNKNOWN, Map.of(), "", PlanningChatDecision.CLARIFICATION_REQUIRED, "没有待确认的修改，请重新描述你的需求。", null);
        PlanningIntent intent = request != null && request.confirmed() && session.pendingIntent() != null
                ? session.pendingIntent() : chatService.recognize(utterance, session);
        if (request != null && request.confirmed() && session.pendingIntent() != null) {
            PlanningIntent pending = session.pendingIntent();
            intent = new PlanningIntent(pending.action(), pending.slots(), pending.echo(), utterance);
        }
        sessions.publish(session, new PlanningSessionStore.PublicEvent(0, PlanningEventType.INTENT_RECOGNIZED,
                intent.echo(), Map.of("action", intent.action().name(), "slots", intent.slots(), "echo", intent.echo())));
        PlanningIntentGate.Result gate = intentGate.decide(intent, request != null && request.confirmed(), session);
        if (gate.decision() == PlanningChatDecision.UNRECOGNIZED || gate.decision() == PlanningChatDecision.CLARIFICATION_REQUIRED)
            return new PlanningChatResponse(intent.action(), intent.slots(), intent.echo(), gate.decision(), gate.text(), null);
        if (intent.action() == PlanningIntentAction.ASK_QUESTION) return chatService.question(intent, session);
        if (intent.action() == PlanningIntentAction.NEW_SESSION)
            return new PlanningChatResponse(intent.action(), intent.slots(), intent.echo(), PlanningChatDecision.NEW_SESSION_SUGGESTED, "请确认后用新行程开始规划。", null);
        if (intent.action() == PlanningIntentAction.ROLLBACK) {
            int version = ((Number) intent.slots().getOrDefault("rollbackVersion", Math.max(0, session.currentVersion() - 1))).intValue();
            if (version <= 0) return new PlanningChatResponse(intent.action(), intent.slots(), intent.echo(), PlanningChatDecision.CLARIFICATION_REQUIRED, "当前没有可回滚的上一版方案。", null);
            return new PlanningChatResponse(intent.action(), Map.of("rollbackVersion", version), intent.echo(), PlanningChatDecision.EXECUTED, "已回滚到上一版方案。", rollback(id, version));
        }
        if (!request.confirmed()) {
            session.pendingIntent(intent);
            return new PlanningChatResponse(intent.action(), intent.slots(), intent.echo(), gate.decision(), gate.text(), null);
        }
        session.pendingIntent(null);
        if (intent.action() == PlanningIntentAction.CANCEL) return new PlanningChatResponse(intent.action(), intent.slots(), intent.echo(), PlanningChatDecision.EXECUTED, "已收到取消请求。", cancel(id));
        RevisionRequest revision = toRevision(intent, session.request());
        ResponseEntity<Map<String, Object>> response = revise(id, revision);
        return new PlanningChatResponse(intent.action(), intent.slots(), intent.echo(), PlanningChatDecision.EXECUTED, "修改已开始。", response.getBody());
    }

    private RevisionRequest toRevision(PlanningIntent intent, PlanRequest current) {
        Integer budget = number(intent.slots().get("budget"));
        Integer hotel = number(intent.slots().get("maxHotelPrice"));
        @SuppressWarnings("unchecked") List<String> places = (List<String>) intent.slots().get("mustVisit");
        String preferences = intent.action() == PlanningIntentAction.ADJUST_PACE
                ? current.preferences() + "；调整节奏，安排更宽松" : (String) intent.slots().get("preferences");
        return new RevisionRequest(budget, hotel, preferences, null, places, null, null, null);
    }
    private Integer number(Object value) { return value instanceof Number n ? n.intValue() : null; }

    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable String id) {
        PlanningSession session = requireSession(id);
        SseEmitter output = new SseEmitter(0L);
        BlockingQueue<PlanningSessionStore.PublicEvent> queue = sessions.subscribe(session);
        executor.execute(() -> {
            try {
                while (true) {
                    PlanningSessionStore.PublicEvent event = queue.poll(15, TimeUnit.SECONDS);
                    if (event == null) event = new PlanningSessionStore.PublicEvent(
                            0, PlanningEventType.HEARTBEAT, "", Map.of());
                    output.send(SseEmitter.event().data(event));
                    if (session.state() == PlanningSessionState.CLOSED && queue.isEmpty()) break;
                }
            } catch (Exception ignored) {
            } finally {
                sessions.unsubscribe(session, queue);
                output.complete();
            }
        });
        return output;
    }

    @GetMapping("/{id}")
    public Map<String, Object> snapshot(@PathVariable String id) {
        PlanningSession session = requireSession(id);
        Map<String, Object> snapshot = new java.util.LinkedHashMap<>();
        snapshot.put("sessionId", id);
        snapshot.put("state", session.state());
        snapshot.put("terminalState", session.terminalState());
        snapshot.put("events", session.events());
        snapshot.put("response", session.response() == null ? Map.of() : session.response());
        snapshot.put("versions", session.versions());
        snapshot.put("currentVersion", session.currentVersion());
        return snapshot;
    }

    private void execute(PlanningSession session, PlanRequest request, PlanningSeed seed,
            Map<String, Object> requestDiff) {
        executor.execute(() -> {
            try {
                PlanResponse response = sessionContext.bind(session,
                        () -> sse.forSession(session, () -> planningEngine.plan(request, seed)));
                if (response.terminalState() == PlanningTerminalState.CANCELLED) {
                    sessions.publish(session, new PlanningSessionStore.PublicEvent(0, PlanningEventType.CANCELLED,
                            "当前轮已收尾，规划已取消", Map.of("hasBestPlan", response.plan() != null)));
                }
                sessions.complete(session, response, requestDiff);
            } catch (RuntimeException exception) {
                PlanningStopReport report = new PlanningStopReport("TECHNICAL_FAILURE", "规划服务暂时不可用",
                        exception.getMessage() == null ? "任务执行失败" : exception.getMessage(), "请稍后重试");
                PlanResponse failed = new PlanResponse(null, null, 0, PlanStatus.MAX_ROUNDS, report.reason(), 0,
                        List.of(report.reason()), List.of(), PlanningTerminalState.GUARDED, report);
                sessions.complete(session, failed, requestDiff);
                sessions.publish(session, new PlanningSessionStore.PublicEvent(0,
                        PlanningEventType.GUARD_TRIGGERED, report.reason(), Map.of()));
                sessions.persist(session);
            }
        });
    }

    private PlanningSession requireSession(String id) {
        PlanningSession session = sessions == null ? null : sessions.get(id);
        if (session == null) throw new SessionOperationException(HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND",
                "规划会话不存在或已过期", "请创建新的规划会话");
        return session;
    }

    private PlanningVersion currentVersion(PlanningSession session) {
        return session.versions().stream().filter(item -> item.version() == session.currentVersion()).findFirst()
                .orElseThrow(() -> new SessionOperationException(HttpStatus.CONFLICT, "NO_REVISION_SEED",
                        "当前会话还没有可用于续修的版本", "请先完成至少一轮规划"));
    }
}
