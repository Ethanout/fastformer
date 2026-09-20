# 历史持久化 generation 保护

## 问题

同一玩家的历史写入可以并行完成。较早的完整快照失败后，较新的写入可能先完成；如果回调只看 `pendingPersistence == 0`，旧成功回调会错误清除较新失败留下的 `persistenceDirty`，从而停止重试并误报恢复。

## 修改

`WorldHistoryManager.trackPersistence` 为每次持久化操作分配单调递增 generation。只有满足以下条件，成功回调才可以清除 dirty 状态：

- 当前写入是允许清理 dirty 的完整恢复快照；
- 当前没有其他待完成写入；
- 完成回调的 generation 仍是 owner 的最新 generation。

任何失败仍会保留 dirty 状态并安排重试。未持久化期间的内存历史继续跳过缓存裁剪。

## 验证

- `./gradlew test --tests io.github.fastformer.fastplace.world.WorldHistoryOwnerLifecycleTest --rerun-tasks`
- `./gradlew check`

两项命令均通过。该记录不替代真实磁盘故障和进程中断验收。
