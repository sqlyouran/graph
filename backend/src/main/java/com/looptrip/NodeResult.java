package com.looptrip;

/** 节点执行结果：只携带状态增量，节点从不直接改公共状态。 */
public record NodeResult(GraphDelta delta) {

    public static NodeResult of(GraphDelta delta) {
        return new NodeResult(delta);
    }
}
