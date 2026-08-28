package com.looptrip;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class RevisionPolicy {
    public RevisionDecision apply(PlanRequest current, RevisionRequest change) {
        if (change == null) return RevisionDecision.reject("续修内容不能为空");
        if (change.destination() != null && !change.destination().equals(current.destination())) {
            return RevisionDecision.reject("目的地不能在续修中修改，上一版路线已失去参考价值");
        }
        if (change.startDate() != null && !change.startDate().equals(current.startDate())) {
            return RevisionDecision.reject("出发日期不能在续修中修改，上一版时间安排已失去参考价值");
        }
        if (change.days() != null && change.days() != current.days()) {
            return RevisionDecision.reject("行程天数不能在续修中修改，上一版日程结构已失去参考价值");
        }
        List<String> mustVisit = change.mustVisit() == null ? current.mustVisit() : normalize(change.mustVisit());
        if (!new LinkedHashSet<>(mustVisit).containsAll(current.mustVisit())) {
            return RevisionDecision.reject("必去景点只能增加，不能删除或替换已有项目");
        }
        int budget = change.budget() == null ? current.budget() : change.budget();
        int hotel = change.maxHotelPrice() == null ? current.maxHotelPrice() : change.maxHotelPrice();
        int rounds = change.maxRounds() == null ? current.maxRounds() : change.maxRounds();
        String preferences = change.preferences() == null ? current.preferences() : change.preferences().trim();
        if (budget <= 0) return RevisionDecision.reject("预算必须大于 0");
        if (hotel <= 0) return RevisionDecision.reject("酒店每晚限价必须大于 0");
        if (rounds < 1 || rounds > 5) return RevisionDecision.reject("最大轮次必须在 1 到 5 之间");

        PlanRequest revised = new PlanRequest(current.origin(), current.destination(), current.startDate(),
                current.days(), budget, hotel, preferences, mustVisit, rounds);
        Map<String, Object> diff = new LinkedHashMap<>();
        putChanged(diff, "budget", current.budget(), budget);
        putChanged(diff, "maxHotelPrice", current.maxHotelPrice(), hotel);
        putChanged(diff, "preferences", current.preferences(), preferences);
        putChanged(diff, "maxRounds", current.maxRounds(), rounds);
        putChanged(diff, "mustVisit", current.mustVisit(), mustVisit);
        return new RevisionDecision(true, revised, diff, "续修字段校验通过", "继续使用上一版方案作为参考");
    }

    private List<String> normalize(List<String> values) {
        List<String> normalized = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.isBlank() && !normalized.contains(value.trim())) normalized.add(value.trim());
        }
        return List.copyOf(normalized);
    }

    private void putChanged(Map<String, Object> diff, String name, Object before, Object after) {
        if (!java.util.Objects.equals(before, after)) {
            Map<String, Object> change = new LinkedHashMap<>();
            change.put("before", before);
            change.put("after", after);
            diff.put(name, change);
        }
    }
}
