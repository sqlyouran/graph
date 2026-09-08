继续改现在这个 LoopTrip 工程。先读 TravelPlanningEngine、LightGraph、
PlanChatService、ContextAssembler、TravelTools 和 TravelDataService，沿用现有包结构、事件和测试。

基于现有 GraphDefinition 和 LightGraph 做增量改造：
1. 将 flights、hotels、attractions、weather 从 generate 拆成四个独立节点；
2. 四个节点只读取同一份 PlanRequest，可同时执行；图保持7个业务节点、14条边；
3. 增加一个四源 JoinSpec，四项都返回数据或明确降级结果后，generate 只启动一次；
4. validate → generate 的返工只重写方案，必须复用已有事实；
5. flights、hotels 作为必需数据，幂等重试1次后仍失败只能 GUARDED；
6. attractions、weather 作为增强数据，失败时保留原因，其他检查通过可带警告交付；
7. 单节点默认5秒、整组默认10秒；固定 deadline，取消或迟到结果不得写入 GraphState；
8. 并行任务使用独立 tool-pool，并设置有界并发，不阻塞 planning-pool；
9. 四路结果组成一份 PlanningFacts 快照，随 PlanGenerationInput 交给 ContextAssembler；
   有快照时告诉模型直接使用，不要再重复调用同一批工具，原有 @Tool 保留作兼容入口。

实现前先说明四项查询为何互不依赖、JoinSpec 等待哪些结果，以及失败矩阵如何映射终态；
确认后再修改。完成后验证同一请求的串并行耗时、天气失败、航班失败、迟到结果隔离，
以及多轮返工只查询一次事实。不要改 C1-C7 和 @Tool 声明，不引入第三方Graph框架。
