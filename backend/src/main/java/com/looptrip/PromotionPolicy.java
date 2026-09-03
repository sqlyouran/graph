package com.looptrip;

import org.springframework.stereotype.Component;

/**
 * 晋升策略：四行判断。频次低于门槛等一等；方向来回变化拒掉；
 * 带场景条件的连着条件一起确认；其余无条件确认。确认前一律不写画像。
 */
@Component
public class PromotionPolicy {

    public PromotionDecision evaluate(PreferenceCandidate candidate, int minSessions) {
        if (candidate.sessionCount() < minSessions) return PromotionDecision.WAIT;
        if (!candidate.directionConsistent()) return PromotionDecision.REJECT;
        if (candidate.condition() != null && !candidate.condition().isBlank()) {
            return PromotionDecision.CONFIRM_WITH_CONDITION;
        }
        return PromotionDecision.CONFIRM_UNCONDITIONAL;
    }
}
