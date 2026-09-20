# 2026-09-16 写入闸门准入与停摆收尾（SL-1）

- 状态：修复 1 已实施并通过主代理验收；统一调度入口（原修复 2 的替代方案）已实施，等待主代理验收。未运行 Gradle。

## 验证记录

- 修复 1（准入写闸门）：主代理运行完整 `runGameTestServer`，51 项必需测试全部成功，用时 12.69 秒，Gradle 用时 30 秒。日志 `.dsh-tmp/gametest-write-gate-20260916.log`（359086 字节，2026-09-16 17:38:16），本机已确认该文件存在。本轮未自行运行 Gradle。
- 修复 2（受理后停摆）与统一调度入口：未实施，验收覆盖不到。

## 实施记录（统一调度入口）

改动文件与内容：

| 文件 | 改动 |
|---|---|
| `WorldTaskFeature.java` 第 26 至 51 行 | 闭闸时不再提前 `return`。history 保持先运行；placement 与 operation 在闸门开启时走 `tickWorld`，关闭时走各自的 `handOverBlockedTasks`。两个交接独立执行，一个失败不跳过另一个 |
| `OperationManager.java` 第 487 至 542 行 | 新增 `handOverBlockedTasks` 与逐 owner 的 `handOverBlockedTask` |
| `FastPlaceManager.java` 第 886 至 921 行 | 同上，针对 `PlacementTask` |
| `src/test/java/io/github/fastformer/fastplace/TaskHandoverUnderClosedGateTest.java` | 新增单元测试 6 项 |
| `src/main/java/io/github/fastformer/fastplace/world/OperationWriteGateGameTests.java` | 新增 GameTest，驱动真实 `WorldTaskFeature.tick` 入口 |

安全契约（对应任务要求）：

1. 先交接、后出队。新入口调用既有 `WorldHistoryManager.acceptStoppedTask`，只有返回 `CANCELLED_BEFORE_WRITE` 或 `ROLLBACK_STARTED` 时才 `TASKS.remove`。旧 `cancelTask` 的先删后交不用于闭闸路径。
2. 交接抛异常时保留队列槽位。`stopAndTransferRecovery` 或恢复路径抛 `RuntimeException` 或 `OutOfMemoryError` 时记录错误并返回，任务留在 `TASKS`，后续 tick 或退出路径可以重试。
3. 未接收即保留。返回其他值时只写警告，不删任务，并提示 `recovery_journal_blocked`。
4. 一个 owner 失败不影响其他 owner。异常按 owner 捕获，循环继续。
5. 恢复路径继续获得调度。history/recovery 仍每 tick 先运行，且不受暂停分支影响。
6. 工作区终结回执走既有入口。使用 8 参数 `FastPlaceNetwork.sendWorkspaceResult(server, owner, dimension, player, transferId, accepted, retryable, recoveryCreated, ...)`：无写入时为 `retryable=true, recoveryCreated=false`（账本记 `FAILED_RETRYABLE`，可用新 transfer 重试）；已有写入被恢复路径接管时为 `retryable=false, recoveryCreated=true`（账本记 `RECOVERY_REQUIRED`）。

未做的事：没有改 `WorldHistoryManager` 内部、payload、network、controller、session。`tickWorld` 与 `resumeTask` 里的闸门 `break` 与检查保留原样，作为闸门开启时的正常路径。

## P1 修正：恢复快照必须被持有到明确接收

验收发现的问题：保留 `TASKS` 槽位不等于保留恢复数据。`WorldChangeTransaction.transferRecoverySnapshot` 第 162 至 168 行把 before/after 从任务移走，而 `acceptStoppedTask` 是先提取再接收。接收阶段抛错时任务虽留在队列，内容已经空了，下一次可能被当成无写入移除，真正快照丢失。

修正结构：

