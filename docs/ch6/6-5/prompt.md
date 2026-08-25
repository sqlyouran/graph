继续这个旅行规划项目。第五章的 TravelPlanningEngine 已经能生成、验证、带反馈
再生成；现在把第六章的设计接进真实引擎。先读现有代码和测试，在现有项目上改，
不要另起一套。

要补的四件事：
1. 预检：进 Loop 前用事实数据排除必然做不到的请求——必去景点查不到、预算低于
   最低可行成本等返回 INFEASIBLE；数据根本不覆盖目的地返回 UNSUPPORTED。这两类
   一次模型都不能调。
2. 四道护栏，每轮开始前统一检查，优先级 G1 无进展（连续两轮 HARD 失败指纹一字
   不差）> G2 Token 预算（已用 + 上一轮×1.2 预估超上限）> G3 总时限（同公式）>
   G4 轮次兜底。
3. 终态：响应带 terminalState 和停止报告（谁拦的、为什么、证据、建议）：SUCCESS /
   GUARDED / INFEASIBLE / UNSUPPORTED / CANCELLED。
4. 技术重试：超时、429、5xx 同轮内最多重试 2 次、固定退避 500ms，成功算一轮；
   全失败就向上抛走 502，不伪装 GUARDED。业务验收失败不在这里重试，交给下一轮。

两条纪律：账本（可变）只允许引擎每轮结束后记；护栏看的是快照（不可变复印件），
只读。时间用 Clock 注入，测试不要 sleep。Token 用字符数近似，命名上标明是估算。

配置沿用 looptrip 前缀：max-tokens=20000、max-duration=30s、multiplier=1.2、
same-fingerprint-limit=2。

不要做熔断、限流、真实 tokenizer、前端取消按钮、SSE。先列出准备修改和新增的
文件、第五章测试怎么保留，再动手。