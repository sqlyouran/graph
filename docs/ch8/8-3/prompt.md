# Prompt S：偏好学习与记忆管理

继续在这个仓库里做，不另起工程。先读第七章的 PlanningSession、修改版本、SSE 确认流程，以及上一节的 ContextAssembler 和 UserProfile。

现在 ContextAssembler 已经会读画像，但画像一直是空的。我想让系统从用户跨会话的修改中学习偏好，不过不能改一次就永久记住。

请在每次 RevisionPolicy 接受修改后留下 RevisionRecord。修改字段以 RevisionPolicy 接受的 diff 为准——budget、maxHotelPrice、preferences、maxRounds、mustVisit 这五个，不要另起炉灶去重新解析用户原文。学习时按修改类型汇总，同一会话里反复修改只算一次，并保留频次不够的候选。PromotionPolicy 再统一判断：不足三个会话就 WAIT，方向来回变化就 REJECT，稳定候选进入用户确认；如果它只在"带孩子"这类场景下成立，确认时要把条件一起展示和保存。

用户点确认前，候选不要进入 UserProfile。WAIT 候选、修改历史和已确认偏好都放在 data/profiles 目录下按用户 ID 命名的 JSON 文件里，避免每次从所有会话文件重新扫描。项目还没有登录系统，先让创建规划接口接收 X-User-Id 这个请求头，课堂页面固定使用 course-demo-user，旧调用没有传时也使用这个默认值。

每条已确认偏好要保存内容、置信度、适用条件、学习和确认时间、最近使用和衰减时间，以及来自哪些 session。按字段相关性、条件、置信度和新鲜度排序，最多召回八条；本次请求明确说过的内容仍然优先。PlanChatService 负责加载画像并传给 ContextAssembler，不要让 ContextAssembler 自己读文件，也不要让 TravelPlanningEngine 依赖画像组件。

复用现有会话的 SSE：增加 PREFERENCE_LEARNED、PREFERENCE_CONFIRMED、PREFERENCE_FORGOTTEN 三个事件——删除和衰减都走 FORGOTTEN，遗忘在事件流里也是一等公民。新事件要带的字段加进 SSE 白名单，前端才能收到。页面收到候选后显示确认卡片，两个按钮："记住"和"不保存"，用户点"记住"后前端调确认接口。用户改成相反偏好时直接删除旧记录。九十天没有再次使用就降低置信度，低于阈值自动删除；删除和衰减写审计日志。

再提供画像导出、删除单条、清空、关闭学习的接口。配置都放在 looptrip.preference 下。补测试覆盖四种晋升结果、跨三个会话后才确认、九十一天衰减、相反修改删除、画像进入 Prompt，以及 Engine 不依赖画像组件。最后提供一个不联网、不调用模型的课堂回放脚本，打印三次观察、确认写入、Prompt 召回和相反修改删除的结果。
