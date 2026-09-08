package com.looptrip;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 第 9 章第 3 节课本回放：并行从图里长出来。
 * 上半区用玩具图验证通用执行器的并行语义（同时出发、join 收齐一次、迟到不采用、护栏组前喊停）；
 * 下半区用真实 7 节点图验证四路事实汇合、降级是返回值、核心缺失禁交付、返工不重查。
 */
class Chapter9ParallelFactReplayTest {

    // ===================== 通用并行执行器（玩具图，领域无关） =====================

    private record Noop() implements GraphDelta {
    }

    private record FanDelta(String source) implements GraphDelta {
    }

    private record CollectDelta() implements GraphDelta {
    }

    private static final class FanState implements GraphStateContract<FanState> {
        private final Set<String> sources;
        private final int collectorRuns;

        private FanState(Set<String> sources, int collectorRuns) {
            this.sources = sources;
            this.collectorRuns = collectorRuns;
        }

        static FanState initial() {
            return new FanState(Set.of(), 0);
        }

        Set<String> sources() {
            return sources;
        }

        int collectorRuns() {
            return collectorRuns;
        }

        @Override
        public FanState apply(GraphDelta delta) {
            if (delta instanceof FanDelta fan) {
                Set<String> next = new java.util.LinkedHashSet<>(sources);
                next.add(fan.source());
                return new FanState(Set.copyOf(next), collectorRuns);
            }
            if (delta instanceof CollectDelta) {
                return new FanState(sources, collectorRuns + 1);
            }
            return this;
        }

        @Override
        public boolean satisfies(String fromNode, String condition) {
            if (condition.equals("always")) {
                return true;
            }
            throw new IllegalStateException("玩具状态不认识 " + fromNode + " 的条件 " + condition);
        }
    }

    /** START → fan →（a|b|c|d 并行）→ join 收齐 → collector → END。sourceBehavior 决定每路交回什么。 */
    private GraphDefinition<FanState> fanGraph(Function<String, NodeResult> sourceBehavior) {
        return GraphDefinition.<FanState>builder()
                .start("START")
                .node("fan", state -> NodeResult.of(new Noop()))
                .node("a", state -> sourceBehavior.apply("a"))
                .node("b", state -> sourceBehavior.apply("b"))
                .node("c", state -> sourceBehavior.apply("c"))
                .node("d", state -> sourceBehavior.apply("d"))
                .node("collector", state -> NodeResult.of(new CollectDelta()))
                .terminals("END")
                .edge("START", "fan", "always")
                .edge("fan", "a", "always")
                .edge("fan", "b", "always")
                .edge("fan", "c", "always")
                .edge("fan", "d", "always")
                .edge("a", "collector", "always")
                .edge("b", "collector", "always")
                .edge("c", "collector", "always")
                .edge("d", "collector", "always")
                .edge("collector", "END", "always")
                .join("collector", "a", "b", "c", "d")
                .build();
    }

