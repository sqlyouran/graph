package com.looptrip;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class TravelPlanningEngine {

    private final PlanGenerator planGenerator;
    private final BasicContractReview contractReview;
    private final PlanningEventSink eventSink;

    public TravelPlanningEngine(
            PlanGenerator planGenerator,
            BasicContractReview contractReview,
            PlanningEventSink eventSink) {
        this.planGenerator = planGenerator;
        this.contractReview = contractReview;
        this.eventSink = eventSink;
    }

    public PlanResponse plan(PlanRequest request) {
        validate(request);
        List<PlanningEvent> events = new ArrayList<>();
        return eventSink.capture(events, () -> runLoop(request, events));
    }

    private PlanResponse runLoop(PlanRequest request, List<PlanningEvent> events) {
        List<PlanningRoundSnapshot> rounds = new ArrayList<>();
        List<String> feedback = List.of();
        String previousMarkdown = null;
        PlanningRoundSnapshot bestRound = null;
        long totalElapsedMs = 0;

        for (int round = 1; round <= request.maxRounds(); round++) {
            safeSetRound(round);
            int firstEventIndex = events.size();
            emit(PlanningEventType.ROUND_STARTED, "第 " + round + " 轮开始", Map.of());

            PlanGenerationResult generated = planGenerator.generate(new PlanGenerationInput(
                    request,
                    round,
                    previousMarkdown,
                    feedback));
            totalElapsedMs += generated.elapsedMs();
            emit(
                    PlanningEventType.GENERATION_COMPLETED,
                    "第 " + round + " 轮候选生成完成",
                    Map.of("elapsedMs", generated.elapsedMs(), "model", generated.model()));

            BasicContractReviewResult review = contractReview.review(request, generated.markdown());
            emit(
                    PlanningEventType.REVIEW_COMPLETED,
                    review.passed() ? "基础契约通过" : "基础契约发现 " + review.problems().size() + " 个问题",
                    Map.of("passed", review.passed(), "problems", review.problems()));

            if (review.passed()) {
                emit(PlanningEventType.COMPLETED, "基础契约通过，规划完成", Map.of());
            } else if (round < request.maxRounds()) {
                emit(
                        PlanningEventType.FEEDBACK_PREPARED,
                        "全部检查问题进入下一轮",
                        Map.of("problems", review.problems()));
            } else {
                emit(
                        PlanningEventType.MAX_ROUNDS_REACHED,
                        "达到最大轮次，仍有未解决问题",
                        Map.of("problems", review.problems()));
            }

            PlanningRoundSnapshot snapshot = new PlanningRoundSnapshot(
                    round,
                    request,
                    generated.markdown(),
                    generated.model(),
                    generated.elapsedMs(),
                    review,
                    feedback,
                    List.copyOf(events.subList(firstEventIndex, events.size())));
            rounds.add(snapshot);

            if (bestRound == null
                    || review.problems().size() <= bestRound.review().problems().size()) {
                bestRound = snapshot;
            }

            if (review.passed()) {
                return response(
                        bestRound,
                        totalElapsedMs,
                        PlanStatus.COMPLETED,
                        "基础契约通过",
                        rounds,
                        events);
            }

            previousMarkdown = generated.markdown();
            feedback = review.problems();
        }

        return response(
                bestRound,
                totalElapsedMs,
                PlanStatus.MAX_ROUNDS,
                "达到最大轮次，返回当前最好版本",
                rounds,
                events);
    }

    private PlanResponse response(
            PlanningRoundSnapshot bestRound,
            long elapsedMs,
            PlanStatus status,
            String stopReason,
            List<PlanningRoundSnapshot> rounds,
            List<PlanningEvent> events) {
        return new PlanResponse(
                bestRound.markdown(),
                bestRound.model(),
                elapsedMs,
                status,
                stopReason,
                rounds.size(),
                bestRound.review().problems(),
                rounds,
                events);
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
