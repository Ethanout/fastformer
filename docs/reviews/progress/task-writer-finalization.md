# 大型写入任务与测试注入收口（2026-11-15）

本阶段拆分 PlacementTask／SelectionOperationTask 的真实职责，保留 LeasedJournaledTask 对任务终止、写入租约、日志、内存与恢复交接的统一所有权。

## 职责划分

- `PlacementGeneration` 管理异步和延迟生成、进度发布，以及生成预算从工作线程转入任务或转交完成清理的过程。取消会解除任务对结果的观察；正在运行的工作线程退出前仍保留原预算，晚失败不会污染已取消任务。
- `PlacementJournalPrediction` 生成惰性的日志 after 视图。日志编码器消费时才映射快照，避免另建完整列表。放置、选区日志预测和工作区都使用此能力。
- `PlacementBlockWriter` 执行单格放置。世界回调前先保存 before，成功或部分失败后保存实际 after；冲突、KEEP_EXISTING、回调失败和邻居刷新沿原有顺序执行。
- `SelectionPlacementPlan` 管理有界选区扫描游标、重复目标游标、原点跳过、位移和目标遍历。它不读取世界、不推进日志、不拥有事务资源。写入任务使用同一计划计算验证、重叠索引和日志预测目标。
- `WorkspaceSnapshotCapture` 管理已组合目标的捕获游标与 NBT 估算。预算争用时保留已捕获快照和游标，恢复后先重新准入，不再次读取同一格或累加其 NBT。

PlacementTask 从 994 行降为约 763 行，SelectionOperationTask 从 860 行降为约 754 行。server.task.block 保持 12 个顶层类型，没有通过扩大单包或新建通用总入口绕过限制。

## 真实注入边界

`BlockSnapshotReader` 是快照读取与空气查询的构造依赖，默认使用真实 ServerLevel。`BlockTaskMemoryPolicy` 接收按实际保留数据计算的 MemoryAdmission，默认保持原准入结果；可施加所属任务的预算限制。三个任务的现有公开构造保留，注入构造位于任务包内。

testSupport 的 WorldTaskFixtures 通过构造传入读取器和策略，不修改运行任务的私有字段。IncrementalMemoryContentionTest 直接推进生产捕获、扫描和提交阶段；恢复测试通过空快照结果或明确的硬拒绝策略产生真实失败，再检查已有写入被交给恢复。

以下反射已移除：

- PlacementTaskTest 不再读取私有 future。它检查取消后的真实 tick 返回 CANCELLED，同时保留工作线程未被取消、预算不提前释放、晚失败不影响任务等行为断言。
- IncrementalMemoryContentionTest 不再设置 desired、captureIterator、phase、source、blockEntityReserve 或 commitBlockEntityReserve，也不反射调用私有方法。增长争用使用真实的大 NBT 快照，硬拒绝使用构造注入的准入结果。
- TaskRecoveryHandoffTest 不再设置 failed／memoryUnsafe。它先保留已有 before／after，再通过实际 validateSnapshots 触发快照失败或硬拒绝，最后推进队列并验证恢复数据。

生产已使用的阶段方法允许同包协作和验证，没有新增 ForTesting 类型、任意状态 setter 或第二份测试实现。

## 验证

使用 JDK 21 和缓存依赖隔离编译任务生产闭包、testSupport 与 11 类定向测试。首次运行定位并补齐 SelectionJournalPrediction 对迁出惰性映射函数的调用；修复后 **61 项，61 成功、0 失败、0 跳过**。

覆盖生成成功／失败／取消、生成预算交接、内存争用重试、三个写入任务的提交准入与释放、恢复交接等待日志追加／压缩、统一任务生命周期，以及只读提交事件的顺序与去重。共享包迁移统一后，隔离 javac 再次完整通过，随后 61 项测试再次全部通过。真实世界写入与客户端验收仍需 GameTest／实测，不以直接 JUnit 替代。

未运行 Gradle，未暂存、提交、推送或修改架构 baseline。统一生产／测试／GameTest 编译及完整回归由主代理执行。
