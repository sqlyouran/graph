package com.looptrip;

import java.util.ArrayList;
import java.util.List;

/**
 * 四路事实快照：每一项要么带数据回来，要么带明确的失败说明回来。
 * 降级是返回值，不是异常——缺一项不等于整份快照作废。
 */
public record PlanningFacts(FactSlot flights, FactSlot hotels, FactSlot attractions, FactSlot weather) {

    /** 单个来源的交代：ok=true 带事实文本；ok=false 带失败原因。 */
    public record FactSlot(String source, boolean ok, String note, String text) {
    }

    public static PlanningFacts empty() {
        return new PlanningFacts(null, null, null, null);
    }

    public boolean present() {
        return flights != null || hotels != null || attractions != null || weather != null;
    }

    /** 合并一个来源的交代；同一来源后到的不覆盖先到的（迟到不采用由执行器保证）。 */
    public PlanningFacts with(FactSlot slot) {
        if (slot == null) {
            return this;
        }
        return new PlanningFacts(
                slot.source().equals("flights") ? slot : flights,
                slot.source().equals("hotels") ? slot : hotels,
                slot.source().equals("attractions") ? slot : attractions,
                slot.source().equals("weather") ? slot : weather);
    }

    /** 核心承诺缺失：航班或酒店没查到，validate 必须阻止正式交付。 */
    public boolean coreMissing() {
        return missing(flights) || missing(hotels);
    }

    /** 增强信息缺失：景点或天气没查到，可带警告交付。 */
    public List<String> warnings() {
        List<String> out = new ArrayList<>();
        if (missing(attractions)) out.add("景点事实缺失：" + noteOf(attractions));
        if (missing(weather)) out.add("天气事实缺失：" + noteOf(weather));
        return List.copyOf(out);
    }

    public List<FactSlot> slots() {
        List<FactSlot> out = new ArrayList<>();
        for (FactSlot slot : List.of(flights, hotels, attractions, weather)) {
            if (slot != null) out.add(slot);
        }
        return List.copyOf(out);
    }

    private static boolean missing(FactSlot slot) {
        return slot != null && !slot.ok();
    }

    private static String noteOf(FactSlot slot) {
        return slot == null ? "未查询" : slot.note();
    }
}
