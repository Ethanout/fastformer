# 服务端事务修复复核

## SR-1：短事务交接失败没有保留恢复数据

状态：已确认代码缺口，待修复与故障路径验证。

`ShortWriteTransaction.fail` 在恢复失败且 `WorldHistoryManager.startRollback` 返回 false 时，只记录日志并返回 `RECOVERY_PENDING`。`before` 与 `after` 是调用栈局部数据，没有交给任何持久对象。写入租约仍保留，但恢复快照在返回后失去所有者。注释声称“保留快照”，当前实现没有做到。

修复要求：失败交接必须进入明确持有快照、维度和事务身份的恢复队列；只有接收者成功接管后才返回恢复等待。重试与服务器关闭必须有明确所有者，不能仅靠锁阻止新写入，也不能释放锁并丢弃恢复数据。

## SR-2：异步未使用日志清理绕过租约代次

状态：已确认接口缺口，需复核调用链并测试迟到回调。

`WorldWriteCoordinator.releaseAfterUnusedJournal` 的 future 回调调用 `releaseCurrentLease(server, dimension, owner)`。该接口只核对玩家身份，没有核对发起清理的租约代次。同一玩家的后继租约可能被旧回调释放。`PendingUnusedJournal` 同样只保存 owner。

修复要求：日志清理及失败重试持有原始 Lease，以精确租约释放与登记；旧清理不得登记到新租约或释放后继事务。迁移全部调用方，并覆盖迟到 future 与接管后清理的测试。

## 已核对的当前状态

- `PlayerLifecycleEvents.onPlayerChangedDimension` 当前调用 `FastPlaceManager.handleDimensionChange`，已不再调用 `quit`。工作区审计报告中的相反描述是旧状态。
- 当前完整测试执行 1157 项，7 项失败；服务端相关测试未报告失败。这不能证明上述未覆盖异常路径正确。
- 尚未完成游戏内与真实恢复故障验收。
