继续改现在这个 LoopTrip 工程。先读 pom.xml、TravelPlanningEngine.runLoop、
PlanRequest、验证器、护栏、事件定义和相关测试，弄清当前业务路径。沿用现有
Java 21、Spring Boot 3.5、Spring AI、包结构和测试框架。

这轮只改变控制流的表达方式。把现有 runLoop 拆成 preflight、generate、validate
三个业务节点，当前只维护这一张3节点、7条边的可执行图。事实查询暂时留在
 generate 内串行执行，不做并行，也不实现 awaitUser。

动代码前，先用对照表说明：旧 runLoop 的预检、模型技术重试、C1-C7、反馈、
bestRound、ledger、轮次、取消、护栏、现有事件和所有终态，分别迁到哪个节点、
哪条边或哪个执行边界；再列出准备修改的文件和测试，等我确认后再实现。

实现 NodeSpec、EdgeSpec、GraphDefinition 和领域无关的 LightGraph。节点读取不可变
GraphState，只返回 StateDelta；执行器按“执行节点→合并变化→根据新状态选边”推进。
maxSteps 设为25；非终态没有可走的边要明确报错；beforeRound 只在 generate 的业务
轮次入口调用，不能和图步数、技术重试混在一起。

保持 plan() 对外签名、Prompt、C1-C7、反馈、bestRound、护栏、终态和既有事件不变。
重点回放三条业务路径：一次通过、返工一次、预检失败；再跑通用执行器单测与完整回归。

本轮不要提前加入 JoinSpec，不拆四个事实节点，不引入 CompletableFuture、线程池或
第三方Graph框架，也不实现快照和resume。如果讲稿名称与源码不同，以源码为准并说明。