1. 每个 manager 新增 `HANDOVERS`（`Map<UUID, WorldRecoverySnapshot>`）。提取一次后快照留在该表，直到恢复路径明确接收。
2. 提取只做一次。`transferToRecovery` 先查 `HANDOVERS`，命中就直接复用，不再调用 `stopAndTransferRecovery()`。
3. 接收用新公开边界 `WorldHistoryManager.acceptTransferredRecovery`（原包内方法改为 public，其他语义不变）。它仍然先判断 `hasWrites()`，再走 `startRollback`。
4. 只有 `CANCELLED_BEFORE_WRITE` 或 `ROLLBACK_STARTED` 才清 `HANDOVERS` 并出队。其他结果保留两者。
5. 异常一律视为“未接收”。早期版本用 `WorldHistoryManager.busy(owner)` 判断快照是否已被接管，该判断不成立：同 owner 可能已有无关的 history 任务在运行，那正是任务此前等待的原因。现在只要抛出异常就保留任务与快照并返回 `RECOVERY_BLOCKED`。
6. 接收边界不再在转移后抛出。`WorldHistoryManager.startRollback` 的三处 `context.actionBar(...)` 改为 `notifyQuietly`，入队改为 `enqueueCaptureQuietly`；后者失败时返回 false，表示没有转移，调用方继续持有快照。因此“抛出异常”与“已接收”不再重叠，接收的唯一证明是返回值 `CANCELLED_BEFORE_WRITE` 或 `ROLLBACK_STARTED`。
6. `cancelTask` 改为先接收后出队，并复用 `HANDOVERS` 中的快照。退出路径因此也不会丢记录。`clearServer` 清空 `HANDOVERS`；`enqueueTask` 在新任务入队时清除同 owner 的旧副本。
7. 持有快照期间任务不得被 tick。`tickWorld` 跳过 `HANDOVERS` 中仍有记录的 owner，`resumeTask` 增加同一条件。原因：提取后任务的事务已经是空的，若此时 tick 它，任务会以“无写入”失败收尾并丢掉那条唯一记录。

反例测试 `unrelatedHistoryWorkDoesNotProveThatTheSnapshotWasAccepted`：先为同一 owner 用 `WorldHistoryManager.startRollback` 建立一条无关的 history 任务（不同坐标），确认 `busy(owner)` 为真；再执行带注入异常的交接。断言任务仍在队列、提取次数为 1。旧代码在该情形会因 `busy` 为真而删掉快照并宣告 `ROLLBACK_STARTED`，本测试正是那个假阳性的反例。第二次交接后断言提取次数仍为 1 且任务出队，说明被保留的是原快照。

相关文件：`OperationManager.java`（`HANDOVERS` 字段、`transferToRecovery`、`handOverBlockedTask`、`cancelTask`、`clearServer`、`enqueueTask`）、`FastPlaceManager.java`（同构）、`WorldHistoryManager.java` 第 504 行起的方法可见性。

新增测试 `anAcceptanceFailureKeepsTheExtractedSnapshotForTheRetry`：假任务在**成功提取非空快照之后**让 `journal()` 第一次抛错（该读取发生在接收调用构造参数时，因此异常点位于提取之后）。第一次交接后断言任务仍在、提取次数为 1、未产生恢复工作；第二次交接后断言提取次数仍为 1（没有第二次提取）、任务出队、`WorldHistoryManager.busy` 为真，并从 `WorldHistoryManagerTestAccess.activeRecoveryBatch` 读出恢复批次，断言坐标与 before/after 标记与原始快照一致。该断言检查的是恢复数据本身，不只是队列槽位。
- 触发问题：`writesAllowed() == false` 时是否存在“任务已入队但 tick 停止”，以及如何在不破坏恢复语义的前提下收尾。

## 一、闸门生命周期（当前代码）

置位与复位：

1. `WorldTaskFeature.tickSafely` 第 40 至 48 行：history/recovery、placement、operation 任一 tick 抛出 `RuntimeException` 或 `OutOfMemoryError` 时调用 `PersistentRecoveryJournal.blockNewWrites()`（`WorldTaskFeature.java:45`）。
2. `PersistentRecoveryJournal.blockNewWrites` 第 664 至 666 行置 `startupRecoveryBlocked = true`。
3. `PersistentRecoveryJournal.recoverAll` 第 476 行在服务器启动恢复开始时复位该标志；第 581 行按恢复结果再次赋值。
4. `PersistentRecoveryJournal.awaitIoIdle` 第 684 行在队列未到达空闲边界时再次置位。

读取点：`writesAllowed()`（`PersistentRecoveryJournal.java:660-662`）返回 `!startupRecoveryBlocked`。同一会话内一旦置位，只能等下次启动恢复复位。

## 二、路径核对（结论以当前代码为准）

结论 A：workspace 生产入口已经受闸门保护，原先“闸门关闭后仍入队”的说法不成立。

