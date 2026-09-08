package com.looptrip;

import java.util.Optional;

/** 节点执行前的通用护栏钩子：返回终态标签即停下，不返回则继续。 */
public interface PreNodeGuard<S> {

    Optional<String> check(String node, S state);
}
