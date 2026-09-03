package com.looptrip;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户画像的管理面：查看、确认候选、单条遗忘、清空、学习开关。
 * 规划链路只读画像，写入口全部收在这里——课堂上演示"忘记我"就点这组接口。
 */
@RestController
@RequestMapping("/api/profiles")
public class ProfileController {

    private final PreferenceMemoryService preferenceMemory;
    private final PlanningSessionStore sessions;
    private final PreferenceProperties properties;

    @Autowired
    public ProfileController(PreferenceMemoryService preferenceMemory, PlanningSessionStore sessions,
            PreferenceProperties properties) {
        this.preferenceMemory = preferenceMemory;
        this.sessions = sessions;
        this.properties = properties;
    }

    @GetMapping("/{userId}")
    public ProfileDocument export(@PathVariable String userId) {
        return preferenceMemory.export(resolve(userId));
    }

    @PostMapping("/{userId}/confirm")
    public Map<String, Object> confirm(@PathVariable String userId,
            @RequestParam String candidateId, @RequestParam boolean accepted) {
        String resolved = resolve(userId);
        PreferenceMemoryService.Confirmation confirmation = preferenceMemory.confirm(resolved, candidateId, accepted);
        if (confirmation == null) {
            throw new SessionOperationException(HttpStatus.NOT_FOUND, "CANDIDATE_NOT_FOUND",
                    "偏好候选不存在或已被处理", "请刷新画像后重试");
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("candidateId", candidateId);
        details.put("field", confirmation.candidate().field());
        details.put("content", confirmation.candidate().content());
        details.put("accepted", accepted);
        if (confirmation.preference() != null) {
            details.put("condition", confirmation.preference().condition() == null
                    ? "" : confirmation.preference().condition());
        }
        sessions.publishToUser(resolved, new PlanningSessionStore.PublicEvent(0,
                PlanningEventType.PREFERENCE_CONFIRMED,
                accepted ? "偏好已记住：" + details.get("content") : "已放弃候选：" + details.get("content"),
                details));
        Map<String, Object> body = new LinkedHashMap<>(details);
        body.put("saved", accepted);
        return body;
    }

    @DeleteMapping("/{userId}/preferences/{index}")
    public Map<String, Object> forget(@PathVariable String userId, @PathVariable int index) {
        PreferenceMemoryService.Forgotten forgotten = preferenceMemory.forgetAt(resolve(userId), index);
        if (forgotten == null) {
            throw new SessionOperationException(HttpStatus.NOT_FOUND, "PREFERENCE_NOT_FOUND",
                    "第 " + index + " 条偏好不存在", "请先查看画像确认序号");
        }
        return Map.of("forgotten", true, "field", forgotten.field(),
                "content", forgotten.content(), "reason", forgotten.reason());
    }

    @DeleteMapping("/{userId}")
    public Map<String, Object> clear(@PathVariable String userId) {
        preferenceMemory.clear(resolve(userId));
        return Map.of("cleared", true, "userId", resolve(userId));
    }

    @PostMapping("/{userId}/learning")
    public Map<String, Object> learning(@PathVariable String userId, @RequestParam boolean enabled) {
        preferenceMemory.setLearningEnabled(resolve(userId), enabled);
        return Map.of("userId", resolve(userId), "learningEnabled", enabled);
    }

    private String resolve(String userId) {
        if (userId != null && !userId.isBlank() && !"-".equals(userId)) return userId;
        return properties == null ? "course-demo-user" : properties.defaultUser();
    }
}