- `ServerInputDispatcher.canOperate` 第 35 至 39 行要求 `player.isCreative() && FastPlaceSettings.load(player).enabled() && PersistentRecoveryJournal.writesAllowed()`。
- `ServerInputDispatcher.applyWorkspace` 第 520 至 525 行在调用 `OperationManager.applyWorkspace` 之前先要求该判定。闸门关闭时在 `OperationManager` 之前返回 false。

结论 B：闸门在受理之后关闭时，任务停摆，且客户端无法自行结束等待。这是本轮唯一成立的可达链路。

- `OperationManager.tickWorld` 第 480 至 490 行在 `!writesAllowed()` 时 `break`，停摆任务不会被 tick。
- `OperationManager.taskActive(UUID)` 第 743 至 745 行仍是 `TASKS.containsKey`，因此 `PlayerPreviewSync.currentActivity` 继续上报 `OPERATION_TASK`。
- 客户端 `WorkspaceSubmissionTracker.tick` 第 50 至 52 行在 `TASK_ACTIVE` 返回 false，600 tick 启动超时与 40 tick 结果宽限期都不生效。
- 触发条件是一次调度器异常，本轮未做故障注入，未确认出现频率。

结论 C：`FastPlaceNetwork.handleShapePlacement` 第 330 行直接调用 `OperationManager.applyWorkspace`，不经过 `canOperate`。`ShapePlacementPayload` 在当前 `src/main` 中没有生产发送方，因此该路径当前是死代码。若将来出现发送方，它会绕过写入闸门。这是跨文件接点，需要主代理分配。

结论 D：`OperationManager.applyWorkspace` 第 404 至 444 行现在先查 `WorkspaceSubmissionLedger.outcomeFor`（第 410 至 420 行）。同一 transfer 的重放不再二次入队，直接回报已记录结果。该逻辑由断线代理并发加入，本文件行号会继续变化。

## 三、拟修复（限定 `OperationManager.java`）

修复 1：准入拒绝，防御纵深。

在 `OperationManager.applyWorkspace` 顶部检查 `!PersistentRecoveryJournal.writesAllowed()`，显示 `fastformer.message.recovery_journal_blocked` 动作栏并返回 false。`apply` 路径（选择操作任务）同法处理。

理由：结论 C 的唯一无保护入口在别的文件；在 `OperationManager` 内部兜底可以覆盖它以及将来的新入口。效果符合要求：调用方 `FastPlaceNetwork.handleOperationWorkspaceApply` 在 false 时发送 5 参数结果，`retryable` 为 true，客户端保留草稿。

修复 2：停摆任务的终结沿用既有路径，不新增终结代码。

原方案的两个选项作废。新方案不改终结语义，只让停摆任务重新进入它本来就有的收尾路径。

关键证据：全局闸门关闭时，既有代码对本任务的处理方式是“让任务自己处理”，而不是停住它。

1. `ClientWorkspacePlacementTask.tick` 第 103 至 105 行在 `!writesAllowed()` 时直接返回 `OperationTaskResult.FAILED`。该检查在任务内部。
2. `OperationManager.tickWorld` 第 482 至 484 行的 `break` 使任务到不了上面那行，任务因此永久留在 `TASKS`。
3. 任务一旦返回 FAILED，`OperationManager.tickTask` 的失败分支（`retryableWorkspaceFailure` 第 532 行、`settleFailedTask`、`TASKS.remove` 第 583 行）会走既有收尾：`WorldHistoryManager.acceptStoppedTask` → `acceptTransferredRecovery`（`WorldHistoryManager` 第 504 至 523 行）。无写入时走 `CANCELLED_BEFORE_WRITE` 并释放未用日志；已有部分写入时走 `startRollback`。
4. 恢复任务在闸门关闭时被显式豁免：`WorldHistoryManager` 第 802 至 807 行只在 `!task.recovery` 时取消任务，并注释说明“已应用的格子由回滚处理”。这就是 `RECOVERY_REQUIRED` 的真实归属证据：回滚任务由 `WorldHistoryManager` 持有，闸门关闭期间继续运行。
5. `OperationManager.cancelTask` 第 713 行是既有的“带恢复地终止”入口，语义与上面一致。

因此修复 2 的最小改动是去掉 `tickWorld` 里的闸门 `break`，让每个 owner 的停摆任务照常进入 `tickTask`。任务在自己的 tick 内返回 FAILED，然后由既有失败收尾处理：发真实结果、转移恢复所有权、从 `TASKS` 移除。不新增终结回包，不制造 `FAILED_RETRYABLE`，不留下会再次执行的任务。

