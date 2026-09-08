package com.looptrip;

import java.util.function.Function;

/** 图节点：一个名字加一段只读状态、交回增量的动作。 */
public record NodeSpec<S extends GraphStateContract<S>>(String name, Function<S, NodeResult> action) {
}
