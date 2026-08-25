package com.looptrip;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 课堂演示专用，不属于正式运行路径。 */
class ChapterGuardrailsDemoTest {
    private static final GuardLimits LIMITS = new GuardLimits(20_000, 30_000, 4);
    private static final GuardrailEvaluator GUARDRAILS = new GuardrailEvaluator();

    @Test
    void classroomDemo() {
        title("护栏机制");
        System.out.println("每轮记账：Token、耗时、未解决 HARD 问题");
        System.out.println("每轮开始前：护栏只读账本快照");
        System.out.println("优先级：G1 无进展 > G2 Token > G3 总时限 > G4 轮次上限");
        firstRoundDemo();
        estimateDemo();
        changingEvidenceDemo();
        scenarioA();
        scenarioB();
        timeoutAndRoundDemo();
        priorityDemo();
    }

    private void firstRoundDemo() {
        title("首轮直接放行");
        GuardDecision decision = GUARDRAILS.beforeNextRound(new Ledger().snapshot(), LIMITS);
        System.out.println("账本为空，允许启动第 1 轮=" + decision.allowed());
        assertThat(decision.allowed()).isTrue();
    }

    private void estimateDemo() {
        title("预估公式");
        Ledger ledger = new Ledger();
        ledger.record(1, 3_200, 4_000, List.of(hard("C6", "必去景点“灵隐寺”未出现")));
        Projection projection = GUARDRAILS.project(ledger.snapshot());
        System.out.println("记完第 1 轮：已用 Token=3200，已耗时=4000ms");
        System.out.println("预估下一轮：Token=ceil(3200×1.2)=" + projection.nextTokens()
                + "，耗时=ceil(4000×1.2)=" + projection.nextElapsedMs() + "ms");
        assertThat(projection).isEqualTo(new Projection(3_840, 4_800));
    }

    private void changingEvidenceDemo() {
        title("G1：证据数字变化算有进展");
        Ledger ledger = new Ledger();
        ledger.record(1, 2_000, 2_000, List.of(hard("C1", "超预算 1205 元")));
        ledger.record(2, 2_000, 2_000, List.of(hard("C1", "超预算 320 元")));
        fingerprints(ledger.snapshot());
        GuardDecision decision = GUARDRAILS.beforeNextRound(ledger.snapshot(), LIMITS);
        System.out.println("C1 证据 1205→320，指纹变化，允许继续=" + decision.allowed());
        assertThat(decision.allowed()).isTrue();
    }

    private void scenarioA() {
        title("场景 A：C6 原地踏步");
        Ledger ledger = new Ledger();
        HardFailure same = hard("C6", "必去景点“灵隐寺”未出现在 ATTRACTION 活动中");
        ledger.record(1, 3_200, 4_000, List.of(same));
        printRound(ledger.snapshot().latest());
        ledger.record(2, 3_200, 4_000, List.of(same));
        printRound(ledger.snapshot().latest());
        fingerprints(ledger.snapshot());
        GuardDecision decision = GUARDRAILS.beforeNextRound(ledger.snapshot(), LIMITS);
        printDecision("第 3 轮前", decision);
        assertThat(decision.code()).isEqualTo("G1_NO_PROGRESS");
    }

    private void scenarioB() {
        title("场景 B：持续改进，但 Token 不够开第 4 轮");
        Ledger ledger = new Ledger();
        ledger.record(1, 4_500, 2_000, List.of(hard("C1", "超预算 1205 元")));
        ledger.record(2, 5_200, 2_000, List.of(hard("C1", "超预算 760 元")));
        ledger.record(3, 6_100, 2_000, List.of(hard("C1", "超预算 320 元")));
        ledger.snapshot().entries().forEach(this::printRound);
        LedgerSnapshot snapshot = ledger.snapshot();
        Projection projection = GUARDRAILS.project(snapshot);
        System.out.println("Token 合计=4500+5200+6100=" + snapshot.totalTokens());
        System.out.println("第 4 轮预估=ceil(6100×1.2)=" + projection.nextTokens());
        System.out.println("预算判断=15800+7320=23120 > 20000");
        GuardDecision decision = GUARDRAILS.beforeNextRound(snapshot, LIMITS);
        printDecision("第 4 轮前", decision);
        assertThat(snapshot.totalTokens()).isEqualTo(15_800);
        assertThat(projection.nextTokens()).isEqualTo(7_320);
        assertThat(decision.code()).isEqualTo("G2_TOKEN_BUDGET");
    }

