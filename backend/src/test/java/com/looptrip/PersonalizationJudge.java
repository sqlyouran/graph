package com.looptrip;

/**
 * 个性化评委：回答"方案是否体现了这条偏好"。
 * 抽成接口是为了换实现不换评测：课堂回放用离线可重复实现，接真实模型时替换实现类。
 */
public interface PersonalizationJudge {

    boolean reflects(String preferenceText, String planMarkdown);
}
