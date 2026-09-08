package com.looptrip;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class TravelPlanningEngine {

    private final PlanGenerator planGenerator;
    private final BasicContractReview contractReview;
    private final TripPlanConstraintReviewer constraintReviewer;
    private final PlanningEventSink eventSink;
    private final TravelPlanPreflight preflight;
    private final LoopGuardrail guardrail;
    private final TechnicalRetryExecutor retryExecutor;
    private final PlanningCancellationSignal cancellationSignal;
    private final TravelGraphFactory graphFactory;
    private final ExecutorService toolPool;

    @Autowired
    public TravelPlanningEngine(
            PlanGenerator planGenerator,
            BasicContractReview contractReview,
            TripPlanConstraintReviewer constraintReviewer,
            PlanningEventSink eventSink,
            TravelPlanPreflight preflight,
            LoopGuardrail guardrail,
            TechnicalRetryExecutor retryExecutor,
            PlanningCancellationSignal cancellationSignal,
            TravelGraphFactory graphFactory) {
        this.planGenerator = planGenerator;
        this.contractReview = contractReview;
        this.constraintReviewer = constraintReviewer;
        this.eventSink = eventSink;
        this.preflight = preflight;
        this.guardrail = guardrail;
        this.retryExecutor = retryExecutor;
        this.cancellationSignal = cancellationSignal;
        this.graphFactory = graphFactory;
        this.toolPool = new PropagatingExecutor(newToolPool());
    }

    TravelPlanningEngine(PlanGenerator planGenerator, BasicContractReview contractReview,
            TripPlanConstraintReviewer constraintReviewer, PlanningEventSink eventSink,
            TravelPlanPreflight preflight, LoopGuardrail guardrail, TechnicalRetryExecutor retryExecutor) {
        this(planGenerator, contractReview, constraintReviewer, eventSink, preflight, guardrail,
                retryExecutor, () -> false, null);
    }

    TravelPlanningEngine(PlanGenerator planGenerator, BasicContractReview contractReview,
            TripPlanConstraintReviewer constraintReviewer, PlanningEventSink eventSink,
            TravelPlanPreflight preflight, LoopGuardrail guardrail, TechnicalRetryExecutor retryExecutor,
            PlanningCancellationSignal cancellationSignal) {
        this(planGenerator, contractReview, constraintReviewer, eventSink, preflight, guardrail,
                retryExecutor, cancellationSignal, null);
    }

    TravelPlanningEngine(PlanGenerator planGenerator, BasicContractReview contractReview,
            TripPlanConstraintReviewer constraintReviewer, PlanningEventSink eventSink) {
        this(planGenerator, contractReview, constraintReviewer, eventSink, null, null,
                new TechnicalRetryExecutor(millis -> {}), () -> false, null);
    }

    /** 独立 tool-pool：有界并发 4，事实查询不占用请求线程，也不阻塞 planning 侧。 */
    private static ExecutorService newToolPool() {
        java.util.concurrent.atomic.AtomicInteger seq = new java.util.concurrent.atomic.AtomicInteger();
        return java.util.concurrent.Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "tool-pool-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    /** 事件上下文透传池：事实节点在池线程发射事件，sink 里的会话/轮次需随任务跨线程携带。 */
    private final class PropagatingExecutor extends java.util.concurrent.AbstractExecutorService {
        private final ExecutorService delegate;

        PropagatingExecutor(ExecutorService delegate) {
            this.delegate = delegate;
        }

        @Override
        protected <T> java.util.concurrent.RunnableFuture<T> newTaskFor(java.util.concurrent.Callable<T> callable) {
            return super.newTaskFor(eventSink.propagate(callable));
        }

        @Override public void execute(Runnable command) { delegate.execute(command); }
        @Override public void shutdown() { delegate.shutdown(); }
        @Override public List<Runnable> shutdownNow() { return delegate.shutdownNow(); }
        @Override public boolean isShutdown() { return delegate.isShutdown(); }
        @Override public boolean isTerminated() { return delegate.isTerminated(); }
        @Override public boolean awaitTermination(long timeout, java.util.concurrent.TimeUnit unit)
                throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }
    }

    @jakarta.annotation.PreDestroy
    void shutdownToolPool() {
        toolPool.shutdownNow();
    }

    public PlanResponse plan(PlanRequest request) {
        return plan(request, PlanningSeed.empty());
    }

    public PlanResponse plan(PlanRequest request, PlanningSeed seed) {
        validate(request);
        List<PlanningEvent> events = new ArrayList<>();
        PlanningSeed effectiveSeed = seed == null ? PlanningSeed.empty() : seed;
        return eventSink.capture(events, () -> runGraph(request, effectiveSeed, events));
    }

    private PlanResponse runGraph(PlanRequest request, PlanningSeed seed, List<PlanningEvent> events) {
        TravelGraphFactory factory = graphFactory != null ? graphFactory : new TravelGraphFactory(null, eventSink);
        GraphDefinition<GraphState> definition = factory.build(
                this::runPreflightNode,
                state -> runGenerateNode(state, events),
                state -> runValidateNode(state, events));
        AtomicReference<PlanningStopReport> guardReport = new AtomicReference<>();
        LightGraph<GraphState> graph = new LightGraph<>(25, toolPool, 5_000, 10_000);
        LightGraph.RunResult<GraphState> run = graph.run(definition,
                GraphState.initial(request, seed, guardrail == null),
                List.of(cancellationGuard(), guardrailGuard(guardReport)));
        return mapResponse(run, guardReport);
    }

    private PreNodeGuard<GraphState> cancellationGuard() {
        return (node, state) -> node.equals("generate") && cancellationSignal.isCancelled()
                ? Optional.of("CANCELLED")
                : Optional.empty();
    }

    private PreNodeGuard<GraphState> guardrailGuard(AtomicReference<PlanningStopReport> guardReport) {
        return (node, state) -> {
            if (!node.equals("generate") || guardrail == null) {
                return Optional.empty();
            }
            Optional<PlanningStopReport> stopped =
                    guardrail.beforeRound(state.ledgerSnapshot(), state.request().maxRounds());
            if (stopped.isPresent()) {
                guardReport.set(stopped.get());
                emit(PlanningEventType.GUARD_TRIGGERED, stopped.get().reason(), Map.of());
            }
            return stopped.map(report -> "GUARDED");
        };
    }

    private NodeResult runPreflightNode(GraphState state) {
        if (preflight == null) {
            return NodeResult.of(StateDelta.none());
        }
        PreflightResult result = preflight.check(state.request());
        if (!result.canStart()) {
            return NodeResult.of(StateDelta.preflightStopped(result));
        }
        emit(PlanningEventType.PREFLIGHT_PASSED, "预检通过", Map.of());
        return NodeResult.of(StateDelta.none());
    }

    private NodeResult runGenerateNode(GraphState state, List<PlanningEvent> events) {
        int round = state.round() + 1;
        safeSetRound(round);
        int firstEventIndex = events.size();
        emit(PlanningEventType.ROUND_STARTED, "第 " + round + " 轮开始", Map.of());
        emit(PlanningEventType.FACTS_ASSEMBLED, factsSummary(state.facts()), Map.of(
                "flights", slotStatus(state.facts().flights()),
                "hotels", slotStatus(state.facts().hotels()),
                "attractions", slotStatus(state.facts().attractions()),
                "weather", slotStatus(state.facts().weather())));

        PlanGenerationInput generationInput = new PlanGenerationInput(
                state.request(),
                round,
                state.previousPlan(),
                state.feedback(),
                state.facts());
        emit(
                PlanningEventType.GENERATION_STARTED,
                "正在调用模型生成结构化行程",
                Map.of("model", "configured"));
        PlanGenerationResult generated = retryExecutor.execute(planGenerator, generationInput);
        emit(
                PlanningEventType.GENERATION_COMPLETED,
                "第 " + round + " 轮候选生成完成",
                Map.of("elapsedMs", generated.elapsedMs(), "model", generated.model()));
        return NodeResult.of(StateDelta.roundStarted(
                round, firstEventIndex, generationInput, generated, generated.elapsedMs()));
    }

    private NodeResult runValidateNode(GraphState state, List<PlanningEvent> events) {
        PlanRequest request = state.request();
        PlanGenerationResult generated = state.lastGenerated();
        List<String> problems = new ArrayList<>(generated.problems());
        List<ConstraintCheckResult> constraintResults = List.of();
        int contractProblemCount = 0;
        int hardFailureCount = 0;
        if (generated.plan() != null) {
            emitSelection(generated.plan());
            List<String> contractProblems = contractReview.review(request, generated.plan()).problems();
            contractProblemCount = contractProblems.size();
            problems.addAll(contractProblems);
            constraintResults = constraintReviewer.review(request, generated.plan());
            for (ConstraintCheckResult result : constraintResults) {
                if (!result.passed() && result.severity() == ConstraintSeverity.HARD) {
                    hardFailureCount++;
                    String evidence = result.evidence().isEmpty()
                            ? "未提供失败原因"
                            : String.join("；", result.evidence());
                    String suggestions = result.suggestions().isEmpty()
                            ? "请根据检查证据修正"
                            : String.join("；", result.suggestions());
                    problems.add(result.code() + " " + result.name() + "：原因：" + evidence
                            + "；修改：" + suggestions);
                }
            }
        } else if (problems.isEmpty()) {
            problems.add("缺少可检查的结构化行程");
            contractProblemCount = 1;
        }
        boolean coreMissing = state.facts().coreMissing();
        if (coreMissing) {
            problems.add("核心事实缺失，禁止正式交付：" + coreMissingReason(state.facts()));
        }
        boolean passed = problems.isEmpty();
        emit(
                PlanningEventType.REVIEW_COMPLETED,
                passed ? "两层验收通过" : reviewSummary(contractProblemCount, constraintResults, generated.problems().size()),
                Map.of("passed", passed, "problems", problems,
                        "constraintResults", constraintResults,
                        "contractProblemCount", contractProblemCount,
                        "hardFailureCount", hardFailureCount));

        if (!passed && state.round() < request.maxRounds()) {
            emit(
                    PlanningEventType.FEEDBACK_PREPARED,
                    "全部检查问题进入下一轮",
                    Map.of("problems", problems));
        } else {
            emit(
                    PlanningEventType.MAX_ROUNDS_REACHED,
                    "达到最大轮次，仍有未解决问题",
                    Map.of("problems", problems));
        }

        String fingerprint = hardFingerprint(constraintResults);
        long estimatedTokens = estimateTokens(state.lastGenerationInput(), generated);
        PlanningRoundSnapshot snapshot = new PlanningRoundSnapshot(
                state.round(),
                request,
                generated.plan(),
                generated.model(),
                generated.elapsedMs(),
                problems,
                constraintResults,
                state.feedback(),
                List.copyOf(events.subList(state.firstEventIndex(), events.size())),
                estimatedTokens,
                fingerprint);

        if (passed) {
            emit(PlanningEventType.COMPLETED, "两层验收通过，规划完成", Map.of());
        }

        PlanningRoundSnapshot currentBest = state.bestRound();
        PlanningRoundSnapshot bestRoundUpdate = currentBest == null
                || snapshot.problems().size() < currentBest.problems().size()
                ? snapshot
                : null;
        return NodeResult.of(StateDelta.validated(
                snapshot.problems(),
                snapshot.plan(),
                bestRoundUpdate,
                snapshot,
                new PlanningLedgerEntry(state.round(), estimatedTokens, generated.elapsedMs(), fingerprint),
                passed,
                coreMissing));
    }

    private PlanResponse mapResponse(LightGraph.RunResult<GraphState> run,
            AtomicReference<PlanningStopReport> guardReport) {
        GraphState state = run.state();
        switch (run.terminalLabel()) {
            case "END":
                return response(state.bestRound(), state.totalElapsedMs(), PlanStatus.COMPLETED,
                        endReason(state), state.rounds(), PlanningTerminalState.SUCCESS, null);
            case "GUARDED":
                if (state.coreFactsMissing()) {
                    return coreFactsGuarded(state);
                }
                return run.stoppedByGuard()
                        ? guarded(state.bestRound(), state.totalElapsedMs(), state.rounds(), guardReport.get())
                        : legacyMaxRounds(state.bestRound(), state.totalElapsedMs(), state.rounds());
            case "PREFLIGHT_FAILED":
                return terminalWithoutRounds(state.preflightStop().state(), state.preflightStop().report());
            case "CANCELLED": {
                PlanningStopReport report = new PlanningStopReport("CANCELLED", "用户已取消规划",
                        "第 " + (state.round() + 1) + " 轮生成前收到取消信号", "可稍后使用当前最好版本继续规划");
                if (state.bestRound() == null) {
                    return terminalWithoutRounds(PlanningTerminalState.CANCELLED, report);
                }
                return response(state.bestRound(), state.totalElapsedMs(), PlanStatus.MAX_ROUNDS,
                        report.reason(), state.rounds(), PlanningTerminalState.CANCELLED, report);
            }
            default:
                throw new IllegalStateException("未知的图终态：" + run.terminalLabel());
        }
    }

    private String factsSummary(PlanningFacts facts) {
        return "四路事实交卷：航班=" + slotStatus(facts.flights()) + "，酒店=" + slotStatus(facts.hotels())
                + "，景点=" + slotStatus(facts.attractions()) + "，天气=" + slotStatus(facts.weather());
    }

    private String slotStatus(PlanningFacts.FactSlot slot) {
        if (slot == null) {
            return "未查询";
        }
        return slot.ok() ? (slot.text().isEmpty() ? "已交代" : "有数据") : "降级：" + slot.note();
    }

    private String coreMissingReason(PlanningFacts facts) {
        List<String> parts = new ArrayList<>();
        if (facts.flights() != null && !facts.flights().ok()) {
            parts.add("航班（" + facts.flights().note() + "）");
        }
        if (facts.hotels() != null && !facts.hotels().ok()) {
            parts.add("酒店（" + facts.hotels().note() + "）");
        }
        return String.join("、", parts);
    }

    private String endReason(GraphState state) {
        List<String> warnings = state.facts().warnings();
        return warnings.isEmpty()
                ? "两层验收通过"
                : "两层验收通过（带警告交付：" + String.join("；", warnings) + "）";
    }

    private PlanResponse coreFactsGuarded(GraphState state) {
        PlanningStopReport report = new PlanningStopReport("CORE_FACTS_MISSING", "核心事实缺失，禁止正式交付",
                coreMissingReason(state.facts()), "补齐快照覆盖或改用覆盖范围内目的地后重试");
        return response(state.bestRound(), state.totalElapsedMs(), PlanStatus.MAX_ROUNDS,
                report.reason() + "：" + coreMissingReason(state.facts()), state.rounds(),
                PlanningTerminalState.GUARDED, report);
    }

    private PlanResponse legacyMaxRounds(PlanningRoundSnapshot bestRound, long totalElapsedMs,
            List<PlanningRoundSnapshot> rounds) {
        return response(
                bestRound,
                totalElapsedMs,
                PlanStatus.MAX_ROUNDS,
                "达到最大轮次（" + rounds.size() + " 轮），仍有 "
                        + bestRound.problems().size() + " 个未解决问题；返回问题最少的第 "
                        + bestRound.round() + " 轮",
                rounds,
                PlanningTerminalState.GUARDED,
                new PlanningStopReport("G4_MAX_ROUNDS", "已达到最大轮次", "已完成 " + rounds.size() + " 轮",
                        "提高最大轮次后重试"));
    }

    private PlanResponse response(
            PlanningRoundSnapshot bestRound,
            long elapsedMs,
            PlanStatus status,
            String stopReason,
            List<PlanningRoundSnapshot> rounds,
            PlanningTerminalState terminalState,
            PlanningStopReport stopReport) {
        return new PlanResponse(
                bestRound.plan(),
                bestRound.model(),
                elapsedMs,
                status,
                stopReason,
                rounds.size(),
                bestRound.problems(),
                rounds,
                terminalState,
                stopReport);
    }

    private PlanResponse guarded(PlanningRoundSnapshot bestRound, long elapsedMs,
            List<PlanningRoundSnapshot> rounds, PlanningStopReport report) {
        return response(bestRound, elapsedMs, PlanStatus.MAX_ROUNDS,
                report.reason() + "；返回问题最少的第 " + bestRound.round() + " 轮",
                rounds, PlanningTerminalState.GUARDED, report);
    }

    private PlanResponse terminalWithoutRounds(PlanningTerminalState state, PlanningStopReport report) {
        return new PlanResponse(null, null, 0, PlanStatus.MAX_ROUNDS, report.reason(), 0,
                List.of(report.reason()), List.of(), state, report);
    }

    private long estimateTokens(PlanGenerationInput input, PlanGenerationResult result) {
        // 总账护栏只关心文本量级：用请求 + 返工意见 + 产出物做代理。
        // 第 8-2 节起 Engine 不再引用 PlanChatService 的 prompt 拼装（架构边界见 PlanningArchitectureTests）。
        int characters = String.valueOf(input.originalRequest()).length()
                + input.feedbackProblems().stream().mapToInt(String::length).sum();
        if (result.plan() != null) characters += result.plan().toString().length();
        characters += result.problems().stream().mapToInt(String::length).sum();
        return (characters + 3L) / 4L;
    }

    private String hardFingerprint(List<ConstraintCheckResult> results) {
        return results.stream().filter(item -> !item.passed() && item.severity() == ConstraintSeverity.HARD)
                .sorted(Comparator.comparing(ConstraintCheckResult::code))
                .map(item -> item.code() + ":" + item.evidence().stream().sorted().reduce((a, b) -> a + "|" + b).orElse(""))
                .reduce((a, b) -> a + "||" + b).orElse("");
    }

    private String reviewSummary(int contractProblemCount, List<ConstraintCheckResult> constraintResults,
            int generationProblemCount) {
        List<String> parts = new ArrayList<>();
        if (generationProblemCount > 0) parts.add(generationProblemCount + " 个生成问题");
        if (contractProblemCount > 0) parts.add(contractProblemCount + " 个契约问题");
        List<String> hardFailures = constraintResults.stream()
                .filter(item -> !item.passed() && item.severity() == ConstraintSeverity.HARD)
                .map(item -> item.code() + " " + item.name())
                .toList();
        if (!hardFailures.isEmpty()) {
            parts.add(hardFailures.size() + " 项必改问题（" + String.join("、", hardFailures) + "）");
        }
        return "两层验收发现 " + String.join("、", parts);
    }

    private void emitSelection(TripPlan plan) {
        List<Map<String, Object>> hotels = plan.dailyPlans().stream()
                .filter(day -> day.hotel() != null)
                .map(day -> selectionMap(
                        "date", day.date(),
                        "name", day.hotel().name(),
                        "area", day.hotel().area(),
                        "pricePerNight", day.hotel().pricePerNight()))
                .toList();
        List<Map<String, Object>> attractions = plan.dailyPlans().stream()
                .flatMap(day -> day.activities().stream()
                        .filter(activity -> "ATTRACTION".equalsIgnoreCase(activity.type()))
                        .map(activity -> selectionMap(
                                "date", day.date(),
                                "name", activity.name(),
                                "area", activity.area(),
                                "startTime", activity.startTime(),
                                "endTime", activity.endTime())))
                .toList();
        Map<String, Object> details = new java.util.LinkedHashMap<>();
        if (plan.outboundFlight() != null) details.put("selectedOutboundFlight", flightSelection(plan.outboundFlight()));
        if (plan.returnFlight() != null) details.put("selectedReturnFlight", flightSelection(plan.returnFlight()));
        details.put("selectedHotels", hotels);
        details.put("selectedAttractions", attractions);
        details.put("summary", selectionSummary(plan, hotels, attractions));
        emit(PlanningEventType.SELECTION_COMPLETED, "模型已完成方案选择", details);
    }

    private Map<String, Object> flightSelection(TripFlight flight) {
        Map<String, Object> selected = new java.util.LinkedHashMap<>();
        selected.put("flightNumber", flight.flightNumber());
        selected.put("origin", flight.origin());
        selected.put("destination", flight.destination());
        selected.put("departureTime", flight.departureTime());
        selected.put("arrivalTime", flight.arrivalTime());
        selected.put("price", flight.price());
        return selected;
    }

    private Map<String, Object> selectionMap(Object... keysAndValues) {
        Map<String, Object> selected = new java.util.LinkedHashMap<>();
        for (int index = 0; index < keysAndValues.length; index += 2) {
            Object value = keysAndValues[index + 1];
            selected.put((String) keysAndValues[index], value);
        }
        return selected;
    }

    private String selectionSummary(TripPlan plan, List<Map<String, Object>> hotels,
            List<Map<String, Object>> attractions) {
        List<String> choices = new ArrayList<>();
        if (plan.outboundFlight() != null) choices.add("去程 " + plan.outboundFlight().flightNumber());
        if (plan.returnFlight() != null) choices.add("返程 " + plan.returnFlight().flightNumber());
        List<String> hotelNames = hotels.stream().map(hotel -> String.valueOf(hotel.get("name"))).distinct().toList();
        if (!hotelNames.isEmpty()) choices.add("酒店 " + String.join("、", hotelNames));
        List<String> attractionNames = attractions.stream().map(item -> String.valueOf(item.get("name"))).toList();
        if (!attractionNames.isEmpty()) choices.add("景点 " + String.join("、", attractionNames));
        return choices.isEmpty() ? "模型未选出可展示的航班、酒店或景点" : String.join("；", choices);
    }

    private void validate(PlanRequest request) {
        if (request == null) throw new PlanValidationException("request must not be null");
        if (!StringUtils.hasText(request.origin())) throw new PlanValidationException("origin must not be blank");
        if (!StringUtils.hasText(request.destination())) throw new PlanValidationException("destination must not be blank");
        if (request.startDate() == null) throw new PlanValidationException("startDate must not be null");
        if (request.days() < 1 || request.days() > 7) throw new PlanValidationException("days must be between 1 and 7");
        if (request.budget() <= 0) throw new PlanValidationException("budget must be greater than 0");
        if (request.maxHotelPrice() <= 0) {
            throw new PlanValidationException("maxHotelPrice must be greater than 0");
        }
        if (request.maxRounds() < 1 || request.maxRounds() > 5) {
            throw new PlanValidationException("maxRounds must be between 1 and 5");
        }
    }

    private void safeSetRound(int round) {
        try {
            eventSink.setRound(round);
        } catch (RuntimeException ignored) {
            // Observability must not control the planning flow.
        }
    }

    private void emit(PlanningEventType type, String message, Map<String, Object> details) {
        try {
            eventSink.emit(type, message, details);
        } catch (RuntimeException ignored) {
            // Observability must not control the planning flow.
        }
    }
}
