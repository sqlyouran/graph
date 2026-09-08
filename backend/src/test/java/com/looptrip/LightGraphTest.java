package com.looptrip;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 通用执行器单测：玩具图验证分支、回边、护栏停机、先合并再选边与两道报错。 */
class LightGraphTest {

    private record ToyDelta(Integer value, String flag) implements GraphDelta {
    }

    private static final class ToyState implements GraphStateContract<ToyState> {
        private final int value;

        ToyState(int value) {
            this.value = value;
        }

        int value() {
            return value;
        }

        @Override
        public ToyState apply(GraphDelta delta) {
            if (delta instanceof ToyDelta d && d.value() != null) {
                return new ToyState(d.value());
            }
            return this;
        }

        @Override
        public boolean satisfies(String fromNode, String condition) {
            switch (fromNode) {
                case "start":
                case "finish":
                    if (condition.equals("always")) return true;
                    break;
                case "count":
                    if (condition.equals("under")) return value < 3;
                    if (condition.equals("done")) return value >= 3;
                    break;
                case "branch":
                    if (condition.equals("even")) return value % 2 == 0;
                    if (condition.equals("odd")) return value % 2 == 1;
                    break;
                default:
                    break;
            }
            throw new IllegalStateException("玩具状态不认识 " + fromNode + " 的条件 " + condition);
        }
    }

    @Test
    void conditionalBranchRoutesByMergedState() {
        GraphDefinition<ToyState> definition = GraphDefinition.<ToyState>builder()
                .start("start")
                .node("branch", state -> NodeResult.of(new ToyDelta(state.value(), null)))
                .terminals("EVEN", "ODD")
                .edge("start", "branch", "always")
                .edge("branch", "EVEN", "even")
                .edge("branch", "ODD", "odd")
                .build();

        LightGraph<ToyState> graph = new LightGraph<>(10);
        assertThat(graph.run(definition, new ToyState(4), List.of()).terminalLabel()).isEqualTo("EVEN");
        assertThat(graph.run(definition, new ToyState(3), List.of()).terminalLabel()).isEqualTo("ODD");
    }

    @Test
    void backEdgeLoopsUntilConditionMet() {
        GraphDefinition<ToyState> definition = GraphDefinition.<ToyState>builder()
                .start("start")
                .node("count", state -> NodeResult.of(new ToyDelta(state.value() + 1, null)))
                .terminals("END")
                .edge("start", "count", "always")
                .edge("count", "count", "under")
                .edge("count", "END", "done")
                .build();

        LightGraph.RunResult<ToyState> run =
                new LightGraph<ToyState>(10).run(definition, new ToyState(0), List.of());

        assertThat(run.terminalLabel()).isEqualTo("END");
        assertThat(run.state().value()).isEqualTo(3);
        assertThat(run.steps()).isEqualTo(3);
        assertThat(run.stoppedByGuard()).isFalse();
    }

    @Test
    void preNodeGuardStopsBeforeNodeExecution() {
        GraphDefinition<ToyState> definition = GraphDefinition.<ToyState>builder()
                .start("start")
                .node("count", state -> NodeResult.of(new ToyDelta(state.value() + 1, null)))
                .terminals("END")
                .edge("start", "count", "always")
                .edge("count", "count", "under")
                .edge("count", "END", "done")
                .build();
        PreNodeGuard<ToyState> guard = (node, state) ->
                node.equals("count") && state.value() >= 2 ? java.util.Optional.of("STOPPED") : java.util.Optional.empty();

        LightGraph.RunResult<ToyState> run =
                new LightGraph<ToyState>(10).run(definition, new ToyState(0), List.of(guard));

        assertThat(run.terminalLabel()).isEqualTo("STOPPED");
        assertThat(run.stoppedByGuard()).isTrue();
        assertThat(run.state().value()).isEqualTo(2);
    }

    @Test
    void edgeSelectionSeesMergedStateNotStaleState() {
        GraphDefinition<ToyState> definition = GraphDefinition.<ToyState>builder()
                .start("start")
                .node("count", state -> NodeResult.of(new ToyDelta(5, null)))
                .terminals("DONE", "LOOP")
                .edge("start", "count", "always")
                .edge("count", "DONE", "done")
                .edge("count", "LOOP", "under")
                .build();

        LightGraph.RunResult<ToyState> run =
                new LightGraph<ToyState>(10).run(definition, new ToyState(0), List.of());

        assertThat(run.terminalLabel()).isEqualTo("DONE");
    }

    @Test
    void exceedingMaxStepsThrows() {
        GraphDefinition<ToyState> definition = GraphDefinition.<ToyState>builder()
                .start("start")
                .node("count", state -> NodeResult.of(new ToyDelta(state.value() + 1, null)))
                .terminals("END")
                .edge("start", "count", "always")
                .edge("count", "count", "under")
                .edge("count", "END", "done")
                .build();

        assertThatThrownBy(() -> new LightGraph<ToyState>(2).run(definition, new ToyState(0), List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("maxSteps=2");
    }

    @Test
    void nonTerminalWithoutWalkableEdgeThrows() {
        GraphDefinition<ToyState> definition = GraphDefinition.<ToyState>builder()
                .start("start")
                .node("lonely", state -> NodeResult.of(new ToyDelta(state.value(), null)))
                .terminals("END")
                .edge("start", "lonely", "always")
                .build();

        assertThatThrownBy(() -> new LightGraph<ToyState>(10).run(definition, new ToyState(0), List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("没有可走的边");
    }
}
