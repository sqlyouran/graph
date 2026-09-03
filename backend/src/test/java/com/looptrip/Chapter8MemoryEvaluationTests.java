package com.looptrip;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 第 8-4 节 Prompt T 评测入口：默认禁用，只有显式 -Dlooptrip.eval=true 才跑，不进 CI 二值通道。
 * 全程离线：组装走真实 ContextAssembler，方案走脚本生成，评委默认 OfflinePersonalizationJudge。
 * -Dlooptrip.eval.limit=N 限制画像数（课堂缩小集），默认 0 = 全量 50 画像 × 3 请求。
 * 每个画像先跑三个会话把偏好建立起来（跨会话序列），再发三条标注请求看召回与体现。
 * 前七个维度是硬门槛（100% 才放行），第八维是软指标（≥80% 看趋势）；报告写 eval/reports/。
 */
@EnabledIfSystemProperty(named = "looptrip.eval", matches = "true")
class Chapter8MemoryEvaluationTests {

    private static final Instant START = Instant.parse("2026-09-01T00:00:00Z");
    private static final String BEFORE = "原始默认安排";

    private record Case(String id, boolean passed, String detail) {
    }

    private record Dim(String name, double gate, List<Case> cases) {
        double rate() {
            return cases.isEmpty() ? 1.0 : (double) cases.stream().filter(Case::passed).count() / cases.size();
        }
    }

    /** kind=text 无条件文本偏好；kind=cond 条件偏好（条件由学习器从"当…时，…"里提取）。 */
    private record ProfileSpec(String id, String kind, String target,
                               String hitPrefs, String secondPrefs, String thirdPrefs) {
    }