    private void timeoutAndRoundDemo() {
        title("G3/G4 各自能拦什么");
        Ledger timeout = new Ledger();
        timeout.record(1, 100, 12_000, List.of(hard("C1", "超预算 500 元")));
        timeout.record(2, 100, 14_000, List.of(hard("C1", "超预算 400 元")));
        GuardDecision timeDecision = GUARDRAILS.beforeNextRound(
                timeout.snapshot(), new GuardLimits(99_999, 40_000, 9));
        printDecision("总时限演示", timeDecision);
        assertThat(timeDecision.code()).isEqualTo("G3_TOTAL_TIME");

        Ledger rounds = new Ledger();
        rounds.record(1, 100, 100, List.of(hard("C1", "超预算 500 元")));
        rounds.record(2, 100, 100, List.of(hard("C1", "超预算 400 元")));
        GuardDecision roundDecision = GUARDRAILS.beforeNextRound(
                rounds.snapshot(), new GuardLimits(99_999, 99_999, 2));
        printDecision("轮次兜底演示", roundDecision);
        assertThat(roundDecision.code()).isEqualTo("G4_MAX_ROUNDS");
    }

    private void priorityDemo() {
        title("多道同时触发");
        Ledger ledger = new Ledger();
        HardFailure same = hard("C6", "必去景点“灵隐寺”缺失");
        ledger.record(1, 11_000, 20_000, List.of(same));
        ledger.record(2, 11_000, 20_000, List.of(same));
        GuardDecision decision = GUARDRAILS.beforeNextRound(
                ledger.snapshot(), new GuardLimits(20_000, 30_000, 2));
        System.out.println("四道护栏同时满足停止条件");
        printDecision("优先级裁决", decision);
        assertThat(decision.code()).isEqualTo("G1_NO_PROGRESS");
    }

    private HardFailure hard(String code, String evidence) {
        return new HardFailure(code, List.of(evidence));
    }

    private void printRound(LedgerEntry entry) {
        System.out.println("第 " + entry.round() + " 轮记账：Token=" + entry.tokens()
                + "，耗时=" + entry.elapsedMs() + "ms，HARD 指纹=" + entry.hardFingerprint());
    }

    private void fingerprints(LedgerSnapshot snapshot) {
        List<LedgerEntry> entries = snapshot.entries();
        System.out.println("倒数第 2 轮指纹：" + entries.get(entries.size() - 2).hardFingerprint());
        System.out.println("倒数第 1 轮指纹：" + entries.get(entries.size() - 1).hardFingerprint());
    }

    private void printDecision(String point, GuardDecision decision) {
        System.out.println(point + "：允许继续=" + decision.allowed());
        if (!decision.allowed()) {
            System.out.println("护栏代号=" + decision.code());
            System.out.println("原因=" + decision.reason());
            System.out.println("证据=" + decision.evidence());
            System.out.println("给用户的建议=" + decision.suggestion());
        }
    }

    private void title(String title) {
        System.out.println();
        System.out.println("========== " + title + " ==========");
    }

    record HardFailure(String ruleCode, List<String> evidence) {
        HardFailure {
            evidence = evidence.stream().sorted().toList();
        }
        String fingerprintPart() {
            return ruleCode + ":" + String.join("|", evidence);
        }
    }

    record LedgerEntry(int round, long tokens, long elapsedMs, List<HardFailure> unresolvedHard) {
        LedgerEntry {
            unresolvedHard = List.copyOf(unresolvedHard);
        }
        String hardFingerprint() {
            return unresolvedHard.stream()
                    .sorted(Comparator.comparing(HardFailure::ruleCode)
                            .thenComparing(HardFailure::fingerprintPart))
                    .map(HardFailure::fingerprintPart)
                    .reduce((left, right) -> left + "||" + right)
                    .orElse("PASS");
        }
    }

