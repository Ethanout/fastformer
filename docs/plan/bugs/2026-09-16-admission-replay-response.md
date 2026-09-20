# 2026-09-16 服务端准入重放响应审计与修复

范围：`ServerInputDispatcher.applyWorkspace`、`FastPlaceNetwork.handleOperationWorkspaceApply`、`FastPlaceNetwork.handleShapePlacement`、`OperationManager.applyWorkspace` 及其重放分支、`WorkspaceSubmissionLedger.finish`、`WorkspaceSubmissionBook.record`。

审计部分为只读静态复核。修复部分已实施。本轮未运行 Gradle。

行号取自审计当时的工作树。修复后行号有变化。

## 结论摘要

| 编号 | 严重度 | 状态 |
|---|---|---|
| SRA-1 | P1 | 已修：布尔返回值换成类型化准入结果 |
| SRA-2 | P2 | 已修：结果包只有一个发送方 |
| SRA-3 | P1 | 已修：账本只允许向更强的状态迁移 |
| SRA-4 | P2 | 已修：删除第二个发送方与无用语句 |
| SRA-5 | P2 | 已修：形状放置路径走同一类型化路径 |
| SRA-6 | P1 | 新发现并已修：门闸检查早于账本检查，重放被误判为拒绝 |
| SRA-7 | P1 | 原判断错误，已纠正：真问题是修复删掉了活跃任务的回调域 |
| SRA-8 | P1 | 新发现并已修：已准入后的故障与重复请求的解码异常改写运行账本 |

## SRA-1：布尔返回值把四种含义压成一种

证据链：

1. `ServerInputDispatcher.applyWorkspace` 第 520 至 524 行在 `interactionBlocked(player)`、`!canOperate(player)`、`nearNormalBlockReach(player)` 任一成立时返回 false。
2. `OperationManager.applyWorkspace` 第 412 至 428 行在账本已有记录时报告既有结果并返回 false。
3. 同方法第 429 至 435 行在写入门闸关闭时返回 false。
4. 同方法第 437 至 440 行在玩家已有任务时返回 false。
5. `FastPlaceNetwork.handleOperationWorkspaceApply` 第 305 至 309 行见到 false 立即调用 `sendWorkspaceResult(player, transferId, false, List.of(), List.of())`。
6. 该 5 参数重载走 6 参数在线重载，`retryable` 取默认值 true。

因此 false 同时表示：已重放、门闸关闭、玩家忙碌、以及真正的准入拒绝。只有最后一种可以写成「可重试失败」。

## SRA-2：同一提交收到两个结果包

重放一条已应用的提交时：

1. `OperationManager.reportRecordedWorkspaceOutcome` 第 467 行起为 `APPLIED` 发送一个成功结果包。
2. 该方法返回后，`applyWorkspace` 仍在第 427 行返回 false。
3. 处理器第 308 行再发送一个 `accepted=false, retryable=true` 的结果包。

两个包对应同一个 transferId，内容互相矛盾。客户端按到达顺序处理，后到的包决定结论，于是玩家被邀请重发已经写入世界的改动。

重放一条进行中的提交时，第一个包不发送，但第二个包照发。该包随后进入 `WorkspaceSubmissionLedger.finish`，把 `IN_PROGRESS` 写成可重试失败。

## SRA-3：账本允许终态覆盖终态

证据链：

1. `FastPlaceNetwork.sendWorkspaceResult` 在发送前调用 `WorkspaceSubmissionLedger.finish`。
2. `WorkspaceSubmissionLedger.finish` 第 65 至 80 行按三个布尔值映射出结果，`accepted=false, retryable=true` 映射为 `FAILED_RETRYABLE`。
3. `WorkspaceSubmissionBook.record` 第 45 至 53 行的守卫只拦截「终态换成进行中」。
4. 终态换成另一个终态没有被拦截。

后果：`APPLIED` 被写成 `FAILED_RETRYABLE`。此后重连查询对已经写入世界的提交回答可重试失败，客户端可以再次提交同一份工作。

## SRA-4：结果包有两个发送方

`reportRecordedWorkspaceOutcome` 与准入处理器各自构造并发送同一个 transferId 的结果包。投递顺序因此成为语义的一部分，而顺序没有保证。

