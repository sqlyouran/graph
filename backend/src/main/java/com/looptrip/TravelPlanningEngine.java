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
        validate(request);
        List<PlanningEvent> events = new ArrayList<>();
        return eventSink.capture(events, () -> runLoop(request, events));
    }

    private PlanResponse runLoop(PlanRequest request, List<PlanningEvent> events) {
        if (preflight != null) {
            PreflightResult result = preflight.check(request);
            if (!result.canStart()) return terminalWithoutRounds(result.state(), result.report());
        }
        List<PlanningRoundSnapshot> rounds = new ArrayList<>();
        PlanningLedger ledger = new PlanningLedger();
        List<String> feedback = List.of();
        TripPlan previousPlan = null;
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
            if (guardrail != null) {
                Optional<PlanningStopReport> stopped = guardrail.beforeRound(ledger.snapshot(), request.maxRounds());
                if (stopped.isPresent()) return guarded(bestRound, totalElapsedMs, rounds, stopped.get());
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
            PlanGenerationResult generated = retryExecutor.execute(planGenerator, generationInput);
            totalElapsedMs += generated.elapsedMs();
            emit(
                    PlanningEventType.GENERATION_COMPLETED,
                    "第 " + round + " 轮候选生成完成",
                    Map.of("elapsedMs", generated.elapsedMs(), "model", generated.model()));

            List<String> problems = new ArrayList<>(generated.problems());
            List<ConstraintCheckResult> constraintResults = List.of();
            if (generated.plan() != null) {
                problems.addAll(contractReview.review(request, generated.plan()).problems());
                constraintResults = constraintReviewer.review(request, generated.plan());
                for (ConstraintCheckResult result : constraintResults) {
                    if (!result.passed() && result.severity() == ConstraintSeverity.HARD) {
                        for (String suggestion : result.suggestions()) {
                            problems.add(result.code() + " " + result.name() + "：" + suggestion);
                        }
                    }
                }
            } else if (problems.isEmpty()) {
                problems.add("缺少可检查的结构化行程");
            }
            boolean passed = problems.isEmpty();
            emit(
                    PlanningEventType.REVIEW_COMPLETED,
                    passed ? "两层验收通过" : "两层验收发现 " + problems.size() + " 个问题",
                    Map.of("passed", passed, "problems", problems,
                            "constraintResults", constraintResults));

            if (passed) {
                emit(PlanningEventType.COMPLETED, "两层验收通过，规划完成", Map.of());
            } else if (round < request.maxRounds()) {
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

            if (snapshot.passed()) {
                return response(
                        bestRound,
                        totalElapsedMs,
                        PlanStatus.COMPLETED,
                        "两层验收通过",
                        rounds,
                        PlanningTerminalState.SUCCESS,
                        null);
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