    record LedgerSnapshot(List<LedgerEntry> entries) {
        LedgerSnapshot {
            entries = List.copyOf(entries);
        }
        long totalTokens() {
            return entries.stream().mapToLong(LedgerEntry::tokens).sum();
        }
        long totalElapsedMs() {
            return entries.stream().mapToLong(LedgerEntry::elapsedMs).sum();
        }
        LedgerEntry latest() {
            return entries.get(entries.size() - 1);
        }
    }

    static final class Ledger {
        private final List<LedgerEntry> entries = new ArrayList<>();
        void record(int round, long tokens, long elapsedMs, List<HardFailure> hard) {
            entries.add(new LedgerEntry(round, tokens, elapsedMs, hard));
        }
        LedgerSnapshot snapshot() {
            return new LedgerSnapshot(entries);
        }
    }

    record GuardLimits(long maxTokens, long maxElapsedMs, int maxRounds) {}
    record Projection(long nextTokens, long nextElapsedMs) {}

    record GuardDecision(boolean allowed, String code, String reason, String evidence, String suggestion) {
        static GuardDecision allow() {
            return new GuardDecision(true, "ALLOW", "可以继续", "所有护栏均有余量", "启动下一轮");
        }
        static GuardDecision stop(String code, String reason, String evidence, String suggestion) {
            return new GuardDecision(false, code, reason, evidence, suggestion);
        }
    }

    static final class GuardrailEvaluator {
        GuardDecision beforeNextRound(LedgerSnapshot snapshot, GuardLimits limits) {
            if (snapshot.entries().isEmpty()) return GuardDecision.allow();

            Optional<GuardDecision> noProgress = noProgress(snapshot);
            if (noProgress.isPresent()) return noProgress.get();

            Projection projection = project(snapshot);
            long projectedTokens = snapshot.totalTokens() + projection.nextTokens();
            if (projectedTokens > limits.maxTokens()) {
                return GuardDecision.stop("G2_TOKEN_BUDGET", "预计启动下一轮会超过 Token 总预算",
                        snapshot.totalTokens() + "+" + projection.nextTokens() + "=" + projectedTokens
                                + " > " + limits.maxTokens(),
                        "返回当前最好版本；提高 Token 预算或缩小任务范围后重试");
            }

            long projectedElapsed = snapshot.totalElapsedMs() + projection.nextElapsedMs();
            if (projectedElapsed > limits.maxElapsedMs()) {
                return GuardDecision.stop("G3_TOTAL_TIME", "预计启动下一轮会超过总时限",
                        snapshot.totalElapsedMs() + "+" + projection.nextElapsedMs() + "="
                                + projectedElapsed + "ms > " + limits.maxElapsedMs() + "ms",
                        "返回当前最好版本；提高总时限或减少规划复杂度后重试");
            }

            if (snapshot.entries().size() >= limits.maxRounds()) {
                return GuardDecision.stop("G4_MAX_ROUNDS", "已达到最大轮次",
                        "已完成 " + snapshot.entries().size() + " 轮，上限 " + limits.maxRounds() + " 轮",
                        "返回当前最好版本；如需继续，请提高最大轮次");
            }
            return GuardDecision.allow();
        }

        Projection project(LedgerSnapshot snapshot) {
            LedgerEntry latest = snapshot.latest();
            return new Projection(ceilTimesOnePointTwo(latest.tokens()),
                    ceilTimesOnePointTwo(latest.elapsedMs()));
        }

        private Optional<GuardDecision> noProgress(LedgerSnapshot snapshot) {
            List<LedgerEntry> entries = snapshot.entries();
            if (entries.size() < 2) return Optional.empty();
            String previous = entries.get(entries.size() - 2).hardFingerprint();
            String latest = entries.get(entries.size() - 1).hardFingerprint();
            if (!latest.equals("PASS") && latest.equals(previous)) {
                return Optional.of(GuardDecision.stop("G1_NO_PROGRESS",
                        "连续两轮未解决 HARD 失败指纹完全相同", latest,
                        "返回当前最好版本；调整需求或事实数据后再试"));
            }
            return Optional.empty();
        }

        private long ceilTimesOnePointTwo(long value) {
            return (value * 12 + 9) / 10;
        }
    }
}