    @Test
    void joinMergesEverySourceAndRunsTargetOnce() {
        GraphDefinition<FanState> definition = fanGraph(name -> NodeResult.of(new FanDelta(name)));
        ExecutorService pool = pool(4);
        try {
            LightGraph.RunResult<FanState> run =
                    new LightGraph<FanState>(20, pool, 5_000, 10_000).run(definition, FanState.initial(), List.of());

            assertThat(run.terminalLabel()).isEqualTo("END");
            assertThat(run.state().sources()).containsExactlyInAnyOrder("a", "b", "c", "d");
            assertThat(run.state().collectorRuns()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void fourSourcesRunConcurrentlyNotSerially() {
        CountDownLatch latch = new CountDownLatch(4);
        AtomicInteger arrived = new AtomicInteger();
        GraphDefinition<FanState> definition = fanGraph(name -> {
            latch.countDown();
            try {
                // 四路都到齐才会放行；若串行执行，先到的会一直等不到别人而超时。
                if (latch.await(2, TimeUnit.SECONDS)) {
                    arrived.incrementAndGet();
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return NodeResult.of(new FanDelta(name));
        });
        ExecutorService pool = pool(4);
        try {
            LightGraph.RunResult<FanState> run =
                    new LightGraph<FanState>(20, pool, 5_000, 10_000).run(definition, FanState.initial(), List.of());

            assertThat(run.state().sources()).containsExactlyInAnyOrder("a", "b", "c", "d");
            assertThat(arrived).hasValue(4);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void parallelWallClockBeatsSerialSum() {
        GraphDefinition<FanState> definition = fanGraph(name -> {
            sleep(150);
            return NodeResult.of(new FanDelta(name));
        });
        ExecutorService pool = pool(4);
        long parallelMs;
        long serialMs;
        try {
            long start = System.nanoTime();
            new LightGraph<FanState>(20, pool, 5_000, 10_000).run(definition, FanState.initial(), List.of());
            parallelMs = (System.nanoTime() - start) / 1_000_000;
        } finally {
            pool.shutdownNow();
        }
        // 无线程池时按顺序出发：语义不变，只是四项相加。
        long serialStart = System.nanoTime();
        new LightGraph<FanState>(20, null, 5_000, 10_000).run(definition, FanState.initial(), List.of());
        serialMs = (System.nanoTime() - serialStart) / 1_000_000;

        System.out.println("[并行提速] 四个各睡 150ms 的节点：串行≈" + serialMs + "ms，并行≈" + parallelMs + "ms");
        assertThat(parallelMs).isLessThan(serialMs);
        assertThat(parallelMs).isLessThan(400);
    }

    @Test
    void lateSourceIsDroppedAndOnTimeResultsStillMerge() {
        GraphDefinition<FanState> definition = fanGraph(name -> {
            if (name.equals("d")) {
                sleep(400);
            }
            return NodeResult.of(new FanDelta(name));
        });
        ExecutorService pool = pool(4);
        try {
            // 单节点超时 100ms：慢的 d 到点被取消记缺席，快的三路照常合并。
            LightGraph.RunResult<FanState> run =
                    new LightGraph<FanState>(20, pool, 100, 2_000).run(definition, FanState.initial(), List.of());

            assertThat(run.terminalLabel()).isEqualTo("END");
            assertThat(run.state().sources()).containsExactlyInAnyOrder("a", "b", "c");
            assertThat(run.state().collectorRuns()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void guardOnParallelGroupStopsBeforeAnySourceFires() {
        GraphDefinition<FanState> definition = fanGraph(name -> NodeResult.of(new FanDelta(name)));
        PreNodeGuard<FanState> guard = (node, state) ->
                node.equals("a") ? Optional.of("HALTED") : Optional.empty();
        ExecutorService pool = pool(4);
        try {
            LightGraph.RunResult<FanState> run =
                    new LightGraph<FanState>(20, pool, 5_000, 10_000).run(definition, FanState.initial(), List.of(guard));

            assertThat(run.terminalLabel()).isEqualTo("HALTED");
            assertThat(run.stoppedByGuard()).isTrue();
            assertThat(run.state().sources()).isEmpty();
        } finally {
            pool.shutdownNow();
        }
    }

    // ===================== 真实 7 节点图：四路事实汇合 =====================

    /** 计数版事实源：记录每路被查询的次数，并可指定哪一路"查不到"以触发降级。 */
    private static final class CountingDataService extends TravelDataService {
        private final AtomicInteger flights = new AtomicInteger();
        private final AtomicInteger hotels = new AtomicInteger();
        private final AtomicInteger attractions = new AtomicInteger();
        private final AtomicInteger weather = new AtomicInteger();
        private final Set<String> failing = new java.util.HashSet<>();

        CountingDataService() {
            super(new ObjectMapper().findAndRegisterModules(), new DefaultResourceLoader());
            loadFacts();
        }

        void fail(String... sources) {
            failing.addAll(List.of(sources));
        }

        @Override
        public List<FlightFact> searchFlights(String origin, String destination) {
            flights.incrementAndGet();
            return failing.contains("flights") ? List.of() : super.searchFlights(origin, destination);
        }

        @Override
        public List<HotelFact> searchHotels(String destination, int maxPricePerNight) {
            hotels.incrementAndGet();
            return failing.contains("hotels") ? List.of() : super.searchHotels(destination, maxPricePerNight);
        }

        @Override
        public List<AttractionFact> searchAttractions(String destination) {
            attractions.incrementAndGet();
            return failing.contains("attractions") ? List.of() : super.searchAttractions(destination);
        }

        @Override
        public List<WeatherFact> queryWeather(String destination, String date) {
            weather.incrementAndGet();
            return failing.contains("weather") ? List.of() : super.queryWeather(destination, date);
        }
    }

    /** 隔离预检：本节只看并行事实汇合，预检恒可行，不与事实节点抢查询、也不提前拦截降级。 */
    private static final class AlwaysFeasiblePreflight extends TravelPlanPreflight {
        AlwaysFeasiblePreflight(TravelDataService data) {
            super(data);
        }

        @Override
        PreflightResult check(PlanRequest request) {
            return PreflightResult.feasible();
        }
    }

    /** 线程安全事件记录：事实节点在 tool-pool 线程发射，普通 ThreadLocal sink 收不到。 */
    private static final class RecordingSink implements PlanningEventSink {
        private final List<PlanningEvent> all = java.util.Collections.synchronizedList(new ArrayList<>());

        @Override
        public <T> T capture(List<PlanningEvent> events, java.util.function.Supplier<T> action) {
            return action.get();
        }

        @Override
        public void setRound(int round) {
        }

        @Override
        public void emit(PlanningEventType type, String message, java.util.Map<String, Object> details) {
            all.add(new PlanningEvent(all.size() + 1, 0, type, message, details));
        }

        long count(PlanningEventType type) {
            synchronized (all) {
                return all.stream().filter(event -> event.type() == type).count();
            }
        }

        boolean anyFailed(String source) {
            synchronized (all) {
                return all.stream().anyMatch(event -> event.type() == PlanningEventType.FACT_QUERY_FAILED
                        && source.equals(event.details().get("source")));
            }
        }
    }

    private TravelPlanningEngine travelEngine(PlanGenerator generator, TravelDataService data, RecordingSink sink) {
        TravelGraphFactory factory = new TravelGraphFactory(data, sink);
        return new TravelPlanningEngine(generator, new BasicContractReview(), TestTripPlans.constraintReviewer(),
                sink, new AlwaysFeasiblePreflight(data), null,
                new TechnicalRetryExecutor(millis -> {
                }), () -> false, factory);
    }

    private PlanRequest hangzhou() {
        return new PlanRequest("上海", "杭州", LocalDate.of(2026, 10, 1), 3, 3000, 700, "轻松", 3);
    }

    @Test
    void fourFactQueriesAssembleAndGenerateRunsOnce() {
        CountingDataService data = new CountingDataService();
        RecordingSink sink = new RecordingSink();
        AtomicInteger generateCalls = new AtomicInteger();
        TravelPlanningEngine engine = travelEngine(input -> {
            generateCalls.incrementAndGet();
            return PlanGenerationResult.success(TestTripPlans.complete(3), "replay", 1);
        }, data, sink);

        PlanResponse response = engine.plan(hangzhou());

        assertThat(response.terminalState()).isEqualTo(PlanningTerminalState.SUCCESS);
        assertThat(generateCalls).hasValue(1);
        assertThat(data.flights).hasValue(1);
        assertThat(data.hotels).hasValue(1);
        assertThat(data.attractions).hasValue(1);
        assertThat(sink.count(PlanningEventType.FACT_QUERY_STARTED)).isEqualTo(4);
        System.out.println("[四路汇合] flights/hotels/attractions 各查 1 次、weather 查 " + data.weather
                + " 次 → generate 只启动 " + generateCalls.get() + " 次 | 终态=" + response.terminalState());
    }

    @Test
    void enhancementFactFailureDegradesToWarningNotException() {
        CountingDataService data = new CountingDataService();
        data.fail("attractions");
        RecordingSink sink = new RecordingSink();
        TravelPlanningEngine engine = travelEngine(
                input -> PlanGenerationResult.success(TestTripPlans.complete(3), "replay", 1), data, sink);

        PlanResponse response = engine.plan(hangzhou());

        // 景点是增强信息：查不到就带"缺货说明"降级，流程照常走完并交付。
        assertThat(response.terminalState()).isEqualTo(PlanningTerminalState.SUCCESS);
        assertThat(sink.anyFailed("attractions")).isTrue();
        assertThat(response.stopReason()).contains("景点");
    }

    @Test
    void coreFactFailureBlocksDeliveryAsGuarded() {
        CountingDataService data = new CountingDataService();
        data.fail("flights");
        RecordingSink sink = new RecordingSink();
        TravelPlanningEngine engine = travelEngine(
                input -> PlanGenerationResult.success(TestTripPlans.complete(3), "replay", 1), data, sink);

        PlanResponse response = engine.plan(hangzhou());

        // 航班是核心承诺：查不到即禁止正式交付，降级为返回值，不抛异常炸掉整张图。
        assertThat(response.terminalState()).isEqualTo(PlanningTerminalState.GUARDED);
        assertThat(response.stopReport()).isNotNull();
        assertThat(response.stopReport().guardCode()).isEqualTo("CORE_FACTS_MISSING");
        assertThat(sink.anyFailed("flights")).isTrue();
    }

    @Test
    void reworkReusesFactsWithoutRequerying() {
        CountingDataService data = new CountingDataService();
        RecordingSink sink = new RecordingSink();
        AtomicInteger generateCalls = new AtomicInteger();
        TravelPlanningEngine engine = travelEngine(input -> {
            int round = generateCalls.incrementAndGet();
            if (round == 1) {
                return new PlanGenerationResult(TestTripPlans.complete(3), "replay", 1, List.of("缺少返程安排"));
            }
            return PlanGenerationResult.success(TestTripPlans.complete(3), "replay", 1);
        }, data, sink);

        PlanResponse response = engine.plan(hangzhou());

        // 返工走 validate → generate 单边，不重新进入并行事实组：两轮生成，四路事实只查一次。
        assertThat(response.terminalState()).isEqualTo(PlanningTerminalState.SUCCESS);
        assertThat(response.rounds()).hasSize(2);
        assertThat(generateCalls).hasValue(2);
        assertThat(data.flights).hasValue(1);
        assertThat(data.hotels).hasValue(1);
        assertThat(data.attractions).hasValue(1);
        assertThat(sink.count(PlanningEventType.FACT_QUERY_STARTED)).isEqualTo(4);
    }

    // ===================== 工具 =====================

    private static ExecutorService pool(int size) {
        AtomicInteger seq = new AtomicInteger();
        return Executors.newFixedThreadPool(size, runnable -> {
            Thread thread = new Thread(runnable, "test-tool-pool-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
