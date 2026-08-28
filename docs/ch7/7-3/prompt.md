接着上一轮做。SSE 已经能推事件了，用户现在看得见过程，但还是按不动——
只能看着它跑完。这一轮把人接进循环：能取消、能改需求接着跑、能回滚。
现在测试是 71 个，全绿，这些一个都不许坏。

要做的：

一、会话从"内存里一个 Map"升级成正经的东西。
PlanningSession：四个过程态 CREATED / RUNNING / REVISING / CLOSED。终态别
新造，直接复用现有的 PlanningTerminalState 那五个。
注意：现在代码里已经有一个 PlanningSessionStore，它只管 SSE 的事件、响应和
订阅——不要新造一个跟它撞名的类，在它基础上演化，把会话生命周期加进去。
落盘和恢复用 JSON 文件就够，别给我引数据库。配置沿用 looptrip 前缀：
session.max-live=20、session.ttl=30m。超上限就拒绝新请求，并且把原因和建议一起返回，别光丢个 429。

二、取消。
引擎里那个取消检查点第六章就写好了，缺的只是一个能真被按下的信号——现在那个
bean 是恒返回 false 的。换成会话级的：POST /api/plan/{id}/cancel 置旗标，
信号从当前工作线程绑定的会话上读。
取消点别挪位置，就留在原地（下一轮生成开始之前，和护栏同一个检查点）。
正在跑的那一轮让它跑完，然后返回 CANCELLED 加上目前最好的一版，别丢。
前端按下取消要显示"正在收尾当前一轮"，不许直接跳已取消——那是拿界面状态
骗人。

三、续修。
RevisionRequest + RevisionPolicy 做白名单校验：
  能改 budget / maxHotelPrice / preferences / maxRounds，mustVisit 只能加；
  不能改 destination / startDate / days，mustVisit 也不能删。
判据是"改完之后上一版还有参考价值吗"：改预算，上一版路线还能用；换城市，
上一版一文不值，那就该开新会话。
特别注意 mustVisit 不许删——用户上一轮被必去景点拦住了，删掉它就等于改了
判卷标准，这个口子不能开。拒绝的时候把原因和"请开新会话"的建议一起返回。

四、版本链和回滚。
每版记 request diff、终态、轮次、估算 token。回滚就是切指针，一次模型调用
都不许有。别用"照 v2 的参数重跑"来实现回滚，那是又赌一次。

五、引擎只准改一个地方。
plan(PlanRequest, PlanningSeed)，多一个种子参数，把上一版 plan 和上一版
problems 喂给第 1 轮当 previousPlan / feedback，第 2 轮起走引擎自己的逻辑。
种子只能是纯数据（TripPlan + List<String>），不许把 PlanningSession 传进去。
引擎不认识会话，这条给我写个架构测试守住。

新增三个端点：
  POST /api/plan/{id}/cancel     → 置取消旗标，响应里带当前最好一版的摘要；
                                     重复取消也要幂等返回成功，别报 409 吓唬人。
  POST /api/plan/{id}/revisions  → 续修（校验不过就返回原因和建议，
                                     同样别光丢 400）。
  POST /api/plan/{id}/rollback?version=2 → version 是轮次序号（1、2、3…），
                                     指定版本不存在就返回可选的版本列表。
                                     只对终态会话生效，跑着的不许回滚。
取消和续修生效这两个时刻，前端必须能看见：通过现有的 SSE 通道发对应事件，
新事件类型记得加进 SsePlanningEventSink 那个白名单，否则前端收不到。

还有一条老规矩要延续：会话级的 ThreadLocal 绑定必须在工作线程里做，跟上一轮
SSE 绑 sessionId 是同一个位置、同一条纪律。

不要做的：登录鉴权、数据库、WebSocket、自由文本改单（用户现在只能改结构化
字段，看懂人话是下一轮的事）、前端拖拽排版。

先别动手。告诉我你准备改哪些文件、新增哪些文件，71 个测试怎么一行不改地
保住，取消这条链路你打算在哪几个地方加测试（至少覆盖：运行中取消、取消时还没有最好一版、续修白名单拒绝（含 mustVisit 删项）、回滚零调用），以及你打算怎么证明回滚没有发生任何模型调用。