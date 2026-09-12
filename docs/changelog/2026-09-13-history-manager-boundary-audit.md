# 2026-09-13 WorldHistoryManager 职责边界审计

## 观察

`WorldHistoryManager.java` 当前约 1,896 行，职责可按现有方法边界分成四组：

1. 记录与批次物化（`record`、`commitPreparedOperation`、`addBatch`）。
2. undo/redo、回退任务和恢复交接（`request`、`startRollback`、`acceptStoppedTask`、`attachPending`）。
3. 磁盘持久化、索引调度和失败重试（`scheduleNewBatch`、`scheduleIndex`、`retryPersistence`、`trackPersistence`）。
4. owner 生命周期、缓存淘汰和测试辅助入口（`remove`、`detachOwner`、`pruneDetachedOwners` 及 `*ForTest`）。

这些边界已有 `WorldTaskContext`、`HistoryStore` 和 `PersistentRecoveryJournal` 作为协作对象。当前审计没有发现可以在不改变 owner 锁、pending 队列或恢复顺序的情况下直接拆分的重复实现；因此暂不引入转发层或通用状态机。后续拆分应以具体故障（例如持久化失败重试或恢复交接）为触发，并保持每个 owner 的活动数据不可被淘汰。

## 验证边界

现有历史、恢复和 owner 生命周期测试覆盖上述关键路径。文件长度本身不是缺陷证据；真实职责拆分仍需在发现可复现行为问题后进行，并由 `current_todo.md` 的历史自动化和实机条目分别验收。
