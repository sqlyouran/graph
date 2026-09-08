package com.looptrip;

/** 图执行器对状态的全部要求：合并增量、按条件选边。领域逻辑留在状态实现里。 */
public interface GraphStateContract<S> {

    S apply(GraphDelta delta);

    boolean satisfies(String fromNode, String condition);
}