以上方案已作废。新的已读证据表明它在生产路径无效，见下节。

## 三之二、统一暂停入口：`WorldTaskFeature`（替换修复 2）

已读证据：`WorldTaskFeature.tick` 第 29 至 37 行在 history 之后和 placement 之后各有一次 `|| !writesAllowed()` 提前 `return`。闸门关闭时 `FastPlaceManager.tickWorld` 与 `OperationManager.tickWorld` 都不会被调用，因此删除两个 manager 内部的 `break` 在生产路径上不起作用。

现有结构的不对称：

- history/recovery 先运行，且 `WorldHistoryManager` 第 802 至 807 行在闸门关闭时对非恢复任务调用 `task.requestCancel()`，注释说明未用日志被丢弃或已应用格子被回滚。恢复任务被 `!task.recovery` 条件豁免，继续运行。
- placement 与 operation 被整体跳过，任务没有任何暂停机会。

两类任务也无法可靠自暂停：

- `OperationManager.tickTask` 第 515 至 536 行先检查 level，再 `reserveWorkingSet()`，再 `acquireLease()`，任一步等待就 `return`，任务到不了自己的闸门自检（`ClientWorkspacePlacementTask.tick` 第 103 至 105 行）。
- `PlacementTask` 内部没有闸门检查。已在 `src/main` 内核对：`writesAllowed()` 只出现在 `FastPlaceManager`、`SelectionOperationTask`、`ClientWorkspacePlacementTask`、`WorldHistoryManager`、`WorldTaskFeature`、`ServerInputDispatcher`、命令、生命周期与日志类。

因此统一方案是把暂停与恢复都放在 `WorldTaskFeature`，并让暂停走既有取消入口，而不是走正常 tick：

```java
public static void tick(MinecraftServer server) {
   if (!tickSafely("history/recovery", () -> WorldHistoryManager.tickWorld(server))) {
      return;
   }
   if (PersistentRecoveryJournal.writesAllowed()) {
      if (!tickSafely("placement", () -> FastPlaceManager.tickWorld(server))) {
         return;
      }
   } else {
      tickSafely("placement-pause", () -> FastPlaceManager.handOverBlockedTasks(server));
   }
   if (PersistentRecoveryJournal.writesAllowed()) {
      tickSafely("operation", () -> OperationManager.tickWorld(server));
   } else {
      tickSafely("operation-pause", () -> OperationManager.handOverBlockedTasks(server));
   }
}
```

`handOverBlockedTasks(server)` 只做一件事：对 `List.copyOf(TASKS.keySet())` 的每个 owner 调用既有 `cancelTask(new WorldTaskContext(server, owner))`。该入口已被 `quit` 与 `stopBecauseUnavailable` 使用，且不经过 `reserveWorkingSet` 或 `acquireLease`。无任务时返回 `NOT_ACTIVE`，因此每 tick 重复调用是幂等的。

## 三之三、三态所有权转交（只读核查结论）

| 闭闸时状态 | 转交路径 | 是否需要新内存或新租约准入 | 证据 |
|---|---|---|---|
| 尚未获得租约 | `cancelTask` → `acceptStoppedTask` → `stopAndTransferRecovery` → `hasWrites()` 为 false → `readyForRecovery().thenRun(releaseUnusedJournal)` | 不需要 | `WorldHistoryManager` 第 512 至 516 行；`ClientWorkspacePlacementTask.releaseAfterCancelledJournal` 第 522 至 532 行在 `lease` 为 null 时安全 |
| 已经写入 | 同入口 → `startRollback` → 建立 `HistoryTask.recovery(...)`，或 `enqueueCapture` 排队 | 不需要新租约。只有一次堆检查 `snapshotAdmission(...).fitsCurrentHeap()`，不足时入队并保留所有权 | `WorldHistoryManager` 第 566 至 620 行、第 580 至 595 行 |
| 异步 journal 在飞 | 无写入分支等到 `readyForRecovery()` 完成后才释放日志与租约；写入分支把快照交给回滚，不依赖该日志完成 | 不需要 | `WorldOperationTask.stopAndTransferRecovery` 第 46 至 51 行；`WorldHistoryManager` 第 513 至 519 行 |
| 回滚任务自身 | 由 `WorldHistoryManager` 持有并 tick；闸门关闭时被第 802 行 `!task.recovery` 条件豁免 | 租约走 `WorldWriteCoordinator.takeOver` 同 owner 继承；失败则等待，所有权不丢 | `WorldHistoryManager` 第 2079 至 2100 行；`WorldWriteCoordinator` 第 97 至 122 行 |

