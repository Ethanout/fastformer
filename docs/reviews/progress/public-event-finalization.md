# 对外只读事件（2026-11-15）

对应重构手册第 7 节。`common.api` 提供三个不可取消的 NeoForge 事件。它们只携带 UUID、资源 ID、计数和不可变枚举／ModeId，不暴露 ServerPlayer、会话、世界、HistoryEntry、NBT 或可变集合。

## 发布语义

- `HistoryRecorded` 表示新的撤回记录已被接纳。普通短操作在内存接纳并提交异步保存后发布 `QUEUED`；此状态不保证磁盘完成。日志事务在批次和索引都成功持久化、内存目录已更新后发布 `DURABLE`。排队保存完成或重试不会再发布同一记录，历史压缩、加载和撤回／重做也不是新增记录。
- `ShapeCommitted` 只对应非空的放置／工作区写入任务。任务等待历史持久提交成功后发布；入队、预览、空任务、失败、取消及恢复交接均不发布。
- `ModeChanged` 表示一个编辑仓库的 active mode ID 发生变化。previous／current 为空表示该仓库没有活动会话。事件不表示持有物分类，也不把仅保留的跨维度草稿算作活动模式。payload 的 repository 区分快速起形、选区、几何、智能填充和快速替换。

PlayerSessions 在已有玩家槽内保存上次发布的不可变 ModeId。activate／end／park／restore／discard／clear 在仓库实际变化后比较 ID，不增加第二张玩家表。同一个 GeometrySession 先改 mode 再激活时仍能识别变化；无变化 cancel、重复激活和恢复冲突不发布。跨维度 park／restore 分别表示退出源维度活动模式和恢复目标维度活动模式。

临时模式沿用连接 Scope 状态。SmartFill／QuickReplace 在真实激活和结束处发布，保留现有取消、切维度和断线清理入口。

## 订阅生命周期

每个 SessionSystem 的 Scope 持有独立 ServerEventStream。`SessionSystem.events()` 返回仅能订阅的 EventSource；订阅返回可幂等 close 的 Subscription。服务器关闭清理订阅，旧 WorldTaskContext 捕获的事件流不会转投重启后的服务器。withResume 保留原事件流，测试可以通过新增构造参数注入独立流；原构造仍可用。

```java
EventSource.Subscription subscription = SessionSystem.of(server).events().subscribe(
   ShapeCommitted.class, event -> log(event.operationId(), event.changedBlocks()));
// Release earlier when the extension's own scope ends.
subscription.close();
```

每个实例监听器独立捕获异常，然后桥接到 NeoForge.EVENT_BUS。总线回调异常同样在发布边界隔离，不改变已经接受的编辑结果。NeoForge 自身如何继续处理同一次 post 的后续监听器由其总线实现决定；需要彼此独立的回调隔离时使用实例 EventSource。监听器应只读取通知，不递归驱动任务执行。

## 验证状态

事件生产闭包和 4 个新增测试类使用 JDK 21 与现有依赖隔离 javac 通过；JUnit Platform 直接执行 **12 项，12 成功、0 失败、0 跳过**。测试覆盖独立监听器与总线异常、订阅清理及移除、resume 的流归属、同一可变几何对象切模式、park／restore／discard 去重、批次和索引都成功才报 DURABLE、失败不报、QUEUED 非持久语义，以及 OperationTasks 完成持久提交后只发布一次。

OperationCommittedEventTest 通过公开 WorldTask 边界提供已完成写入者，实际推进 OperationTasks 和 HistoryService 的持久提交。回调必须看到队列已移除、批次状态已释放；测试回调再次调用 service 也不会重复发布。EMPTY 和恢复交接不发布 ShapeCommitted。此测试不替代真实世界写入或客户端验收。

统一日志 f 的 main／test／GameTest 编译和严格架构检查通过，495 项既有架构债务无新增。该窗口之后新增了回调顺序改进及上述 4 类测试，仍需主代理整批 Gradle 回归。未暂存、提交、推送或修改架构 baseline。
