package com.looptrip;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

@Component
public class LoopGuardrail {
    private final LoopGuardProperties properties;

    public LoopGuardrail(LoopGuardProperties properties) { this.properties = properties; }

    public Optional<PlanningStopReport> beforeRound(PlanningLedgerSnapshot snapshot, int maxRounds) {
        if (snapshot.entries().isEmpty()) return Optional.empty();
        List<PlanningLedgerEntry> entries = snapshot.entries();
        int limit = properties.sameFingerprintLimit();
        if (entries.size() >= limit) {
            String fingerprint = entries.getLast().hardFailureFingerprint();
            boolean same = !fingerprint.isBlank() && entries.subList(entries.size() - limit, entries.size())
                    .stream().allMatch(item -> item.hardFailureFingerprint().equals(fingerprint));
            if (same) return Optional.of(new PlanningStopReport("G1_NO_PROGRESS", "连续 " + limit + " 轮 HARD 失败没有进展",
                    fingerprint, "调整需求或事实数据后重试"));
        }
        PlanningLedgerEntry latest = snapshot.latest();
        long nextTokens = ceilMultiply(latest.estimatedTokens());
        if (snapshot.totalEstimatedTokens() + nextTokens > properties.maxTokens())
            return Optional.of(new PlanningStopReport("G2_TOKEN_BUDGET", "预计下一轮会超过 Token 预算",
                    snapshot.totalEstimatedTokens() + "+" + nextTokens + ">" + properties.maxTokens(), "缩小任务范围或提高 Token 预算"));
        long nextMs = ceilMultiply(latest.elapsedMs());
        if (snapshot.totalElapsedMs() + nextMs > properties.maxDuration().toMillis())
            return Optional.of(new PlanningStopReport("G3_TOTAL_TIME", "预计下一轮会超过总时限",
                    snapshot.totalElapsedMs() + "+" + nextMs + "ms>" + properties.maxDuration().toMillis() + "ms", "缩小任务范围或提高总时限"));
        if (entries.size() >= maxRounds)
            return Optional.of(new PlanningStopReport("G4_MAX_ROUNDS", "已达到最大轮次",
                    "已完成 " + entries.size() + " 轮，上限 " + maxRounds + " 轮", "提高最大轮次后重试"));
        return Optional.empty();
    }

    private long ceilMultiply(long value) { return (long) Math.ceil(value * properties.multiplier()); }
}