排队捕获不会因为闸门关闭而卡住：`WorldHistoryManager.tickWorld` 第 790 行的 `attachPending(context)` 在每次 history 调度时物化待处理捕获，而 history 调度在闸门关闭时仍然运行。

`FastPlaceManager` 存在同一问题，证据相同：`tickWorld` 第 886 至 896 行有同样的 `break`，`resumeTask` 第 904 至 910 行有同样的闸门检查，而 `PlacementTask` 没有自检。`FastPlaceManager.cancelTask` 第 250 至 256 行与 `OperationManager.cancelTask` 结构相同，都走 `acceptStoppedTask`。

## 三之四、故障注入测试（设计，未实施）

1. 未获租约：让另一 owner 持有目标维度租约，使 `acquireLease` 持续失败；入队任务后关闸。断言任务被取消、`TASKS` 为空、世界无写入、返回 `CANCELLED_BEFORE_WRITE`。
2. 已写入：用小预算让任务停在 WRITE 中段，确认 `hasWrites()` 为真后关闸。断言任务被取消、恢复任务或待恢复捕获存在、已写格子回到原状态或标记为待恢复。
3. journal 在飞：制造 journal 准备未完成（分段写入加 IO 屏障），随后关闸。断言在 journal future 完成前租约不释放，完成后日志被清理、任务结束。
4. placement 同类：放置任务在闸门关闭时被交接，验证不是因为自检而是因为暂停入口。
5. 幂等与顺序：单次 tick 内 history、placement-pause、operation-pause 都执行；连续两次 tick 第二次无任务可取消；关闸期间不产生新的回滚任务。
6. 恢复优先：回滚任务在闸门关闭期间继续推进，且不因暂停入口被取消。

残余判断（需主代理确认）：暂停入口会让关闭闸门后的第一次 tick 对所有 owner 执行交接，包含回滚任务创建。这属于行为变化。闸门“不产生新写入”的性质仍由准入（`canOperate`）、`resumeTask` 的闸门检查与交接路径共同保证，其中回滚写入是既有设计中的明确例外。

## 三之五、闭闸期间回滚的持久性边界（只读结论）

1. 闸门在会话内单向。`startupRecoveryBlocked` 只在 `recoverAll` 成功时复位（`PersistentRecoveryJournal` 第 476 行）。因此统一入口的暂停动作对本会话是终态，真正的“恢复”只发生在下次服务器启动。设计上不应假设会话内会重新开闸。
2. 闭闸期间不创建新日志。`PersistentRecoveryJournal` 第 181 至 183 行在 `startupRecoveryBlocked` 时直接返回 `Optional.empty()`。回滚因此只能复用失败任务自带的日志：`acceptStoppedTask` 把 `task.journal()` 交给 `startRollback`（`WorldHistoryManager` 第 493 至 502 行）。
3. 已写入任务必然持有日志。`ClientWorkspacePlacementTask.tick` 的阶段顺序是 VALIDATE、JOURNAL、WRITE，第 129 至 138 行在 JOURNAL 成功后才切到 WRITE，而第一次写入发生在第 139 行之后。因此“已经写入”这一态在闭闸前已经取得日志，回滚的持久证明存在。
4. 尚未获租约且无写入的任务不需要日志：`acceptTransferredRecovery` 第 513 至 516 行走写前结束分支，只等待 `readyForRecovery()` 后释放未用日志。
5. 因此统一入口不需要为回滚申请新的内存或新的持久资源，也不需要新的结局类型。唯一的持久性假设是：任务的日志文件在闭闸后仍可读，并且下次启动的 `recoverAll` 会处理它。

需要保留的判断：`resumeTask` 第 633 至 640 行的闸门检查仍在，因此新入队任务在闸门关闭期间不会被 tick。该检查与准入一致，不影响已入队任务的失败收尾。

全任务类型覆盖证据（主代理核查用）。`TASKS` 只有两个写入方：

