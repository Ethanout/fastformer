# 2026-09-16 取消工作区任务不结算账本

范围：`OperationManager.cancelTask`、`handOverBlockedTask`、`WorldHistoryManager` 恢复终结、`WorkspaceSubmissionLedger`。

本轮只改 `OperationManager` 与服务端 GameTest。未改客户端、composer、旋转、预算。未运行 Gradle。共享脏树保留他人修改。代码已冻结。测试由主代理接手验证。

证据级别：静态调用链。自动化未验收。实机未执行。

## 结论

已确认。`cancelTask` 在成功 `transferToRecovery` 后只 `TASKS.remove`。它不调用 `FastPlaceNetwork.sendWorkspaceResult`，因此也不调用 `WorkspaceSubmissionLedger.finish`。恢复路径不补终态。`IN_PROGRESS` 永不过期。客户端回执因此一直等待。

生产入口真实存在：`ServerInputDispatcher.stopBecauseUnavailable` 调用 `OperationManager.cancelTask`。`FastPlaceSettings.toggleEnabled` 在关闭后走该入口。玩家 tick 在 `canOperate` 为假时也走该入口。没有 `setEnabled(false)` 方法。关闭开关就是 `toggleEnabled`。

## 证据链

1. `OperationManager.cancelTask` 原先只交接、再出队。交接成功后不发工作区结果。
2. `handOverBlockedTask` 已发结果：无写入记 `FAILED_RETRYABLE`，有写入记 `RECOVERY_REQUIRED`。取消路径没有复用该入口。
3. `WorldHistoryManager.acceptTransferredRecovery` 只返回 `CANCELLED_BEFORE_WRITE`、`ROLLBACK_STARTED` 或 `RECOVERY_BLOCKED`。它不读 transfer id，也不调用账本。
4. `WorldHistoryManager` 恢复完成只释放租约并写动作栏。它不结算工作区账本。
5. `WorkspaceSubmissionBook.expireFinished` 只删除 `!outcome.open()` 的记录。`IN_PROGRESS.open()` 为真，因此该记录永不超时。
6. `FastPlaceNetwork.answerFor` 在任务已消失且账本仍是 `IN_PROGRESS` 时改答 `UNKNOWN`。那是服务器停机缺口，不是取消终态。客户端因此不能把取消当可重试失败，也不能当恢复接管。

可达生产序列：

1. `OperationManager.applyWorkspace` 入队并 `WorkspaceSubmissionLedger.begin`。
2. 任务尚未写入，或已写入尚未完成。
3. `toggleEnabled` 关闭，或 `canOperate` 失败，进入 `stopBecauseUnavailable`。
4. `OperationManager.cancelTask` 成功接管后移除任务。
5. 账本仍是 `IN_PROGRESS`，且没有主动取消结果通知。现有查询还会把无活动任务的进行中记录降为 `UNKNOWN`，因此不能笼统声称所有查询都会永远返回进行中。问题是缺少可靠取消终态。

## 修复

`cancelTask` 与闭闸交接共用 `settleCancelledTask`。该入口是取消结果的唯一发送拥有者。

规则：

1. 无写入且交接成功：出队，账本记 `FAILED_RETRYABLE`。新 transfer 可以重试。
2. 已有写入且交接成功：出队，账本记 `RECOVERY_REQUIRED`。不鼓励重复写。
3. 拒绝或抛异常：保留任务、保留账本 `IN_PROGRESS`。异常不得丢队列状态。
4. 正常完成走 `tickTask` 的既有发送。完成后 `cancelTask` 返回 `NOT_ACTIVE`，不二次发送。
5. 闭闸交接走同一入口。第二次 cancel 或第二次闭闸不能把更强终态改弱。

未改 `WorldHistoryManager`、payload、客户端。恢复仍不结算工作区账本。

## 测试

`src/main/java/io/github/fastformer/fastplace/OperationCancelSettlementGameTests.java`

| 用例 | 边界 |
|---|---|
| `cancelBeforeWriteSettlesARetryableFailure` | 真实入队后取消。账本为 `FAILED_RETRYABLE`。第二次 cancel 与闭闸交接保持该终态。 |
| `cancelAfterWritesSettlesRecoveryRequired` | 真实 stone 写 gold 后取消。账本为 `RECOVERY_REQUIRED`。恢复后世界回到 stone。 |
| `aCancelPreparationFaultKeepsTheOpenLedger` | 反射把 `journalPreparation` 暂设 null，使 `cancelJournalPreparation` 在抽取前抛错。任务与 `IN_PROGRESS` 保留。finally 恢复原对象后再 cancel，账本变为 `RECOVERY_REQUIRED`，世界回到 stone。这是取消准备异常，不是抽取后异常。抽取后异常由现有 `TaskHandoverUnderClosedGateTest.anAcceptanceFailureKeepsTheExtractedSnapshotForTheRetry` 覆盖。 |
| `aCompletedTaskKeepsItsAppliedResultOnCancel` | 真实 CLIPBOARD 写入空气格完成。账本为 `APPLIED`，目标为 gold。随后 cancel 与闭闸交接不改该终态。 |

准入等待只在首次阶段检查。后续 tick 不因自身任务或恢复而卡住。每个用例只清本 owner 账本，不清全局。没有 Assumptions。

账本不变证明终态没有被改写。本轮没有网络发送计数，不能单独证明网络没有重复发送。

## 已运行验证

全量单测共 1411 项：1410 项通过，0 项失败，1 项已知分组重复用例跳过。全量 GameTest 的 79 项必需测试全部通过，包含本篇 4 项用例。最终日志：`.dsh-tmp/gametest-cancel-rotation-review-20260916-r3.log`。

新测试最终使用 1×1×1 模板的原点，并显式准备目标状态。真实恢复和成功完成断言均保留。新增说明文稿检查：STE 0.00；中文检查覆盖有限。

## 未验收项

1. 客户端实机行为仍未验证。
2. 没有网络层发送计数。账本强度与 `NOT_ACTIVE` 不能单独证明网络没有重复发送。
3. 纯拒绝（`startRollback` 返回 false，无异常）没有单独 GameTest。现有单元测试覆盖抽取后异常。本轮只覆盖抽取前准备异常。
4. `FastPlaceManager.cancelTask` 仍不结算工作区账本。放置任务没有 transfer id，本轮不改。
5. 客户端回执等待未在本轮验证。范围禁止改客户端。
