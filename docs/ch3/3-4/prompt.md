请在现有 loop-trip 项目中接通真实大模型，只做本节增量。

本节要解决的问题：把前端的自然语言问题交给后端，
由 Spring AI 调用百炼 Qwen，并把回答、模型名和耗时返回页面。

当前已有能力：
- backend 是 Spring Boot 3.5.8 / Java 21 / Spring AI 1.1.2，
  已配置百炼 OpenAI 兼容端点、DASHSCOPE_API_KEY 和 ChatClient.Builder
- web 已有三栏工作台与 idle/loading/done/error 四态
- requestPlan 已留出 USE_MOCK 接缝，目前只返回假行程

已经确定的设计：
1. AskRequest record：只接收 question
2. AskResponse record：返回 answer、model、elapsedMs
3. PlanChatService：校验问题，调用 ChatClient，记录模型耗时，组装响应
4. PlanController：提供 POST /api/plan/ask，只负责 HTTP 与 Java 对象的转换
5. ApiExceptionHandler：参数问题返回 400 BAD_REQUEST，
   模型调用失败返回 502 MODEL_CALL_FAILED；响应包含 errorCode 和 message
6. web 的 requestPlan 在 USE_MOCK=false 时调用 /api/plan/ask，
   成功结果沿用现有 done 态，失败信息进入现有 error 态
7. /api/meta 的 capabilities 增加 direct-llm-call

系统提示词只约束旅行规划师角色、Markdown 格式、400 字以内和不要寒暄；
本章刻意不给酒店价格、景点开放时间等事实数据。

明确不做：不加 @Tool、不写循环、不做结构化行程、不引入数据库、
不做流式输出、不在模型失败时返回预设假行程。

验收场景：
1. 合法问题返回 200，answer 非空，并带 model 和 elapsedMs
2. 空问题返回 400 BAD_REQUEST
3. 模型调用异常返回 502 MODEL_CALL_FAILED
4. 前端关闭 USE_MOCK 后能显示真实回答、模型名、耗时和错误原因
5. mvn test 全部通过；测试使用 PlanChatService 替身，不得调用真实模型

先输出修改计划、影响文件和测试计划，等我确认后再实现。