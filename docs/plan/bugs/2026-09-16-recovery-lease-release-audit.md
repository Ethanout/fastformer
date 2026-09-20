# 恢复交接后 lease / journal 释放链审计（只读）

## 结论摘要

- **异常路径不可达。** `readyForRecovery` 的两个输入都被 `handle((ignored, failure) -> null)`
  归一化为“正常完成”，所以“readyFuture exceptional 导致 `thenRun` 不执行”在本链上不成立。
  不需要为“任意 lambda 可能抛异常”加防御。
- **真正可达的是“信号永不完成”。** `IO_EXECUTOR` 是单线程守护线程，一个卡住的日志 I/O 会让
  `completion()` 永不完成 → `thenRun` 永不触发。此时原任务已从管理器移除，lease 只被该
  lambda 引用，没有任何路径再释放它：该维度对本玩家永久不可写（直到重启），journal 文件
  也既未丢弃也未登记到重试表。
- **释放动作本身是失败安全的。** 要么释放，要么登记重试，要么留给启动恢复；不会抛、不会
  半释放。

## 实际调用链（含证据）

1. 交接调用点：`FastPlaceManager.transferToRecovery:937-966`、
   `OperationManager.transferToRecovery:542-571`，都把
   `() -> task.releaseAfterCancelledJournal(context)` 作为 `releaseUnusedJournal` 传入。
   返回 `CANCELLED_BEFORE_WRITE` / `ROLLBACK_STARTED` 后 `HANDOVERS.remove(owner)`，
   `FastPlaceManager.handOverBlockedTask:920-925` 同时 `TASKS.remove(owner, task)`。
   **即：接收后原任务对象被丢弃，lease 只活在这个 lambda 里。**
2. 无写入分支：`WorldHistoryManager.acceptTransferredRecovery:522-526`
   → `recovery.readyForRecovery().thenRun(releaseUnusedJournal)`。
3. `readyForRecovery` 的来源：
   - `PlacementTask.stopAndTransferRecovery:904-910`
     = `allOf(operationCommit.stopForRecovery(), journalPreparation.completion())`。
   - 默认实现 `WorldOperationTask.stopAndTransferRecovery:46-51`
     = `allOf(commit.stopForRecovery(), journalCompletion())`；
     `journalCompletion()` 覆盖见 `SelectionOperationTask:145-147`、
     `ClientWorkspacePlacementTask:426-428`，两者都返回 `journalPreparation.completion()`。
   - `WorldOperationCommit.stopForRecovery:116-119` → `WorldHistoryPublication.stopForRecovery:82-85`
     → `this.future.handle((ignored, exception) -> null)`：**异常转正常完成**。
   - `WorldJournalPreparation.completion:109-112` → `pending.handle((ignored, failure) -> null)`：
     **异常转正常完成**。
   因此 `allOf` 的输入只会正常完成，`thenRun` 只在“I/O 已终止”时执行或永不执行，
   exceptional 分支不可达。
4. 释放链本身：`PlacementTask.releaseAfterCancelledJournal:628-637`
   → `operationCommit.cancel()`（其 `whenComplete` 内的 `deleteOrphanCorrection` 自身捕获
   `IOException | RuntimeException`，见 `PersistentRecoveryJournal:459-468`）
   → `WorldJournalPreparation.releaseAfterCancellation:160-163`
   → `WorldWriteCoordinator.releaseAfterUnusedJournal:187-225`：
   - `lease == null`：记 error 并保持不动（该调用方本来就没有 lease）。
   - `journal != null`：`discardUnused()`（`PersistentRecoveryJournal:303-319` 捕获
     `IOException | RuntimeException` 后返回 false，不抛）→ true 则 `release`，false 则
     `retainUnusedJournal` 进入 `PENDING_UNUSED` 重试环（`WorldWriteCoordinator:277-312`）。
   - `journal == null`：对日志准备 future 注册 `whenComplete`；future 已结束时立即走同一套
     分支。
   → 没有“抛异常导致已接收交接被当成失败”的残留路径，也没有半释放状态。

