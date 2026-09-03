package com.looptrip;

/**
 * 晋升判据的四种结果：频次不够等、方向不一致拒、带条件确认、无条件确认。
 */
public enum PromotionDecision {
    WAIT,
    REJECT,
    CONFIRM_WITH_CONDITION,
    CONFIRM_UNCONDITIONAL
}