同一方法第 471 行还有一条无用语句。

## SRA-5：形状放置路径同样折叠

`FastPlaceNetwork.handleShapePlacement` 第 330 至 334 行调用 `OperationManager.applyWorkspace` 并在 false 时发送可重试失败。该路径与工作区路径共享同一处缺陷。

## SRA-6：门闸检查早于账本检查

`ServerInputDispatcher.applyWorkspace` 第 521 行先做门闸判断，再在第 524 行进入 `OperationManager.applyWorkspace`，而账本检查在后者内部。因此门闸关闭时，一条已应用的提交被回答为拒绝，而不是重放。

这条在修复过程中发现。它说明「仅禁止终态覆盖」不够：即使账本保住了已应用状态，客户端仍会收到一个矛盾的结果包。

## SRA-7：请求回调域（原判断错误，已纠正）

原判断：进行中重放每次留下一条回调域记录，形成泄漏。

纠正：请求回调域的键是 `(owner, transferId)`。同一次提交的重放与原始请求共享同一条记录，重放只覆盖同一条，不会增长。原始任务完成时 `sendWorkspaceResult` 会移除该条。所以原判断不成立。

真正的问题由修复引入：对非 `QUEUED` 一律移除，会删掉仍在运行的原始任务的回调域。任务完成时 `remembered` 为 null，结果包回退到当前会话与维度，破坏原 scope。玩家会看到结果落到错误的会话范围。

同一键还有第二处风险：重放的 chunk 0 会调用 `rememberWorkspaceCallbackScope` 覆盖原域，让运行中任务的结果包带上重放的 scope。

修复：

| 场景 | 回调域处理 |
|---|---|
| `QUEUED` | 保留，任务完成时自行移除 |
| `REPLAYED(进行中)` | 保留，活跃任务仍需要它 |
| `REPLAYED(终态)` | 移除，这一次回答结束了当前请求 |
| `REJECTED` | 移除，拒绝结束了当前请求 |

`keepsRequestScope()` 现在同时覆盖 `QUEUED` 与「重放且记录仍开放」。`rememberWorkspaceCallbackScope` 在账本已记录开放状态时不再写入，因此重放不会覆盖活跃任务的域。

## SRA-8：已准入后的故障与重复请求的解码异常

证据链：

1. `OperationManager.applyWorkspace` 在 `enqueueTask` 与 `ledger.begin` 之后调用 `cancel(player)` 与 `actionBar`。
2. 其中任一抛出都会穿出 `applyWorkspace`，经 `ServerInputDispatcher` 进入处理器的 `catch`。
3. 该 `catch` 调用 `sendWorkspaceResult(player, transferId, false, ...)`，`retryable` 取默认 true。
4. `finish` 把 `IN_PROGRESS` 写成 `FAILED_RETRYABLE`。按强度序这是向上迁移，因此被接受。

后果：任务已经入队并在运行，账本却记录可重试失败。客户端被邀请重发同一份工作。

同一 `catch` 还覆盖另一条路径：重复上传的解码异常。此时任务同样在运行，账本同样被改成可重试失败。

修复：以真实任务状态为准，并隔离已准入之后的全部步骤。

准入的边界是「任务进入队列」，不是「方法返回」。世界 tick 循环遍历队列，所以进入队列的任务即使后续步骤失败也会运行。

- `applyWorkspace` 的顺序改为：任务入队，然后记录，然后排首次恢复，然后通知。记录、排恢复、通知三步各自捕获运行时异常并记日志，任何一步失败都不改变准入结果。
- 记录失败时账本为空，查询答 `UNKNOWN`，客户端保留草稿且不自动重发；任务结算时自己的结果包会写入终态。
- `reportFailedAdmission` 以真实状态为权威：账本已有记录则不上报；账本为空但该提交仍有活跃任务也不上报。第二种正是「入队与建账之间发生故障」留下的状态。
- 结果包投递加连接判空与运行时异常隔离。账本已经持有结果，客户端在下次登录通过回执查询取回。

## 已实施的修复

### 类型化准入结果

新增 `io.github.fastformer.fastplace.WorkspaceAdmission`，含三种 kind 与以下查询方法。

