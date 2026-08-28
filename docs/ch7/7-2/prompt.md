继续这个旅行规划项目。第六章那套引擎我挺满意的——预检、四道护栏、诚实终态、
技术重试都在跑，67 个测试全绿。问题是它现在只能"一次请求换一次结果"：用户
提交完就只能干等，前端唯一显示的东西是个"已等待 N 秒"的秒表。这一轮先解决
一件事——让过程能看见。别动交互，取消和改需求下一轮再说。

在现有项目上改，别另起一套。动手前把现有代码和测试读一遍。

要做的：
1. 多一条推送通道。加 SsePlanningEventSink 实现 PlanningEventSink，往浏览器推
   事件。是组合、不是替换——InMemoryPlanningEventSink 别动，响应里的
   rounds/events 必须保持原样，我还靠它做断言。项目里还没有组合 sink，顺手加
   一个 CompositePlanningEventSink，把新旧两条并联，配置里换成组合。
2. 事件补四个：PREFLIGHT_PASSED、GUARD_TRIGGERED、CANCELLED、HEARTBEAT。
   CANCELLED 这一轮先只定义、先不触发，下一轮做取消时用。
3. 推送内容走白名单，不走黑名单。只推 round、type、message，外加几个标量
   elapsedMs / model / passed。REVIEW_COMPLETED 的 details 里有 problems 列表和
   constraintResults 对象，这两个都不许原样出去——problems 折成一个
   problemsCount 数字就够了。完整 prompt、API key、异常堆栈、内部类名，一个都
   不许出去。这条管子是过公网的，DevTools 谁都能开。
4. 接口改三个地方：
   POST /api/plan/ask         → 改成 202 + { sessionId }，任务丢工作线程跑
   GET  /api/plan/{id}/events → SSE 流，15 秒没事件就发一次 HEARTBEAT
   GET  /api/plan/{id}        → 会话快照（全量），给断线重连补数据用
   先用一个内存里的 Map 存会话就行，落盘、TTL、上限下一轮再管。
5. 前端最小改动。App.tsx 里那个 waitedSeconds 秒表换成事件时间线：EventSource
   订阅，收到事件按顺序追加渲染，HEARTBEAT 不显示。断线后 EventSource 会自己
   重连，重连成功去拉一次快照接口补齐中间丢的事件——别在流里做重放和确认，太重
   了。前端不许自己判断成功失败，一律看后端给的 terminalState。

两件容易踩的，提前说：
- 推送不许影响业务。连接断了、浏览器关了、emit 抛异常，loop 该跑完照样跑完。
  用户关页面不等于停任务。
- PlanningEventSink 里那些 ThreadLocal 必须在工作线程里面建立，别在请求线程
  建好再把 supplier 丢过去——那样事件会静默丢，最难查的一类问题。

不要做的：登录鉴权、数据库、WebSocket、取消、改需求、前端拖拽排版。

最后，别直接开始写。先告诉我你准备改哪些文件、新增哪些文件，还有现有 67 个
测试你打算怎么一行不改地保住。我看过再动手。