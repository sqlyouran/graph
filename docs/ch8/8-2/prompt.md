继续改这个旅行规划项目。现在 buildPrompt 会把上一版 TripPlan 全文拼进去，我想把这段改成一个有预算意识的 ContextAssembler。先读现有 PlanGenerationInput、PlanChatService 和事件流，不要另起一套会话模型。

组装时按这个顺序：固定系统指令、本次硬约束、上一版摘要、用户画像、末尾再次确认本轮任务。指令区组装成 system 消息，其余区段组装成 user 消息，保持现在 ChatClient 的调用结构。系统指令和硬约束不能丢；上一版不要再放完整 dailyPlans，只用模板整理出目的地、天数、航班号、酒店和景点。上轮验收反馈是 Loop 下一轮的返工要求，要跟硬约束放在一起，即使预算紧也必须保留。不要再调一次模型做摘要。组装时保留第一节加的 ### SECTION 分区标记——PromptDumper 的分区统计靠它切段，丢了标记分区报告就废了。

先建一个最小的 UserProfile 和 Preference，让组装器支持传入相关偏好；这节生产流程先传空画像，真正的读取和学习留到下一节。空间不够时先不放画像，再把上一版摘要降成只含目的地和天数的最小摘要。把放了哪些区段、丢了哪些区段和原因一起放进 PromptContext 返回。

单次预算使用 looptrip.context.max-tokens 等于 6000，字符数除以 1.5 后向上取整，并预留百分之十五。组装完成后，由真正准备调用模型的 PlanChatService 发出 CONTEXT_ASSEMBLED 事件，再调用第一节的 PromptDumper，最后才调用模型。事件详情要进入现有 SSE 白名单，并且固定四个字段：includedSections 是放进来的区段，droppedSections 是被丢的区段、每条带丢弃原因，estimatedTokens 是本轮估算 token，usableBudget 是扣掉安全余量后的可用预算。

ContextAssembler 保持无状态，不读 session、不做 IO；不要把它放进 TravelPlanningEngine，并把 PlanningArchitectureTests 的 doesNotContain 清单加上 ContextAssembler、PromptDumper 和 UserProfile，用架构测试锁死这条边界。旧测试里"必须发送上一版完整 JSON"的断言已经和新目标冲突，把它更新成"发送结构化摘要且保留全部返工反馈"，其他测试保持通过。先列准备改的文件，再实现并运行测试。