| 任务类型 | 入队点 | 闸门关闭时的自检 | 客户端可见结果 |
|---|---|---|---|
| `ClientWorkspacePlacementTask` | `OperationManager` 第 435 行（`applyWorkspace`） | `ClientWorkspacePlacementTask.tick` 第 103 至 105 行返回 FAILED | 有 transfer ID，发 `sendWorkspaceResult` |
| `SelectionOperationTask` | `OperationManager` 第 387 行（`apply`） | `SelectionOperationTask.tick` 第 119 至 122 行置 `failed` 并返回 FAILED | 无 transfer ID，不发工作区结果；客户端跟随会话与预览 |

两类任务都实现 `WorldOperationTask`，都在自己的 tick 内检查同一闸门。因此去掉 `tickWorld` 的 `break` 后，两类任务都会在同一次 tick 内自我终结，不需要按类型分支。

结算次序（`OperationManager.tickTask`，当前行号）：

1. 第 532 行调用 `task.tick(...)`，闸门关闭时返回 FAILED。
2. 第 538 行进入非 ACTIVE 分支。第 539 行计算 `retryableWorkspaceFailure = !task.hasWrites()`。
3. 第 540 至 543 行判定 `failureTransferred`：FAILED 属于该集合。
4. 第 544 至 545 行 `settleFailedTask` → `WorldHistoryManager.acceptStoppedTask` → `acceptTransferredRecovery`。无写入走 `CANCELLED_BEFORE_WRITE` 并释放未用日志；有写入走 `startRollback`。
5. 回滚任务由 `WorldHistoryManager` 持有，闸门关闭期间被第 802 行的 `!task.recovery` 条件豁免，继续运行。
6. 工作区任务在第 566 行附近发送结果，两类任务随后都在第 583 行由 `TASKS.remove(owner, task)` 移除，并显示失败动作栏。

残余需要主代理判断的一点：`tickWorld` 第 482 至 484 行的 `break` 不只影响停摆任务，也决定“闸门关闭时是否继续遍历其他 owner”。去掉 `break` 会让所有 owner 的任务在同一 tick 内依次进入失败结算，包含回滚任务创建。这是行为变化，需要主代理确认是否接受一次性收尾。

修复 1 的落点与顺序：

```java
      var recorded = io.github.fastformer.fastplace.world.WorkspaceSubmissionLedger.outcomeFor(
         server, player.getUUID(), dimension, transferId
      );
      if (recorded != OperationSubmissionOutcome.UNKNOWN) {
         reportRecordedWorkspaceOutcome(player, dimension, transferId, recorded);
         return false;
      }
      if (!PersistentRecoveryJournal.writesAllowed()) {
         // The write gate is closed for this server session. Refuse before any
         // ledger begin so a later retry with a new transfer can be admitted.
         FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.recovery_journal_blocked"));
         return false;
      }
```

顺序理由：重放查询保持在最前，这样已记录的结局仍能回报；闸门检查放在 `WorkspaceSubmissionLedger.begin` 之前，未受理的提交不会留下账本记录。返回 false 后 `FastPlaceNetwork.handleOperationWorkspaceApply` 发送 `accepted=false`、`retryable=true`，客户端保留草稿。

修复 3（跨界，不做）：让“写入闸门关闭”有独立文案，需要给结果载荷加原因字段并改客户端分支。涉及 `network/payload`、`ClientOperationController` 与语言文件，超出本轮范围，交回主代理。

## 四、回归清单（本轮未运行）

1. `writesAllowed() == false` 时 `OperationManager.applyWorkspace` 返回 false，不产生 `TASKS` 条目，并发送 `accepted=false` 且 `retryable=true` 的结果。
2. `tickWorld` 遇到停摆任务时发送一次终结回包，且 `TASKS` 内容在调用前后完全相同。
3. 无写入的停摆任务按既有写前结束语义处理，参数化确认没有世界写入。
4. 已有部分写入的停摆任务在终结后仍保留恢复记录，`WorldHistoryManager` 的待恢复计数不下降。
5. 对照组：闸门正常时 `applyWorkspace` 行为不变（入队、`cancel(player)`、`operation_scanning`）。
6. 与断线代理的账本逻辑交叉：同一 transfer 重放时仍走 `WorkspaceSubmissionLedger` 分支，不被新增闸门检查提前拦截。

## 五、边界与冲突

- 未修改任何文件的生产代码。本文件是唯一新增文档。
- `OperationManager.java` 正被断线代理编辑。本轮不并行写入，避免覆盖。
- 测试与文档以外的一切改动都需要主代理确认归属后再执行；本机不运行 Gradle，无法提供编译验证。

状态：方案完成，等待主代理决定独占与选项 A/B 后实施。
