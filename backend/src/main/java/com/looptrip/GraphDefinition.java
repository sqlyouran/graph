package com.looptrip;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/** 路线图：节点、边、终态在构建后冻结，运行途中不能改线。 */
public final class GraphDefinition<S extends GraphStateContract<S>> {

    private final String start;
    private final Map<String, NodeSpec<S>> nodes;
    private final List<String> terminals;
    private final List<EdgeSpec> edges;
    private final List<JoinSpec> joins;

    private GraphDefinition(String start, Map<String, NodeSpec<S>> nodes,
            List<String> terminals, List<EdgeSpec> edges, List<JoinSpec> joins) {
        this.start = start;
        this.nodes = nodes;
        this.terminals = terminals;
        this.edges = edges;
        this.joins = joins;
    }

    public String start() {
        return start;
    }

    public boolean isTerminal(String label) {
        return terminals.contains(label);
    }

    public boolean isNode(String label) {
        return nodes.containsKey(label);
    }

    public NodeSpec<S> node(String label) {
        NodeSpec<S> spec = nodes.get(label);
        if (spec == null) {
            throw new IllegalStateException("图节点不存在：" + label);
        }
        return spec;
    }

    /** 根据合并后的新状态选下一条边；非终态却无边可走必须明确报错。 */
    public String next(String from, S state) {
        for (EdgeSpec edge : edges) {
            if (edge.from().equals(from) && state.satisfies(from, edge.condition())) {
                return edge.to();
            }
        }
        throw new IllegalStateException("节点 " + from + " 非终态且当前状态没有可走的边");
    }

    /** 同一状态下一站可能有多个目标：全部满足条件的边就是并行前沿。 */
    public List<String> nextTargets(String from, S state) {
        List<String> targets = new ArrayList<>();
        for (EdgeSpec edge : edges) {
            if (edge.from().equals(from) && state.satisfies(from, edge.condition())) {
                targets.add(edge.to());
            }
        }
        if (targets.isEmpty()) {
            throw new IllegalStateException("节点 " + from + " 非终态且当前状态没有可走的边");
        }
        return List.copyOf(targets);
    }

    /** 目标集合正好等于某个 join 组的来源集合时，返回那张交卷名单。 */
    public Optional<JoinSpec> joinForSources(List<String> targets) {
        Set<String> targetSet = Set.copyOf(targets);
        return joins.stream()
                .filter(join -> join.sourceNodes().equals(targetSet))
                .findFirst();
    }

    public static <S extends GraphStateContract<S>> Builder<S> builder() {
        return new Builder<>();
    }

    public static final class Builder<S extends GraphStateContract<S>> {
        private String start;
        private final Map<String, NodeSpec<S>> nodes = new LinkedHashMap<>();
        private final List<String> terminals = new ArrayList<>();
        private final List<EdgeSpec> edges = new ArrayList<>();
        private final List<JoinSpec> joins = new ArrayList<>();

        public Builder<S> start(String label) {
            this.start = label;
            return this;
        }

        public Builder<S> node(String label, Function<S, NodeResult> action) {
            nodes.put(label, new NodeSpec<>(label, action));
            return this;
        }

        public Builder<S> terminals(String... labels) {
            terminals.addAll(List.of(labels));
            return this;
        }

        public Builder<S> edge(String from, String to, String condition) {
            edges.add(new EdgeSpec(from, to, condition));
            return this;
        }

        /** 声明交卷名单：sources 全部交回后，target 只启动一次。 */
        public Builder<S> join(String targetNode, String... sourceNodes) {
            joins.add(new JoinSpec(targetNode, Set.of(sourceNodes)));
            return this;
        }

        public GraphDefinition<S> build() {
            return new GraphDefinition<>(start, Map.copyOf(nodes), List.copyOf(terminals),
                    List.copyOf(edges), List.copyOf(joins));
        }
    }
}