    @Test
    void evaluatesEightMemoryDimensionsOffline() throws Exception {
        int limit = Integer.getInteger("looptrip.eval.limit", 0);
        Path profileDir = Files.createTempDirectory("memory-eval-profiles");
        List<Dim> dims = new ArrayList<>();
        try {
            AtomicReference<Instant> now = new AtomicReference<>(START);
            PreferenceProperties properties = new PreferenceProperties(
                    profileDir.toString(), "eval-default", 3, 90, 0.25, 0.3, 8);
            ProfileRepository repository = new ProfileRepository(objectMapper(), properties);
            PreferenceMemoryService service = new PreferenceMemoryService(
                    repository, properties, new PreferenceLearner(), new PromotionPolicy(), mutableClock(now));
            ContextAssembler assembler = new ContextAssembler();
            PersonalizationJudge judge = new OfflinePersonalizationJudge();

            List<Case> d1 = new ArrayList<>();
            List<Case> d2 = new ArrayList<>();
            budgetCascadeCases(assembler, d1, d2);
            List<Case> d3 = new ArrayList<>();
            promotionCases(service, d3);
            List<Case> d4 = new ArrayList<>();
            forgetAndDecayCases(service, repository, now, d4);
            List<Case> d5 = new ArrayList<>();
            conditionSyntheticCases(service, repository, assembler, d5);

            List<Case> d6 = new ArrayList<>();
            List<Case> d7 = new ArrayList<>();
            List<Case> d8 = new ArrayList<>();
            List<String[]> votes = new ArrayList<>();
            List<ProfileSpec> specs = dataset(limit);
            for (ProfileSpec spec : specs) {
                runProfile(spec, service, assembler, judge, d5, d6, d7, d8, votes, specs);
            }

            dims.add(new Dim("1 预算丢弃顺序", 1.0, d1));
            dims.add(new Dim("2 丢弃可追溯", 1.0, d2));
            dims.add(new Dim("3 偏好晋升正确性", 1.0, d3));
            dims.add(new Dim("4 遗忘和衰减正确性", 1.0, d4));
            dims.add(new Dim("5 条件偏好尊重", 1.0, d5));
            dims.add(new Dim("6 显式覆盖尊重", 1.0, d6));
            dims.add(new Dim("7 跨用户隔离", 1.0, d7));
            dims.add(new Dim("8 个性化命中率", 0.8, d8));

            Path report = writeReport(dims, votes, limit, specs.size());
            System.out.println("[eval] 报告已写入 " + report.toAbsolutePath().normalize());
            for (Dim dim : dims) {
                System.out.printf("[eval] %s：%d/%d = %.1f%%（达标线 %.0f%%）%s%n",
                        dim.name(), dim.cases().stream().filter(Case::passed).count(), dim.cases().size(),
                        dim.rate() * 100, dim.gate() * 100, dim.rate() >= dim.gate() ? "" : " ← 未达标");
            }
            for (int i = 0; i < 7; i++) {
                assertThat(dims.get(i).rate()).as("维度 %s 是硬门槛，必须 100%%", dims.get(i).name()).isEqualTo(1.0);
            }
            assertThat(dims.get(7).rate()).as("第八维是软指标，达标线 80%").isGreaterThanOrEqualTo(0.8);
        } finally {
            try (Stream<Path> files = Files.walk(profileDir)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    // ---------- 维度 1/2：预算淘汰级联与可追溯 ----------

    private void budgetCascadeCases(ContextAssembler assembler, List<Case> d1, List<Case> d2) {
        PlanRequest request = request("杭州", "");
        PlanGenerationInput input = new PlanGenerationInput(request, 2, previousPlan(), List.of());
        UserProfile profile = new UserProfile("sweep", List.of(new Preference(null, "preferences", "住宿优先选择民宿")));

        PromptContext full = assembler.assemble(input, profile, 100_000, 0.0, 8);
        PromptContext tight = null;
        PromptContext tighter = null;
        for (int budget = full.estimatedTokens() - 1; budget > 100; budget--) {
            PromptContext ctx = assembler.assemble(input, profile, budget, 0.0, 8);
            if (tight == null && droppedNames(ctx).equals(List.of("profile"))) {
                tight = ctx;
            }
            if (tight != null && droppedNames(ctx).contains("history-detail")) {
                tighter = ctx;
                break;
            }
        }
        assertThat(tight).as("必须存在只砍画像的预算档位").isNotNull();
        assertThat(tighter).as("必须存在历史降级的更紧档位").isNotNull();

        d1.add(new Case("loose", droppedNames(full).isEmpty() && full.includedSections().contains("PROFILE"),
                "宽预算 dropped=" + full.droppedSections()));
        d1.add(new Case("tight", droppedNames(tight).equals(List.of("profile"))
                        && tight.includedSections().containsAll(List.of("INSTRUCTION", "CONSTRAINT", "HISTORY"))
                        && !tight.includedSections().contains("PROFILE")
                        && tight.userPrompt().contains("【上一版摘要】") && !tight.userPrompt().contains("（最小）"),
                "紧预算 dropped=" + tight.droppedSections() + " included=" + tight.includedSections()));
        d1.add(new Case("tighter", droppedNames(tighter).equals(List.of("profile", "history-detail"))
                        && tighter.includedSections().containsAll(List.of("INSTRUCTION", "CONSTRAINT"))
                        && tighter.userPrompt().contains("（最小）"),
                "更紧预算 dropped=" + tighter.droppedSections() + " included=" + tighter.includedSections()));

        d2.add(new Case("tight-trace", tight.droppedSections().size() == 1
                        && "profile".equals(tight.droppedSections().get(0).section())
                        && "剩余预算不足".equals(tight.droppedSections().get(0).reason()),
                "tight droppedSections=" + tight.droppedSections()));
        d2.add(new Case("tighter-trace", tighter.droppedSections().size() == 2
                        && "history-detail".equals(tighter.droppedSections().get(1).section())
                        && "超出剩余预算".equals(tighter.droppedSections().get(1).reason()),
                "tighter droppedSections=" + tighter.droppedSections()));
    }

    // ---------- 维度 3：晋升门槛 ----------

    private void promotionCases(PreferenceMemoryService service, List<Case> d3) {
        String three = "eval-d3-three";
        PromotionDecision first = decide(service, three, "s1", "住宿优先选择民宿");
        PromotionDecision second = decide(service, three, "s2", "住宿优先选择民宿");
        PreferenceMemoryService.ObservationResult third =
                service.observeRevision(three, "s3", diff("preferences", BEFORE, "住宿优先选择民宿"));
        PreferenceMemoryService.Observation observation = third.observations().get(0);
        PreferenceMemoryService.Confirmation confirmation = service.confirm(three, observation.candidateId(), true);
        boolean threeOk = first == PromotionDecision.WAIT && second == PromotionDecision.WAIT
                && observation.decision() == PromotionDecision.CONFIRM_UNCONDITIONAL
                && confirmation.preference() != null
                && service.profileFor(three).profile().preferences().stream()
                        .anyMatch(preference -> preference.text().contains("民宿"));
        d3.add(new Case("three-sessions", threeOk,
                "decisions=" + first + "/" + second + "/" + observation.decision()));

        String two = "eval-d3-two";
        decide(service, two, "s1", "住宿优先选择民宿");
        PreferenceMemoryService.ObservationResult secondResult =
                service.observeRevision(two, "s2", diff("preferences", BEFORE, "住宿优先选择民宿"));
        boolean twoOk = secondResult.observations().get(0).decision() == PromotionDecision.WAIT
                && service.profileFor(two).profile().preferences().isEmpty();
        d3.add(new Case("two-sessions", twoOk,
                "decision=" + secondResult.observations().get(0).decision()
                        + " profileSize=" + service.profileFor(two).profile().preferences().size()));
    }

    // ---------- 维度 4：相反修改与衰减 ----------

    private void forgetAndDecayCases(PreferenceMemoryService service, ProfileRepository repository,
            AtomicReference<Instant> now, List<Case> d4) {
        String user = "eval-d4-opposite";
        establish(service, user, "住宿优先选择民宿");
        PreferenceMemoryService.ObservationResult opposite = service.observeRevision(user, "s4",
                diff("preferences", "住宿优先选择民宿", "住宿优先选择连锁酒店"));
        boolean oppositeOk = opposite.forgotten().stream()
                .anyMatch(forgotten -> "相反修改".equals(forgotten.reason()))
                && service.profileFor(user).profile().preferences().isEmpty();
        d4.add(new Case("opposite-delete", oppositeOk, "forgotten=" + opposite.forgotten()));

        now.set(START);
        String decayUser = "eval-d4-decay";
        repository.save(new ProfileDocument(decayUser, true, List.of(), List.of(), List.of(
                new Preference(null, "preferences", "住宿优先选择民宿", 1.0, null, START, START, null, List.of("s1"), null))));
        now.set(START.plusSeconds(91L * 24 * 3600));
        double confidence = service.profileFor(decayUser).profile().preferences().get(0).confidence();
        d4.add(new Case("decay-drop", confidence == 0.75, "confidence=" + confidence));

        String deleteUser = "eval-d4-delete";
        repository.save(new ProfileDocument(deleteUser, true, List.of(), List.of(), List.of(
                new Preference(null, "preferences", "住宿优先选择民宿", 0.5, null, START, START, START, List.of("s1"), null))));
        PreferenceMemoryService.ProfileLoad load = service.profileFor(deleteUser);
        boolean deleteOk = load.forgotten().stream().anyMatch(forgotten -> "衰减删除".equals(forgotten.reason()))
                && load.profile().preferences().isEmpty();
        d4.add(new Case("decay-delete", deleteOk, "forgotten=" + load.forgotten()));
    }

    // ---------- 维度 5：条件偏好合成用例 ----------

    private void conditionSyntheticCases(PreferenceMemoryService service, ProfileRepository repository,
            ContextAssembler assembler, List<Case> d5) {
        String user = "eval-d5-cond";
        repository.save(new ProfileDocument(user, true, List.of(), List.of(), List.of(
                new Preference(null, "preferences", "当孩子时，不安排爬山", 1.0, "孩子", START, START, null, List.of("s1"), null))));
        UserProfile profile = service.profileFor(user).profile();

        PromptContext withKid = assembler.assemble(
                new PlanGenerationInput(request("杭州", "这次带孩子，节奏舒缓"), 1, null, List.of()), profile, 6000, 0.15, 8);
        d5.add(new Case("cond-holds", withKid.includedSections().contains("PROFILE")
                && withKid.userPrompt().contains("当孩子时，不安排爬山"), "included=" + withKid.includedSections()));

        PromptContext noKid = assembler.assemble(
                new PlanGenerationInput(request("杭州", "商务出差节奏"), 1, null, List.of()), profile, 6000, 0.15, 8);
        d5.add(new Case("cond-absent", !noKid.userPrompt().contains("当孩子时，不安排爬山"),
                "included=" + noKid.includedSections()));
    }

    // ---------- 每画像三场景：命中 / 第二请求 / 第三请求 ----------

    private void runProfile(ProfileSpec spec, PreferenceMemoryService service, ContextAssembler assembler,
            PersonalizationJudge judge, List<Case> d5, List<Case> d6, List<Case> d7, List<Case> d8,
            List<String[]> votes, List<ProfileSpec> all) {
        String user = "eval-" + spec.id();
        establish(service, user, spec.target());
        UserProfile profile = service.profileFor(user).profile();

        PlanRequest hitRequest = request("杭州", spec.hitPrefs());
        PromptContext hit = assembler.assemble(
                new PlanGenerationInput(hitRequest, 1, null, List.of()), profile, 6000, 0.15, 8);
        boolean injected = hit.includedSections().contains("PROFILE") && hit.userPrompt().contains(spec.target());
        String plan = scriptedPlan(hit, hitRequest);
        boolean[] ballot = new boolean[3];
        for (int i = 0; i < 3; i++) {
            ballot[i] = judge.reflects(spec.target(), plan);
        }
        votes.add(new String[]{spec.id(), String.valueOf(ballot[0]), String.valueOf(ballot[1]), String.valueOf(ballot[2])});
        d8.add(new Case(spec.id() + "-hit", injected && ballot[0] && ballot[1] && ballot[2],
                "injected=" + injected + " votes=" + ballot[0] + "/" + ballot[1] + "/" + ballot[2]));

        // 数据集里不同画像可能共用同一句偏好文本，隔离检查按文本去重——否则自己的文本会被当成别人的泄漏
        List<String> leaked = all.stream().map(ProfileSpec::target).distinct()
                .filter(text -> !text.equals(spec.target()))
                .filter(hit.userPrompt()::contains).toList();
        d7.add(new Case(spec.id() + "-iso", injected && leaked.isEmpty(),
                "ownText=" + injected + " leaked=" + leaked));

        PromptContext second = assembler.assemble(
                new PlanGenerationInput(request("杭州", spec.secondPrefs()), 1, null, List.of()), profile, 6000, 0.15, 8);
        PromptContext third = assembler.assemble(
                new PlanGenerationInput(request("杭州", spec.thirdPrefs()), 1, null, List.of()), profile, 6000, 0.15, 8);
        boolean secondInjected = second.userPrompt().contains(spec.target());
        boolean thirdInjected = third.userPrompt().contains(spec.target());

        if ("cond".equals(spec.kind())) {
            // 条件成立才注入；条件不成立（无论是否带别的显式输入）都不注入
            d5.add(new Case(spec.id() + "-cond-holds", injected, "hit included=" + hit.includedSections()));
            d5.add(new Case(spec.id() + "-cond-absent", !secondInjected,
                    "second prefs=" + spec.secondPrefs() + " included=" + second.includedSections()));
            d5.add(new Case(spec.id() + "-cond-input", !thirdInjected,
                    "third prefs=" + spec.thirdPrefs() + " included=" + third.includedSections()));
        } else {
            // 显式覆盖本次不注入，下一次恢复；无关显式输入不许抹掉偏好
            PromptContext restored = assembler.assemble(
                    new PlanGenerationInput(request("杭州", ""), 1, null, List.of()), profile, 6000, 0.15, 8);
            boolean restoredInjected = restored.userPrompt().contains(spec.target());
            d6.add(new Case(spec.id() + "-override", !secondInjected && restoredInjected,
                    "overrideDropped=" + !secondInjected + " restored=" + restoredInjected));
            d6.add(new Case(spec.id() + "-survive", thirdInjected,
                    "third prefs=" + spec.thirdPrefs() + " included=" + third.includedSections()));
        }
    }

    // ---------- 数据集与基础设施 ----------

    private List<ProfileSpec> dataset(int limit) {
        String[] texts = {"住宿优先选择民宿", "住宿偏好民宿", "民宿优先的住宿", "住宿选民宿", "住宿倾向民宿风格"};
        String[] condTexts = {"当孩子时，不安排爬山", "当孩子时，避免登山景点", "当孩子时，不排爬山活动",
                "当孩子时，跳过爬山项目", "当孩子时，行程排除爬山"};
        List<ProfileSpec> specs = new ArrayList<>();
        for (int v = 0; v < 5; v++) {
            for (int d = 0; d < 5; d++) {
                specs.add(new ProfileSpec("t%d%d".formatted(v, d), "text", texts[v],
                        "", "这次住宿想试试酒店", "用餐偏好小吃街"));
                specs.add(new ProfileSpec("c%d%d".formatted(v, d), "cond", condTexts[v],
                        "这次带孩子，节奏舒缓", "商务出差节奏", "这次想试试酒店"));
            }
        }
        if (limit > 0 && limit < specs.size()) {
            return specs.subList(0, limit);
        }
        return specs;
    }

    private void establish(PreferenceMemoryService service, String user, String target) {
        decide(service, user, user + "-s1", target);
        decide(service, user, user + "-s2", target);
        PreferenceMemoryService.ObservationResult third =
                service.observeRevision(user, user + "-s3", diff("preferences", BEFORE, target));
        PreferenceMemoryService.Observation observation = third.observations().get(0);
        service.confirm(user, observation.candidateId(), true);
    }

    private PromotionDecision decide(PreferenceMemoryService service, String user, String sessionId, String target) {
        return service.observeRevision(user, sessionId, diff("preferences", BEFORE, target))
                .observations().get(0).decision();
    }

    /**
     * 脚本化方案生成：prompt 里注入了民宿就住民宿，注入了"不爬山"就避开爬山——
     * 它模拟的是一个"听话的模型"，让第八维验的是"偏好有没有进到 prompt"，而不是模型心情。
     */
    private String scriptedPlan(PromptContext context, PlanRequest request) {
        String prompt = context.userPrompt();
        String hotel = prompt.contains("民宿") ? request.destination() + "西湖民宿" : request.destination() + "商务酒店";
        String activity = prompt.contains("爬山") || prompt.contains("登山")
                ? request.destination() + "湖滨步行" : request.destination() + "爬山观景台";
        return "## %s %d 日行程%n- 住宿：%s%n- 活动：%s".formatted(
                request.destination(), request.days(), hotel, activity);
    }

    private Path writeReport(List<Dim> dims, List<String[]> votes, int limit, int profileCount) throws Exception {
        StringBuilder report = new StringBuilder();
        report.append("# 记忆八维评测报告 ")
                .append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))).append("\n\n");
        report.append("- 门禁：-Dlooptrip.eval=true").append(limit > 0 ? " -Dlooptrip.eval.limit=" + limit : "（全量）").append('\n');
        report.append("- 模式：离线回放（脚本化方案 + OfflinePersonalizationJudge，不联网不调模型）\n");
        report.append("- 数据集：").append(profileCount).append(" 画像 × 3 请求（命中/第二/第三场景），每画像先跑三个会话建立偏好，独立 userId，跑完清理 profiles 目录\n\n");
        report.append("## 各维度通过率\n\n| 维度 | 用例 | 通过 | 通过率 | 达标线 | 结果 |\n|---|---|---|---|---|---|\n");
        for (Dim dim : dims) {
            long passed = dim.cases().stream().filter(Case::passed).count();
            report.append(String.format("| %s | %d | %d | %.1f%% | %.0f%% | %s |%n",
                    dim.name(), dim.cases().size(), passed, dim.rate() * 100, dim.gate() * 100,
                    dim.rate() >= dim.gate() ? "达标" : "**未达标**"));
        }
        report.append("\n## 失败用例\n\n");
        List<Case> failures = dims.stream().flatMap(dim -> dim.cases().stream()).filter(caseItem -> !caseItem.passed()).toList();
        if (failures.isEmpty()) {
            report.append("本次运行没有失败用例。\n");
        } else {
            for (Case failure : failures) {
                report.append("- ").append(failure.id()).append("：").append(failure.detail()).append('\n');
            }
        }
        report.append("\n## 第八维 judge 投票明细（前 5 条）\n\n| 画像 | 票1 | 票2 | 票3 | 多数 |\n|---|---|---|---|---|\n");
        votes.stream().limit(5).forEach(row -> report.append("| ").append(String.join(" | ", row))
                .append(" | ").append("true".equals(row[1]) && "true".equals(row[2]) && "true".equals(row[3]))
                .append(" |\n"));
        report.append("\n前七个维度是硬门槛，全部 100% 才放行；第八维是软指标，看趋势不做卡点。\n");
        Path reports = Path.of("..", "eval", "reports");
        Files.createDirectories(reports);
        Path reportPath = reports.resolve("memory-eval-"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".md");
        Files.writeString(reportPath, report.toString());
        return reportPath;
    }

