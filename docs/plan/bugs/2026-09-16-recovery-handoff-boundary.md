# 恢复交接的接收边界：接收后不得再抛普通异常

## 结论

`WorldHistoryManager` 的恢复接收边界有两处“先接收、后可能抛异常”的漏洞，以及一处
`finally` 覆盖返回值的问题。已修：接收完成后只允许安静收尾，提示失败与内存释放失败
都不能改变“已接收”的结论。调用方因此不会把已接收的快照当成未接收而重复交接。

## 已确认问题

1. `WorldHistoryManager.startRollback(WorldTaskContext, ResourceKey, WorldRecoverySnapshot,
   PersistentRecoveryJournal)`（约 455-480）：`!recovery.ready()` 分支先
   `enqueueCapture(...)` 接收捕获，随后直接调用 `context.actionBar(...)`。该调用一旦抛出
   普通异常，异常穿透到 `acceptTransferredRecovery` 的调用方（`finally` 仍会释放任务
   内存），调用方看到异常即认为交接失败，用同一份快照重试，队列因此持有两份捕获。
2. `acceptShortTransactionRecovery`（约 553-575）同一模式：`enqueueCapture(...)` 之后直接
   `context.actionBar(...)`。
3. `acceptTransferredRecovery`（约 514-533）的 `finally { releaseTaskMemory.run(); }`：
   任务内存释放抛出普通异常时，`finally` 的异常替换掉 try 的正常返回值，已成功的接收
   同样被报告成失败。

## 修复

- 两处 `context.actionBar(FastPlaceMessages.text("fastformer.message.task_recovery_blocked"))`
  改走本文件既有的 `notifyQuietly(context, key)`。该助手捕获 `RuntimeException |
  OutOfMemoryError` 并写日志，注释明确“进度提示不得决定所有权”。同文件的 629-655、601-606
  行早已使用同一约定，本次只是让接收边界与之一致。
- `finally` 改为 `releaseTaskMemoryQuietly(releaseTaskMemory)`：新增私有助手，捕获
  `RuntimeException | OutOfMemoryError` 并记 `LOGGER.error`，使接收结果不被释放失败覆盖。
- 快照绑定与队列语义未改：仍是一次 `enqueueCapture`，仍返回原来的
  `ROLLBACK_STARTED` / `RECOVERY_BLOCKED` / `CANCELLED_BEFORE_WRITE`。

## 复核过的其他路径

- `!recovery.hasWrites()` 分支：`readyForRecovery().thenRun(releaseUnusedJournal)`。
  `CompletableFuture.thenRun` 把动作的异常收进返回的 future（`UniRun.tryFire` 捕获
  `Throwable` 后 `completeThrowable`），不向调用方或完成线程抛出。队列在该分支不接收
  任何捕获，因此无重复风险。
- 私有 `startRollback(...)`：`ownerState(...).active = HistoryTask.recovery(...)` 之后只有
  `notifyQuietly`；`enqueueCaptureQuietly(...)` 之后也只有 `notifyQuietly`。这两处已符合
  接收边界约定。
- `resolveAlreadyRestored(...)`：末尾 `enqueueCapture(...)` 之后直接 `return true`。其前序
  `releaseResolvedLease(...)` 抛异常时接收尚未发生，调用方保留快照重试是安全的，且该
  路径幂等。
- 排队失败路径（`enqueueCaptureQuietly` 返回 false）：未转移即未接收，调用方保留快照，
  不需要安静化。

## 回归测试

`src/test/java/io/github/fastformer/fastplace/world/RecoveryHandoffBoundaryTest.java`

- `aFailedMemoryReleaseCannotHideTheAcceptedResult`：`releaseTaskMemory` 抛异常时结果仍为
  `ROLLBACK_STARTED`，队列只有一份捕获。修复前异常会替换返回值。
