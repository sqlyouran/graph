第 7 章的功能都接好了：看得见（SSE 事件流）、按得动（确认、取消、回滚、续修）、听得懂（意图识别 + 门）。现在测试 101 个，全绿。

但"做完了"和"能证明做完了"是两回事。盘一遍，验收地基有四个洞：**有的实现零测试保护，成本收益从来没被量过，而全章唯一没有唯一正确答案的功能——意图识别——连评测集都没有。**这一轮把账清掉。动手前先读四条原则：

1. **测试和评测是两种东西。** 测试必须二值、必须每次提交都跑；评测是分数、会波动、要花模型调用。评测不许塞进默认的 `mvn test`，平时必须是禁用的，只有显式命令能拉起它。
2. **固定回放优先。** 真实模型非确定，断言没法写在它身上。凡是要断言"顺序、次数、终态"的场景，一律用脚本化生成器回放，不联网、不调真实模型、结果可重复一万次。这套做法第 4 章、第 6 章已经用过（Chapter4FeedbackReplayTest、Chapter6ExitMapReplayTest），照那个模式来。
3. **调用计数（calls == N）是一等断言。** 它是唯一能证明"没花钱"的证据。取消零调用、回滚零调用、规则层零调用，这三处已有的要保住，新场景该量就量。
4. **能算的不许交给模型判。** 准确率、混淆矩阵、误判方向——全部程序算出来。大模型只在最后当评委判主观项，而且那是后面人工验收的事，这一轮不碰。

## 一、补四个"有实现、零保护"的测试

1. **脱敏白名单**：`SsePlanningEventSink.emit` 只放行白名单里的 details 键。现在这段过滤逻辑没有任何测试。写测试喂进去带 `apiKey`、`prompt`、`stackTrace`、内部类名的 details，断言推出去的 PublicEvent 里一个都不剩；白名单内的键照常放行；`REVIEW_COMPLETED` 的 problems 只放行数量不放行正文；HEARTBEAT 不进事件流。
2. **九个动作各自命中**：识别器测试现在只覆盖 5 个动作。补齐 ADJUST_PACE、ADD_MUST_VISIT、NEW_SESSION、ASK_QUESTION 四个——stub 模型返回，断言动作命中。规则层那几条（取消/停/回滚）保持 `verifyNoInteractions(chat)`，证明真没调模型。
3. **拒绝理由不雷同**：RevisionPolicy 已经按字段定制了拒绝文案，但没有测试钉住这一点。补一条：改目的地、改日期、改天数、删必去景点，四条拒绝的 reason 两两不雷同。谁把文案改回同一句话，测试红给谁看。
4. **续修的成本必须量出来**：现在没有任何测试证明"续修比重跑省"。用脚本化生成器，同样的轮次设置下，断言"首次规划 + 一次续修"的合计 estimatedTokens 小于"从零重跑两版"的合计。省不了，续修就是白做——这条断言就是这句话的代码版。

## 二、会话级固定回放：Chapter7SessionReplayTest

单点断言散在各个测试类里，缺一条把整个会话从头到尾放一遍的线。新建 `Chapter7SessionReplayTest`，脚本化生成器、不联网，走真实的 PlanController + SessionStore 全链路：

1. **events**：一次完整会话的事件序列——规划轮事件、INTENT_RECOGNIZED、REVISION_STARTED、ROLLBACK_COMPLETED，断言顺序、轮号、类型，并且每个事件的 details 都过脱敏检查。
2. **revision-cost**：续修场景跑完后，从版本链取 estimatedTokens，断言合计省幅（呼应第一条第 4 点）。
3. **flow-calls**：一次"规划 → 语义续修 → 确认 → 回滚"全流程，断言规划引擎只在规划/续修时被调用，确认与回滚阶段的调用计数为 0。

已有的 SessionCancellationTests、PlanningRollbackTests、PlanningChatZeroEngineTests 保持原样，不要合并进来重复维护。

## 三、意图识别评测脚手架：跟测试分开住

意图识别没有唯一正确答案，断言保护不了它，只能评测：

1. **评测集**：建 `eval/intents.jsonl`，50 条话术，每条标三样：`action`、`slots`、`期望出口`（对得上 PlanningChatDecision：EXECUTED / CONFIRM_REQUIRED / CLARIFICATION_REQUIRED / NEW_SESSION_SUGGESTED / ANSWERED / UNRECOGNIZED）。话术按三条配：九个动作 × 三种说法（正常、口语、含错别字或省略）约 27 条；边界与歧义约 15 条；不该触发 Loop 的（问景点信息、闲聊、我们做不到的事）约 8 条。**期望出口里必须包含"澄清"和"反问"——该问一句本身就是正确答案，不是失败。**
2. **跑批入口**：`PlanningIntentEvaluationTests`，用 `@EnabledIfSystemProperty(named = "looptrip.eval", matches = "true")` 门禁——默认 `mvn test` 跳过它，只有显式带 `-Dlooptrip.eval=true` 才跑。入口自己从 `DASHSCOPE_API_KEY` 环境变量构造 ChatClient（qwen3.8-flash、temperature 0.2，与 application.yml 一致），不复用测试上下文里的占位 key。
3. **报告**：跑完写 `eval/reports/intent-eval-<时间戳>.md`，不许只 print 到控制台。报告必须包含：总动作准确率、槽位准确率、出口准确率、按动作的混淆矩阵，以及单独一节 **危险误判**——期望是 ASK_QUESTION 被判成 REVISE_*、期望是 NEW_SESSION 被判成 REVISE_*，这两类是最贵的错（用户只是问个问题或想重新开始，系统却烧掉三轮规划），必须单独报数量，不许平摊进总准确率里。

## 四、分组脚本：scripts/course/ch07-verify.sh

参照 `scripts/course/ch04-feedback-replay.sh` 的风格，分组可跑：
`events / cancel / revision / policy / isolation / rollback / intent / replay / eval / all`。
eval 组显式带 `-Dlooptrip.eval=true`；其余组全部走默认 `mvn test` 的二值通道。

## 五、收尾

跑完后向我报告三组数字：**① 默认测试总数（绿）；② 评测总准确率与危险误判数；③ 续修相对重跑的 token 省幅。** 测试数和评测分数分开报，不许混在一个数里。

先别动手。回答我三个问题再开工：
1. 脱敏白名单测试里，你打算喂哪些敏感键进去？为什么是这几个？
2. 评测门禁为什么用 system property 而不是检查环境变量有没有 key？
3. "危险误判"为什么不能并进总准确率？用一个具体例子说明。
