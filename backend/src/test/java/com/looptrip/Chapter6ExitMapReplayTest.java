package com.looptrip;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Chapter6ExitMapReplayTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC);

    @Test @Tag("preflight")
    void parameterErrorFailsBeforeLoop() {
        Chapter6ScriptedPlanGenerator generator = generator(Chapter6ScriptedPlanGenerator.Scene.PASS);
        assertThatThrownBy(() -> engine(generator, limits(), () -> false).plan(request(0, 3000, 700, List.of())))
                .isInstanceOf(PlanValidationException.class);
        assertThat(generator.calls()).isZero();
        System.out.println("[参数错误] Loop 前失败 | rounds=0 | calls=0");
    }

    @Test @Tag("preflight")
    void fourPreflightRejectionsNeverGenerate() {
        assertPreflight(requestTo("火星", 3_000, 700, List.of()), PlanningTerminalState.UNSUPPORTED, "P0_UNSUPPORTED");
        assertPreflight(request(3, 3_000, 700, List.of("不存在景点")), PlanningTerminalState.INFEASIBLE, "P1_MUST_VISIT");
        assertPreflight(request(3, 3_000, 300, List.of()), PlanningTerminalState.INFEASIBLE, "P2_NO_FEASIBLE_FACTS");
        assertPreflight(request(3, 100, 700, List.of()), PlanningTerminalState.INFEASIBLE, "P3_MINIMUM_BUDGET");
    }

    @Test @Tag("success")
    void successInOneRound() {
        Chapter6ScriptedPlanGenerator generator = generator(Chapter6ScriptedPlanGenerator.Scene.PASS);
        PlanResponse response = engine(generator, limits(), () -> false)
                .plan(request(4, 3000, 700, List.of("灵隐寺")));
        assertExit(response, PlanningTerminalState.SUCCESS, 1, generator, 1, null);
        assertLedger(response, 1);
    }

    @Test @Tag("success")
    void successAfterThreeDifferentFingerprints() {
        Chapter6ScriptedPlanGenerator generator = generator(Chapter6ScriptedPlanGenerator.Scene.CONVERGE);
        PlanResponse response = engine(generator, limits(), () -> false)
                .plan(request(4, 3000, 700, List.of("灵隐寺")));
        assertExit(response, PlanningTerminalState.SUCCESS, 3, generator, 3, null);
        assertThat(response.rounds()).extracting(PlanningRoundSnapshot::hardFailureFingerprint)
                .containsExactly(
                        response.rounds().get(0).hardFailureFingerprint(),
                        response.rounds().get(1).hardFailureFingerprint(), "");
        assertThat(response.rounds().get(0).hardFailureFingerprint()).startsWith("C6:");
        assertThat(response.rounds().get(1).hardFailureFingerprint()).startsWith("C1:");
        assertLedger(response, 3);
    }

    @Test @Tag("guards")
    void g1StopsBeforeThirdCallOnIdenticalC6() {
        Chapter6ScriptedPlanGenerator generator = generator(Chapter6ScriptedPlanGenerator.Scene.C6_STUCK);
        PlanResponse response = engine(generator, limits(), () -> false)
                .plan(request(5, 3000, 700, List.of("灵隐寺")));
        assertExit(response, PlanningTerminalState.GUARDED, 2, generator, 2, "G1_NO_PROGRESS");
        assertThat(response.rounds()).allMatch(round -> round.hardFailureFingerprint().startsWith("C6:"));
    }

    @Test @Tag("guards")
    void g2StopsBeforeNextGeneration() {
        Chapter6ScriptedPlanGenerator generator = generator(Chapter6ScriptedPlanGenerator.Scene.TOKEN);
        PlanResponse response = engine(generator,
                new LoopGuardProperties(7_000, Duration.ofSeconds(60), 1.2, 2), () -> false)
                .plan(request(5, 20_000, 700, List.of("灵隐寺")));
        assertExit(response, PlanningTerminalState.GUARDED, response.roundsUsed(), generator,
                response.roundsUsed(), "G2_TOKEN_BUDGET");
        assertThat(response.rounds()).allMatch(round -> round.estimatedTokens() > 2_500);
        assertLedger(response, response.roundsUsed());
    }

    @Test @Tag("guards")
    void g3StopsBeforeThirdGeneration() {
        Chapter6ScriptedPlanGenerator generator = generator(Chapter6ScriptedPlanGenerator.Scene.TIME);
        PlanResponse response = engine(generator,
                new LoopGuardProperties(99_999, Duration.ofMillis(10_000), 1.2, 2), () -> false)
                .plan(request(5, 3000, 700, List.of("灵隐寺")));
        assertExit(response, PlanningTerminalState.GUARDED, 2, generator, 2, "G3_TOTAL_TIME");
        assertThat(response.rounds()).extracting(PlanningRoundSnapshot::elapsedMs).containsExactly(4_000L, 4_000L);
    }

    @Test @Tag("guards")
    void g4StopsOscillationAtRoundLimit() {
        Chapter6ScriptedPlanGenerator generator = generator(Chapter6ScriptedPlanGenerator.Scene.OSCILLATE);
        PlanResponse response = engine(generator, limits(), () -> false)
                .plan(request(4, 3000, 700, List.of("灵隐寺")));
        assertExit(response, PlanningTerminalState.GUARDED, 4, generator, 4, "G4_MAX_ROUNDS");
        assertThat(response.rounds()).extracting(PlanningRoundSnapshot::hardFailureFingerprint)
                .allSatisfy(value -> assertThat(value).isNotBlank());
    }

    @Test @Tag("retry")
    void two429ThenSuccessCountsAsOneBusinessRound() {
        Chapter6ScriptedPlanGenerator generator = generator(Chapter6ScriptedPlanGenerator.Scene.RETRY_SUCCESS);
        PlanResponse response = engine(generator, limits(), () -> false)
                .plan(request(3, 3000, 700, List.of("灵隐寺")));
        assertExit(response, PlanningTerminalState.SUCCESS, 1, generator, 3, null);
        assertLedger(response, 1);
    }

    @Test @Tag("retry")
    void threeFailuresRemain502AndNeverBecomeGuarded() {
        Chapter6ScriptedPlanGenerator generator = generator(Chapter6ScriptedPlanGenerator.Scene.RETRY_FAIL);
        ModelCallException failure = org.assertj.core.api.Assertions.catchThrowableOfType(
                () -> engine(generator, limits(), () -> false)
                        .plan(request(3, 3000, 700, List.of("灵隐寺"))), ModelCallException.class);
        assertThat(generator.calls()).isEqualTo(3);
        assertThat(new ApiExceptionHandler().handleModelCall(failure).getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        System.out.println("[技术失败] terminalState=无响应 | HTTP=502 | rounds=0 | calls=3 | GUARDED=false");
    }

    @Test @Tag("cancel")
    void cancellationAfterFirstRoundStopsBeforeSecondCallAndKeepsBest() {
        Chapter6ScriptedPlanGenerator generator = generator(Chapter6ScriptedPlanGenerator.Scene.C6_STUCK);
        PlanResponse response = engine(generator, limits(), () -> generator.calls() >= 1)
                .plan(request(5, 3000, 700, List.of("灵隐寺")));
        assertExit(response, PlanningTerminalState.CANCELLED, 1, generator, 1, "CANCELLED");
        assertThat(response.plan()).isEqualTo(response.rounds().getFirst().plan());
        assertLedger(response, 1);
    }

    private void assertPreflight(PlanRequest request, PlanningTerminalState state, String code) {
        Chapter6ScriptedPlanGenerator generator = generator(Chapter6ScriptedPlanGenerator.Scene.PASS);
        PlanResponse response = engine(generator, limits(), () -> false).plan(request);
        assertExit(response, state, 0, generator, 0, code);
        assertThat(response.rounds()).isEmpty();
    }

    private void assertExit(PlanResponse response, PlanningTerminalState state, int rounds,
            Chapter6ScriptedPlanGenerator generator, int calls, String guardCode) {
        assertThat(response.terminalState()).as(response.toString()).isEqualTo(state);
        assertThat(response.roundsUsed()).isEqualTo(rounds);
        assertThat(generator.calls()).isEqualTo(calls);
        if (guardCode == null) assertThat(response.stopReport()).isNull();
        else assertThat(response.stopReport().guardCode()).isEqualTo(guardCode);
        String ledger = response.rounds().stream()
                .map(round -> "R" + round.round() + "(" + round.estimatedTokens() + "tok/"
                        + round.elapsedMs() + "ms/" + compact(round.hardFailureFingerprint()) + ")")
                .reduce((left, right) -> left + ", " + right).orElse("空");
        System.out.println("[" + state + "] rounds=" + rounds + " | calls=" + calls
                + " | guard=" + (guardCode == null ? "-" : guardCode) + " | ledger=" + ledger);
    }

    private String compact(String fingerprint) {
        if (fingerprint == null || fingerprint.isBlank()) return "PASS";
        return fingerprint.length() <= 48 ? fingerprint : fingerprint.substring(0, 48) + "...";
    }

    private void assertLedger(PlanResponse response, int entries) {
        assertThat(response.rounds()).hasSize(entries).allSatisfy(round -> {
            assertThat(round.estimatedTokens()).isPositive();
            assertThat(round.elapsedMs()).isGreaterThanOrEqualTo(0);
            assertThat(round.hardFailureFingerprint()).isNotNull();
        });
    }

    private Chapter6ScriptedPlanGenerator generator(Chapter6ScriptedPlanGenerator.Scene scene) {
        return new Chapter6ScriptedPlanGenerator(scene, CLOCK);
    }

    private TravelPlanningEngine engine(Chapter6ScriptedPlanGenerator generator, LoopGuardProperties properties,
            PlanningCancellationSignal cancellation) {
        TravelDataService data = facts();
        return new TravelPlanningEngine(generator, new BasicContractReview(), TestTripPlans.constraintReviewer(),
                new InMemoryPlanningEventSink(), new TravelPlanPreflight(data), new LoopGuardrail(properties),
                new TechnicalRetryExecutor(millis -> {}), cancellation);
    }

    private LoopGuardProperties limits() {
        return new LoopGuardProperties(99_999, Duration.ofSeconds(60), 1.2, 2);
    }

    private TravelDataService facts() {
        TravelDataService data = new TravelDataService(new ObjectMapper().findAndRegisterModules(),
                new DefaultResourceLoader());
        data.loadFacts();
        return data;
    }

    private PlanRequest request(int rounds, int budget, int hotelLimit, List<String> mustVisit) {
        return requestTo("杭州", budget, hotelLimit, mustVisit, rounds);
    }

    private PlanRequest requestTo(String destination, int budget, int hotelLimit, List<String> mustVisit) {
        return requestTo(destination, budget, hotelLimit, mustVisit, 3);
    }

    private PlanRequest requestTo(String destination, int budget, int hotelLimit, List<String> mustVisit, int rounds) {
        return new PlanRequest("上海", destination, LocalDate.of(2026, 10, 1), 3,
                budget, hotelLimit, "课堂回放", mustVisit, rounds);
    }
}
