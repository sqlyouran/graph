package com.looptrip;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 按路线图开车的司机：执行节点 → 合并变化 → 根据新状态选边。领域无关。
 * 选边后若出现 join 组（并行前沿），四路同时出发，到点收口，只合并按时交回的结果。
 */
public final class LightGraph<S extends GraphStateContract<S>> {

    public record RunResult<S>(S state, String terminalLabel, int steps, boolean stoppedByGuard) {
    }

    /** 并行组回执：合并后的状态、按时交卷的来源、迟到或缺席的来源、护栏喊停标签。 */
    public record ParallelRun<S>(S state, List<String> completed, List<String> missed, String stoppedLabel) {
    }

    private static final long WAIT_POLL_MS = 20;

    private final int maxSteps;
    private final ExecutorService parallelPool;
    private final long nodeTimeoutMs;
    private final long groupTimeoutMs;

    public LightGraph(int maxSteps) {
        this(maxSteps, null, 5_000, 10_000);
    }

    public LightGraph(int maxSteps, ExecutorService parallelPool, long nodeTimeoutMs, long groupTimeoutMs) {
        this.maxSteps = maxSteps;
        this.parallelPool = parallelPool;
        this.nodeTimeoutMs = nodeTimeoutMs;
        this.groupTimeoutMs = groupTimeoutMs;
    }

    public RunResult<S> run(GraphDefinition<S> definition, S initial, List<PreNodeGuard<S>> guards) {
        S state = initial;
        String current = definition.start();
        int steps = 0;
        while (!definition.isTerminal(current)) {
            if (definition.isNode(current)) {
                for (PreNodeGuard<S> guard : guards) {
                    Optional<String> stop = guard.check(current, state);
                    if (stop.isPresent()) {
                        return new RunResult<>(state, stop.get(), steps, true);
                    }
                }
                if (++steps > maxSteps) {
                    throw new IllegalStateException("图执行超过 maxSteps=" + maxSteps + "，停在节点 " + current);
                }
                NodeResult result = definition.node(current).action().apply(state);
                state = state.apply(result.delta());
            }
            List<String> targets = definition.nextTargets(current, state);
            Optional<JoinSpec> join = definition.joinForSources(targets);
            if (join.isPresent()) {
                steps += targets.size();
                if (steps > maxSteps) {
                    throw new IllegalStateException("图执行超过 maxSteps=" + maxSteps + "，停在并行组 " + targets);
                }
                ParallelRun<S> parallel = runParallelGroup(definition, targets, state, guards, join.get().targetNode());
                if (parallel.stoppedLabel() != null) {
                    return new RunResult<>(parallel.state(), parallel.stoppedLabel(), steps, true);
                }
                state = parallel.state();
                current = join.get().targetNode();
            } else {
                current = targets.get(0);
            }
        }
        return new RunResult<>(state, current, steps, false);
    }

    /** 同时发起、等到边界、只合并截止线前完成的结果；等待期间不断检查护栏。 */
    private ParallelRun<S> runParallelGroup(GraphDefinition<S> definition, List<String> targets,
            S state, List<PreNodeGuard<S>> guards, String joinTarget) {
        for (String target : targets) {
            for (PreNodeGuard<S> guard : guards) {
                Optional<String> stop = guard.check(target, state);
                if (stop.isPresent()) {
                    return new ParallelRun<>(state, List.of(), List.of(), stop.get());
                }
            }
        }
        long startNanos = System.nanoTime();
        long groupDeadline = startNanos + TimeUnit.MILLISECONDS.toNanos(groupTimeoutMs);
        long nodeDeadline = startNanos + TimeUnit.MILLISECONDS.toNanos(nodeTimeoutMs);
        Map<String, Future<NodeResult>> futures = new LinkedHashMap<>();
        Map<String, NodeResult> results = new LinkedHashMap<>();
        List<String> missed = new ArrayList<>();
        for (String target : targets) {
            if (parallelPool == null) {
                // 没有线程池时按顺序出发：语义不变，只是不快。
                try {
                    results.put(target, definition.node(target).action().apply(state));
                } catch (RuntimeException exception) {
                    missed.add(target);
                }
            } else {
                futures.put(target, parallelPool.submit(() -> definition.node(target).action().apply(state)));
            }
        }
        List<String> pending = new ArrayList<>(futures.keySet());
        while (!pending.isEmpty() && System.nanoTime() < groupDeadline) {
            for (PreNodeGuard<S> guard : guards) {
                Optional<String> stop = guard.check(joinTarget, state);
                if (stop.isPresent()) {
                    cancelAll(futures.values());
                    return new ParallelRun<>(state, List.of(), List.of(), stop.get());
                }
            }
            long now = System.nanoTime();
            for (String target : new ArrayList<>(pending)) {
                Future<NodeResult> future = futures.get(target);
                if (now > nodeDeadline && !future.isDone()) {
                    future.cancel(true);
                    missed.add(target);
                    pending.remove(target);
                    continue;
                }
                if (!future.isDone()) {
                    continue;
                }
                pending.remove(target);
                try {
                    results.put(target, future.get());
                } catch (ExecutionException | CancellationException | InterruptedException exception) {
                    if (exception instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    missed.add(target);
                }
            }
            if (!pending.isEmpty()) {
                sleepQuietly(WAIT_POLL_MS);
            }
        }
        // 到点收口：还没交卷的来源一律记缺席，迟到的结果不再采用。
        for (String target : new ArrayList<>(pending)) {
            cancel(futures.get(target));
            missed.add(target);
            pending.remove(target);
        }
        S merged = state;
        for (String target : targets) {
            NodeResult result = results.get(target);
            if (result != null) {
                merged = merged.apply(result.delta());
            }
        }
        List<String> completed = targets.stream().filter(results::containsKey).toList();
        return new ParallelRun<>(merged, completed, List.copyOf(missed), null);
    }

    private void cancelAll(java.util.Collection<Future<NodeResult>> futures) {
        for (Future<NodeResult> future : futures) {
            cancel(future);
        }
    }

    private void cancel(Future<NodeResult> future) {
        if (future != null) {
            future.cancel(true);
        }
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