- `theUnusedJournalCleanupKeepsTheLeaseUntilTheJournalSignalSettles`：交接所用的释放原语
  闭环。直接驱动 `WorldWriteCoordinator.releaseAfterUnusedJournal`（生产链上由
  `PlacementTask.releaseAfterCancelledJournal` →
  `WorldJournalPreparation.releaseAfterCancellation:160-163` 到达）：日志信号未决时 lease
  仍被持有、后继 `acquire` 返回 null；信号落定后 lease 被释放、后继事务可以开始。证明
  “不提前释放、最终释放”，而不是只证明 JDK 吞掉异常。

`src/main/java/io/github/fastformer/fastplace/world/RecoveryHandoffBoundaryGameTests.java`
（游戏测试运行时，与生产 GameTest 同目录）

- `aFailedNotificationCannotTurnAnAcceptedSnapshotIntoAFailure`：真实异步未就绪快照
  （未完成的 `CompletableFuture`，`ready()` 为 false）+ 提示抛异常（用
  `Unsafe.allocateInstance(DedicatedServer.class)` 造一个没有玩家列表的服务端壳，使
  `WorldTaskContext.actionBar` 真实抛空指针）。断言调用仍返回 `ROLLBACK_STARTED` 且队列
  只收到一份捕获。修复前该调用会抛异常。
  该用例原先放在单测里并以 `Assumptions` 跳过（已执行的运行证据：单测 JVM 无法构造服务端
  壳 → 跳过）。游戏测试服务器已经加载了服务端类，壳必然可用，因此这里**构造失败即硬失败**
  （抛 `AssertionError`），不再有跳过路径。
  收尾（不污染整套运行、不清全局）：快照的 before/after 是对同一个真实方块位置的两次相等
  捕获，世界与之匹配；`finally` 里把未决 future 置为已完成（断言失败时也执行），维度使用
  真实已加载维度，于是服务端下一 tick 的 `WorldTaskFeature.tick` →
  `WorldHistoryManager.tickWorld` 会走“已恢复”分支，把该 capture 移出队列且**不写任何方块**。
  `helper.succeedWhen` 轮询断言 `recoveryCaptureCountForTest(owner)` 降到 0 才结束。没有新增
  任何会删除其他任务或清空全局状态的接口。残留仅为该随机 UUID 的一条空闲 owner 记录与一条
  延迟 action bar 文本，不含待办工作。

已删除或迁移的测试与原因（结论保留在文档）：

- 原先的“完成 future 的线程不被异常穿透”用例只验证 `CompletableFuture` 的默认语义，不能
  证明 lease / journal 闭环成功；已由上面的真实释放闭环用例取代。
- 原先的“同一份快照重试会排队两次”用例把重复排队的缺点固定成期望值，会阻止未来正确的
  幂等实现，因此删除。该结论改为文字记录：管理器无法区分首次交接与重试，所以唯一防线是
  不对已接收的交接报告失败；未来若实现幂等接收，应作为行为改进而不是断言当前缺点。
- 原先的“提示抛异常”单测用例迁移到游戏测试运行时（见上），原因是单测 JVM 里
  `Unsafe.allocateInstance(DedicatedServer.class)` 不可用，只会产生跳过而不是证据。

## 未验证与限制

- 交接所用释放原语的语义与“信号永不完成”的缺口见
  `docs/plan/bugs/2026-09-16-recovery-lease-release-audit.md`：`readyForRecovery` 的
  exceptional 分支不可达（两个输入都被 `handle(...)` 归一化），真正未解决的是日志 I/O
  信号永不完成时该维度被永久占用。
- `notifyQuietly` 与 `releaseTaskMemoryQuietly` 只捕获 `RuntimeException` 与
  `OutOfMemoryError`，与本文件既有约定一致；其他 `Error` 仍会穿透。
- 未实机验证：真实服务端上消息发送失败的可达性与表现（游戏测试只构造“没有玩家列表”的
  接收方，覆盖同一抛出路径）。
- 本次未修改 `PlacementTask` / `WorldOperationTask` 两个 manager 及其交接调用点，也未改动
  队列、租约与日志语义。
