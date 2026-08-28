# 7-4 第四轮补账 Prompt（最后三点：证明、接线、收尾）

接着上一轮做。结构和分工的账基本还完了，剩最后三点。现有 91 个测试全绿，
一个都不许坏。契约照旧冻结：九个动作的枚举值、/chat 的请求响应形状、
PlanningChatDecision 语义、INTENT_RECOGNIZED 事件、门里的判断顺序——一行都不要动。

一、测试。连续两轮没交齐，这轮交全。
1. 识别器规则层命中测试："太贵了""酒店太贵""算了不弄了""还是上一版好""回滚到 2"
   各一条，断言动作和槽位（含"酒店太贵""太贵了"返回的是缺槽位的 REVISE_*，
   不是 UNKNOWN）；
2. 失败路径三条：识别结果是不存在的枚举、槽位类型不对、缺必需槽位——
   分别断言落到 UNKNOWN 或门的澄清，且全程不抛异常；
3. 最硬的一条，上轮被降级了，这轮按标准重来：证明 UNKNOWN、澄清、问问题
   这三条路径从 /chat 端点走到底，一次都碰不到 planningEngine.plan。
   证法照抄 PlanningRollbackTests.rollbackSwitchesStoredPointerWithZeroModelOrEngineCalls：
   引擎用 mock(TravelPlanningEngine.class)，断言 verifyNoInteractions(engine)；
   识别器用一个假的 PlanningIntentRecognizer 实现，分别返回 UNKNOWN、
   缺槽位的 REVISE_BUDGET、ASK_QUESTION 三种意图；再断言会话事件里
   不出现 GENERATION_STARTED。注意："门说不"不等于"引擎没被碰"，门和引擎
   中间还隔着控制器整段派发逻辑，这段必须被测试盖住，才算证明成立。

二、把 dispatchesOperation() 接上线。
这个方法上轮挂到了枚举上，但主代码里没有任何调用者，控制器的 NEW_SESSION
分支还是硬编码的 if——性质挂在墙上没人问，等于没挂。改成派发之前先问枚举：
只有 dispatchesOperation() 为真的动作才走执行分支；"建议新会话"这个分支
也由枚举的性质推出来，不是凭空冒出来的 if。改完后行为不许变：后端依然
不许自动开新会话。

三、删重复的 if 块。
chat 方法里三元式已经把 pendingIntent 赋给了 intent，紧接着又有一个 if
把它重建一遍——留三元式，删重建那段。

先别动手。告诉我：第一条第 3 点的测试你准备怎么搭（替身是哪几个、断言写哪几条）、
第二条接线后 NEW_SESSION 分支的条件长什么样、第三条删的是哪几行。我看过再写。