| 成员 | 含义 |
|---|---|
| `newQueued()`、`replayed(state)`、`rejected()` | 三个构造入口 |
| `isQueued()` | 新任务是否入队 |
| `deliveredOutcome()` | 要投递的状态，无投递时为 null |

| 成员 | 含义 |
|---|---|
| `sendsResult()` | 是否投递结果包 |
| `keepsRequestScope()` | 是否把请求回调域留给任务 |
| `retryable()` | 投递的状态是否可重试 |

构造入口用 `newQueued()`，实例查询用 `isQueued()`。两者不能同名，否则同一个类中两个 `queued()` 签名冲突。

`deliveredOutcome()` 给出要投递的状态，或在不应投递时给出 null。`sendsResult()` 说明是否投递。拒绝回答可重试失败，重放回答账本状态，进行中的重放不投递。

### 单一响应责任

`OperationManager.applyWorkspace` 返回准入结果，不再发送任何结果包。`reportRecordedWorkspaceOutcome` 已删除。

`FastPlaceNetwork.sendAdmissionResult` 是准入的唯一投递入口，两个处理器都经过它。任务路径继续用 `sendWorkspaceResult`，它在记录后投递账本持有的状态，而不是调用方打算写入的状态。

投递规则：

| 准入 | 投递的状态 |
|---|---|
| `QUEUED` | 不投递，任务稍后自行投递 |
| `REPLAYED(APPLIED)` | 已应用 |
| `REPLAYED(FAILED_RETRYABLE)` | 可重试失败 |

| 准入 | 投递的状态 |
|---|---|
| `REPLAYED(FAILED_NONRETRYABLE)` | 不可重试失败 |
| `REPLAYED(RECOVERY_REQUIRED)` | 恢复处理中，不可重试 |
| `REPLAYED(IN_PROGRESS)` | 不投递 |
| `REJECTED` | 可重试失败，并写入账本 |

### 账本迁移规则

`WorkspaceSubmissionBook.record` 改为单调强度规则，返回调用后账本实际持有的状态。

| 状态 | 强度 |
|---|---|
| `IN_PROGRESS` | 0，最弱，等待结算 |
| `FAILED_RETRYABLE` | 1 |
| `FAILED_NONRETRYABLE`、`RECOVERY_REQUIRED` | 2 |
| `APPLIED` | 3，最强 |

规则：只有强度更高的状态可以替换已记录状态。相等或更弱时保持原状态并返回它。这样正常运行仍然落地（进行中到任意终态），而重放的失败不会覆盖已应用。唯一被拒绝的合法变化是 `FAILED_NONRETRYABLE` 与 `RECOVERY_REQUIRED` 之间的互换，两者对客户端含义相同。

### 门闸顺序

`ServerInputDispatcher.applyWorkspace` 先查账本，再做门闸判断。重放不是新工作，门闸不该遮住已有结果。

`OperationManager.recordedOutcome` 提供该查询。`OperationManager.applyWorkspace` 保留自己的账本检查，因为形状放置路径直接调用它。

### 请求回调域

回调域的键是 `(owner, transferId)`。它只有一个所有者：把任务入队的那个请求。

| 场景 | 处理 |
|---|---|
| 已入队 | 留给任务，任务完成时移除 |
| 重放进行中的工作 | 留给活跃任务，既不删除也不覆盖 |
| 重放终态或拒绝 | 由本次回答移除 |

重放与原始请求共享同一条记录，因此不构成泄漏。活跃任务的域在任务报告结果之前必须一直有效，否则结果包会落到错误的会话范围。

### 协议兼容

未改动任何 payload。`OperationWorkspaceResultPayload` 仍用 `accepted` 与 `retryable` 两个布尔值，由账本状态推导。`RECOVERY_REQUIRED` 与 `FAILED_NONRETRYABLE` 在包内不可区分，客户端文案因此相同。这是已知且被接受的限制，避免协议变更。

## 测试

单元测试：

1. `WorkspaceAdmissionTest` 18 项。三种 kind 的投递决策、进行中重放不投递、拒绝回答可重试失败、回调域归属矩阵、状态映射与投递规则一致。
2. `WorkspaceSubmissionBookTest` 25 项。已应用不被失败替换、进行中可迁移到每一种终态、可重试失败仍可升级为已应用、强度序单调。

