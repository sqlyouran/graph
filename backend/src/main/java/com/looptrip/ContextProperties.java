package com.looptrip;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 单次 prompt 的文本预算（looptrip.context.*）。
 * 与第六章的 looptrip.max-tokens（整次规划的累计护栏）是两把尺子：一个管总账，一个管单页。
 */
@ConfigurationProperties(prefix = "looptrip.context")
public record ContextProperties(int maxTokens, double safetyMargin) {
}
