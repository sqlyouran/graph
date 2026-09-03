package com.looptrip;

/**
 * 离线评委：不联网、同输入同输出，三票必然一致——课堂回放要的是可重复，不是智能。
 * 接真实模型时新写一个实现类替换它，评测主流程不动。
 */
public class OfflinePersonalizationJudge implements PersonalizationJudge {

    @Override
    public boolean reflects(String preferenceText, String planMarkdown) {
        if (preferenceText.contains("民宿")) {
            return planMarkdown.contains("民宿");
        }
        if (preferenceText.contains("爬山") || preferenceText.contains("登山")) {
            return !planMarkdown.contains("爬山") && !planMarkdown.contains("登山");
        }
        return planMarkdown.contains(preferenceText);
    }
}
