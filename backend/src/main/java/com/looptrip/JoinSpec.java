package com.looptrip;

import java.util.Set;

/** 交卷名单：下一步是谁，以及必须等哪些来源交卷。来源齐备才启动目标节点。 */
public record JoinSpec(String targetNode, Set<String> sourceNodes) {

    public JoinSpec {
        sourceNodes = Set.copyOf(sourceNodes);
    }
}