## 真正可达的缺口（建议决策，未改代码）

`IO_EXECUTOR = Executors.newSingleThreadExecutor(...)`，单线程守护线程
（`PersistentRecoveryJournal:67-71`）。若某个日志 I/O 永不返回（网络盘、杀软长时间持锁、
线程卡死），则：

- `journalPreparation.completion()` 永不完成 → `thenRun` 永不执行 → `releaseAfterCancelledJournal`
  永不执行；
- 原任务已从 `TASKS`/`HANDOVERS` 移除，lease 只被该 lambda 引用，**无任何路径再释放**；
- `WorldWriteCoordinator.acquire/takeOver:80-122` 对该维度永久返回 null →
  该玩家在该维度再也无法开始新的方块写入，直到重启；
- journal 文件既未 `discardUnused` 也未登记 `PENDING_UNUSED`，现有 `retryUnusedJournal`
  看门狗（`WorldWriteCoordinator:288-312`）不会接手；
- 单线程队列会让同一卡死拖住其后所有日志 future，症状扩散到所有任务。

同一根因的另一半（有写入路径）：`WorldHistoryManager.attachPending:987` 在
`!capture.readyForRecovery().isDone()` 时直接返回，capture 永不物化，owner 永久 busy；
出问题的是同一个“无界等待”信号。

## 为什么不该顺手在 thenRun 外再加 try/catch

`handle(...)` 的吞异常是刻意的“I/O 已终止，可以释放”语义。异常在本链不可达，再包一层
捕获既不改变可达性，也会掩盖真正需要示警的卡死。噪声防御会把可诊断问题变成静默问题。

## 修复建议（未实施）

处置决定（主代理确认）：**日志 I/O 永不返回属于未实证的外部阻塞，不是已确认的程序缺陷，
因此现在不加入全局门闸或超时兜底。** 该项保留为运行限制与待实测项。同时确认：等待期间
**必须继续持有 lease** 是正确约束——在日志 I/O 仍未终止时释放租约，会让后继事务跨过可能
仍在追加的 WAL，与 `OperationManager:655-657` 的既有约束冲突。因此任何未来的修复都只能
缩短等待或显式升级，不能提前释放。

待实测触发条件与观测点：磁盘/网络盘阻塞、杀软长时间持锁、`IO_EXECUTOR`
（`PersistentRecoveryJournal:67-71`，单线程守护）线程卡死。观测点是该维度 `acquire`
持续返回 null 且 `PENDING_UNUSED` 为空。

若实测确认该阻塞可达，再按下列方案之一处理：

- **A（有界等待 + 显式升级）**：给无写入释放加 tick 期限。到期仍未完成时按“可能仍有 WAL
  在追加”处理：记 error、`PersistentRecoveryJournal.blockNewWrites()`，并**保留 lease**
  （不释放）。把“静默永久占用”变成“显式拒绝新写入 + 可诊断”。
- **B（复用现有机制）**：交接时就按 `PENDING_UNUSED` 风格登记 lease + journal，交给
  `retryUnusedJournal` 的既有重试与期限逻辑，使 lease 的命运不再取决于 future 是否完成。
- **C（不写代码的兜底）**：若产品接受“卡死需重启，由启动恢复读盘处理”，把该限制写入验收
  文档即可；当前代码即此状态。

## 限制

- 结论为源码静态核对（单线程 executor、无超时、无期限），**未实机验证**卡死的可达性与
  表现。
- 本次未修改任何 Java 文件；审计未覆盖 `WorldWriteCoordinator` 之外的租约语义。
- 已有回归覆盖“不提前释放、信号落定后释放”：
  `RecoveryHandoffBoundaryTest.theUnusedJournalCleanupKeepsTheLeaseUntilTheJournalSignalSettles`。
  “信号永不完成”这一缺口尚无测试，需要可控的卡死 executor 才能构造。
