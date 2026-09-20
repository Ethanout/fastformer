# 2026-09-14 超长文件职责审计

## 结论

本次审计检查 `FastPlaceClientPreviewCore`、`FastPlaceClientInput` 和 `WorldHistoryManager`。文件长度只用于定位审查范围，不作为缺陷证据。

三个文件都包含多个独立状态所有者。后续拆分必须先移动状态和测试，再移动调用入口。不得只按行数切分静态方法。

## FastPlaceClientPreviewCore

当前文件约 3800 行，包含以下职责：

- 接收 building、operation、geometry 和 activity 快照。
- 向输入层提供命中、候选点、Gizmo 和会话查询。
- 绘制 HUD、准星、悬浮文字和滚轮反馈。
- 生成 building 预览，并管理异步任务、CPU 缓存和 GPU 缓冲。
- 分派 building、geometry、operation 和 workspace 世界渲染。
- 清理断线、换世界和取消后的预览状态。

已消除的重复包括 building 缓存统一清理，以及 HUD 计时状态归入 `PreviewFeedbackState`。`BuildingRenderFrame` 也合并了稳定帧中的重复集合转换。

仍需拆分的边界：

1. `PreviewHudController` 持有 HUD 反馈、准星和文字布局。
2. `BuildingPreviewPipeline` 持有异步生成、版本和 building 缓存。
3. `PreviewWorldRenderer` 只分派各类世界渲染器。
4. `PreviewSessionLifecycle` 统一取消、断线和换世界清理。

未发现可以仅凭静态控制流证明的无效分支。渲染模式分支需要客户端等价验收，不能在审计阶段删除。

## FastPlaceClientInput

当前文件约 2300 行，包含以下职责：

- 接收键盘、鼠标、滚轮和原版交互事件。
- 决定 vanilla、building、geometry、operation 和 workspace 输入归属。
- 创建、更新、完成和取消多类拖拽手势。
- 发送网络命令，并维护请求 ID 和提交等待状态。
- 向渲染层暴露拖拽轴、步数、命中点和修饰键状态。
- 产生操作成功和失败提示。

左右键工作区命中分派已归入共同路径。仍有多组 `begin/update/finish/cancel` 手势流程，它们重复处理 owner、按钮、token 和清理顺序。

仍需拆分的边界：

1. `ClientInputRouter` 只把物理事件转换为语义事件。
2. `PointerGestureController` 持有 operation、workspace 和 geometry 手势生命周期。
3. `ClientRequestTracker` 持有 placement 和 workspace 请求 ID。
4. `ClientInteractionFeedback` 持有 actionbar 提示选择。该边界已完成初步拆分，复制、粘贴和提交结果不再由输入类选择提示键。

未发现可安全删除的输入分支。左右键、Alt、中键和按下/释放语义必须先由行为矩阵覆盖。

## WorldHistoryManager

当前文件约 1900 行，包含以下职责：

- 接收普通记录和预备提交记录。
- 管理每个玩家的 undo、redo、待记录和待恢复队列。
- 调度撤回、重做、回滚和恢复任务。
- 管理磁盘保存、索引、重试、淘汰和关闭等待。
- 管理世界写入许可、内存预约、journal 和取消状态。
- 发送进度和失败反馈。

多个 `record` 和 `startRollback` 重载把入口转换、owner 查找和任务创建集中在同一类。内部 `HistoryTask` 同时持有 journal、内存、写入许可、阶段转换和用户反馈。

仍需拆分的边界：

1. `WorldHistoryRepository` 持有磁盘批次、索引和异步保存。
2. `WorldHistoryCache` 持有 undo、redo、字节预算和淘汰。
3. `HistoryTaskScheduler` 持有 owner 队列和 tick 调度。
4. `HistoryRecoveryCoordinator` 持有 journal、写入许可和恢复交接。

当前未发现可以无条件删除的恢复分支。不同分支对应正常任务、取消、恢复转移和崩溃保留，必须先增加所有权测试。

## 执行顺序

先拆 `FastPlaceClientInput` 的请求和手势状态。然后拆 `FastPlaceClientPreviewCore` 的 HUD 与 building pipeline。最后拆 `WorldHistoryManager`，因为它的世界数据风险最高。

每次拆分必须通过原有自动化测试。还必须完成对应的客户端或服务器等价验收。
