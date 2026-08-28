package com.looptrip;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Comparator;
import java.util.Optional;

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

    @Autowired
    public TravelPlanningEngine(
            PlanGenerator planGenerator,
            BasicContractReview contractReview,
            TripPlanConstraintReviewer constraintReviewer,
            PlanningEventSink eventSink,
            TravelPlanPreflight preflight,
            LoopGuardrail guardrail,
            TechnicalRetryExecutor retryExecutor,
            PlanningCancellationSignal cancellationSignal) {
        this.planGenerator = planGenerator;
        this.contractReview = contractReview;
        this.constraintReviewer = constraintReviewer;
        this.eventSink = eventSink;
        this.preflight = preflight;
        this.guardrail = guardrail;
        this.retryExecutor = retryExecutor;
        this.cancellationSignal = cancellationSignal;
    }

    TravelPlanningEngine(PlanGenerator planGenerator, BasicContractReview contractReview,
            TripPlanConstraintReviewer constraintReviewer, PlanningEventSink eventSink,
            TravelPlanPreflight preflight, LoopGuardrail guardrail, TechnicalRetryExecutor retryExecutor) {
        this(planGenerator, contractReview, constraintReviewer, eventSink, preflight, guardrail,
                retryExecutor, () -> false);
    }

    TravelPlanningEngine(PlanGenerator planGenerator, BasicContractReview contractReview,
            TripPlanConstraintReviewer constraintReviewer, PlanningEventSink eventSink) {
        this(planGenerator, contractReview, constraintReviewer, eventSink, null, null,
                new TechnicalRetryExecutor(millis -> {}), () -> false);
    }

    public PlanResponse plan(PlanRequest request) {
        return plan(request, PlanningSeed.empty());
    }

    public PlanResponse plan(PlanRequest request, PlanningSeed seed) {
        validate(request);
        List<PlanningEvent> events = new ArrayList<>();
        PlanningSeed effectiveSeed = seed == null ? PlanningSeed.empty() : seed;
        return eventSink.capture(events, () -> runLoop(request, effectiveSeed, events));
    }

    private PlanResponse runLoop(PlanRequest request, PlanningSeed seed, List<PlanningEvent> events) {
        if (preflight != null) {
            PreflightResult result = preflight.check(request);
            if (!result.canStart()) return terminalWithoutRounds(result.state(), result.report());
            emit(PlanningEventType.PREFLIGHT_PASSED, "预检通过", Map.of());
        }
        List<PlanningRoundSnapshot> rounds = new ArrayList<>();
        PlanningLedger ledger = new PlanningLedger();
        List<String> feedback = seed.feedback();
        TripPlan previousPlan = seed.previousPlan();
        PlanningRoundSnapshot bestRound = null;
        long totalElapsedMs = 0;

        for (int round = 1; ; round++) {
            if (cancellationSignal.isCancelled()) {
                PlanningStopReport report = new PlanningStopReport("CANCELLED", "用户已取消规划",
                        "第 " + round + " 轮生成前收到取消信号", "可稍后使用当前最好版本继续规划");
                if (bestRound == null) return terminalWithoutRounds(PlanningTerminalState.CANCELLED, report);
                return response(bestRound, totalElapsedMs, PlanStatus.MAX_ROUNDS, report.reason(), rounds,
                        PlanningTerminalState.CANCELLED, report);
            }
            if (bestRound != null && bestRound.passed()) {
                emit(PlanningEventType.COMPLETED, "两层验收通过，规划完成", Map.of());
                return response(bestRound, totalElapsedMs, PlanStatus.COMPLETED, "两层验收通过", rounds,
                        PlanningTerminalState.SUCCESS, null);
            }
            if (guardrail != null) {
                Optional<PlanningStopReport> stopped = guardrail.beforeRound(ledger.snapshot(), request.maxRounds());
                if (stopped.isPresent()) { emit(PlanningEventType.GUARD_TRIGGERED, stopped.get().reason(), Map.of()); return guarded(bestRound, totalElapsedMs, rounds, stopped.get()); }
            } else if (round > request.maxRounds()) {
                return legacyMaxRounds(bestRound, totalElapsedMs, rounds);
            }
            safeSetRound(round);
            int firstEventIndex = events.size();
            emit(PlanningEventType.ROUND_STARTED, "第 " + round + " 轮开始", Map.of());

            PlanGenerationInput generationInput = new PlanGenerationInput(
                    request,
                    round,
                    previousPlan,
                    feedback);
            emit(
                    PlanningEventType.GENERATION_STARTED,
                    "正在调用模型生成结构化行程",
                    Map.of("model", "configured"));
            PlanGenerationResult generated = retryExecutor.execute(planGenerator, generationInput);
            totalElapsedMs += generated.elapsedMs();
            emit(
                    PlanningEventType.GENERATION_COMPLETED,
                    "第 " + round + " 轮候选生成完成",
                    Map.of("elapsedMs", generated.elapsedMs(), "model", generated.model()));

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
            boolean passed = problems.isEmpty();
            emit(
                    PlanningEventType.REVIEW_COMPLETED,
                    passed ? "两层验收通过" : reviewSummary(contractProblemCount, constraintResults, generated.problems().size()),
                    Map.of("passed", passed, "problems", problems,
                            "constraintResults", constraintResults,
                            "contractProblemCount", contractProblemCount,
                            "hardFailureCount", hardFailureCount));

            if (!passed && round < request.maxRounds()) {
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
            long estimatedTokens = estimateTokens(generationInput, generated);
            PlanningRoundSnapshot snapshot = new PlanningRoundSnapshot(
                    round,
                    request,
                    generated.plan(),
                    generated.model(),
                    generated.elapsedMs(),
                    problems,
                    constraintResults,
                    feedback,
                    List.copyOf(events.subList(firstEventIndex, events.size())),
                    estimatedTokens,
                    fingerprint);
            rounds.add(snapshot);
            ledger.record(new PlanningLedgerEntry(round, estimatedTokens, generated.elapsedMs(), fingerprint));

            if (bestRound == null
                    || snapshot.problems().size() < bestRound.problems().size()) {
                bestRound = snapshot;
            }

            previousPlan = snapshot.plan();
            feedback = snapshot.problems();
        }

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
        int characters = PlanChatService.buildPrompt(input).length();
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
            if (value != null) selected.put((String) keysAndValues[index], value);
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