    private List<String> droppedNames(PromptContext context) {
        return context.droppedSections().stream().map(PromptContext.DroppedSection::section).toList();
    }

    private PlanRequest request(String destination, String preferences) {
        return new PlanRequest("出发地", destination, LocalDate.of(2026, 10, 1), 3,
                3000, 600, preferences, List.of(), 2);
    }

    private TripPlan previousPlan() {
        return new TripPlan("出发地", "杭州", LocalDate.of(2026, 10, 1), 3,
                new TripFlight("MU1", "出发地", "杭州",
                        LocalDateTime.parse("2026-10-01T08:00"), LocalDateTime.parse("2026-10-01T09:00"), 200),
                new TripFlight("MU2", "杭州", "出发地",
                        LocalDateTime.parse("2026-10-04T18:00"), LocalDateTime.parse("2026-10-04T19:00"), 200),
                List.of(new TripDayPlan(LocalDate.of(2026, 10, 1), new TripHotel("杭州饭店", "西湖", 500),
                        List.of(new TripActivity("西湖", "ATTRACTION", LocalTime.of(9, 0), LocalTime.of(11, 0), "西湖", 0)))));
    }

    private Map<String, Object> diff(String field, Object before, Object after) {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("before", before);
        change.put("after", after);
        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put(field, change);
        return diff;
    }

    private ObjectMapper objectMapper() {
        return new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
    }

    private Clock mutableClock(AtomicReference<Instant> now) {
        return new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
    }
}