实际调用链 GameTest，`WorkspaceReplayGameTests` 5 项：

3. 第二次准入返回 `REPLAYED(IN_PROGRESS)` 且不投递，账本保持进行中。
4. 已应用条目在可重试失败报告之后仍为已应用，重放报告已应用。
5. 恢复处理中的条目保持恢复处理中，重放不提供重试。
6. 写入闸门关闭时，已应用的重放仍返回 `REPLAYED(APPLIED)`。这一项覆盖 SRA-6。
7. 入队准入不自行投递结果，并且账本留下进行中记录。

请求回调域与故障隔离 GameTest，`WorkspaceCallbackScopeGameTests` 7 项。这些用例驱动真实 map 与真实投递方法。

8. 进行中重放后，活跃任务的域仍在，且记录条数不变。
9. 账本已记录进行中时，重放的 chunk 0 不写入，因而不覆盖活跃任务的域。
10. 终态重放移除本次请求的域。
11. 拒绝移除本次请求的域。
12. 失败报告不改写运行中的账本。
13. 账本无记录时，失败报告照常写出可重试失败。
14. 任务在队列中而账本无记录时，失败报告不写任何终态。这一项覆盖入队与建账之间的故障窗口。

反馈故障 GameTest，新增 `WorkspaceAdmissionFaultGameTests` 1 项：

15. 注入反馈故障后，`applyWorkspace` 仍返回 `QUEUED`，账本仍为进行中，任务仍在队列。这一项覆盖 SRA-8 的反馈隔离，用的是注入的真实故障而不是推断。

### 复跑：首次准入被拒的根因

统一 GameTest 复跑 67 项、4 项失败，失败点全是首次 `applyWorkspace` 未入队。

核对拒绝条件。`applyWorkspace` 只在四处拒绝：参数为空或计划无部件、全局写入门闸关闭、`operationBusy`、账本已有记录。前两项在失败用例中都不成立，账本当时为空，因此只能是 `operationBusy`。

根因：`operationBusy` 的第三项是 `WorldWriteCoordinator.busy(server, dimension)`。它按维度判定，不按玩家。相邻批次排队的任务会取一个按维度的写入租约，而租约异步释放。租约尚未落定时，下一个测试的准入被拒。这与准入逻辑无关，是测试对边界的假设错误。

修法分两类，都不清空全局状态来掩盖租约：

| 用例 | 修法 |
|---|---|
| 只需要「进行中」状态的两个用例 | 改用 `WorkspaceSubmissionLedger.begin` 直接写入。账本本就是准入的权威输入，不需要真实任务 |
| 必须验证真实入队路径的两个用例 | 等待真实空闲边界，然后只准入一次 |

后一类用守卫标志防住 `succeedWhen` 反复排队：谓词先判断 `admissionBlocked`，再置标志并入队，断言结束后取消任务，最后等到租约再次落定才成功。等待条件复刻 `operationBusy` 的四项，所以等的是真实边界，不是重置。

真实入队路径的覆盖没有减少：`aQueueingAdmissionSendsNoResultOfItsOwn` 仍走真实入队，`closedWriteGateHandsQueuedWorkToRecovery` 仍走真实入队加恢复交接。

### 尚未覆盖

结果包的实际投递数量没有测试。计数需要一个传输层替身，当前没有。现有测试断言的是投递决策、账本状态与回调域 map，不是线上包数。

形状放置路径的处理器未覆盖，因为该路径当前没有生产发送方。

需要真实入队的用例都要先等真实空闲边界：`WorkspaceReplayGameTests` 的入队用例、`OperationWriteGateGameTests` 的交接用例、`WorkspaceAdmissionFaultGameTests` 的反馈故障用例、以及失败报告覆盖活跃任务的那一项。其余用例用账本直接表达进行中状态。

## 未完成与限制

- 本轮未运行 Gradle。以上测试没有执行结果，GameTest 需要主代理运行。
- 形状放置路径当前在生产代码中没有发送方，测试覆盖依赖将来的发送方。
- 客户端侧的降级防护见 `docs/plan/bugs/2026-09-16-receipt-settlement-implementation.md`。
