package com.looptrip;

import java.util.List;

/**
 * 组装结果：最终发给模型的两段文本 + 取舍账本。
 * droppedSections 回答"这一轮为什么没带某段内容"——看不见的决策等于不存在的决策。
 */
public record PromptContext(
        String systemPrompt,
        String userPrompt,
        List<String> includedSections,
        List<DroppedSection> droppedSections,
        int estimatedTokens,
        int usableBudget) {

    public record DroppedSection(String section, String reason) {
        @Override
        public String toString() {
            return section + ": " + reason;
        }
    }

    public PromptContext {
        includedSections = List.copyOf(includedSections);
        droppedSections = List.copyOf(droppedSections);
    }
}
