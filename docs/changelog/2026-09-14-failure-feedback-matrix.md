# 失败提示矩阵（2026-09-14）

本矩阵记录当前代码中的失败反馈。提示文本以 `zh_cn.json` 为准。自动化证据不替代客户端或服务器实机验收。

## 普通放置

### 生成与快照

| 流程 | 阶段与触发条件 | 玩家提示 | 当前结果 | 玩家下一步 | 代码入口 |
|---|---|---|---|---|---|
| 普通放置 | 生成请求超过上限 | `placement_too_large` / `placement_exceeds_max`：放置量超过上限 | 取消本次任务，不写世界 | 缩小结构后重试 | `FastPlaceManager.submit`、`tick` |
| 普通放置 | 生成内存不足或生成异常 | `operation_memory_unsafe` / `placement_generation_failed`：说明内存或生成失败 | 释放预约，不开始写入 | 缩小结构或增加内存后重试 | `FastPlaceManager.submitGenerated`、`tick` |
| 普通放置 | 首写前快照无法安全保存 | `placement_snapshot_validation_failed`：目标数据无法安全保存，未开始写入 | 删除任务或交给恢复边界，不写目标 | 检查目标方块和日志后重试 | `FastPlaceManager.tick`、`PlacementTask` |

### 写入与提交

| 流程 | 阶段与触发条件 | 玩家提示 | 当前结果 | 玩家下一步 | 代码入口 |
|---|---|---|---|---|---|
| 普通放置 | 首写或后续写入异常 | `placement_failed_rollback` / `placement_failed_no_recovery`：显示阶段、操作 ID 和恢复结果 | 有安全日志时创建恢复任务，否则锁定风险路径 | 等待恢复；无恢复任务时检查日志和备份 | `FastPlaceManager.settleFailedTask` |
| 普通放置 | 提交历史失败，但世界写入已完成 | `placement_history_unavailable`：方块已写入，但撤回记录不可用 | 保留恢复日志，不提供本次撤回 | 记录操作 ID，并检查日志 | `FastPlaceManager.placementFailureStatus` |

## 工作区

### 本地准备

| 流程 | 阶段与触发条件 | 玩家提示 | 当前结果 | 玩家下一步 | 代码入口 |
|---|---|---|---|---|---|
| 工作区 | 本地草稿为空、编辑中或连接不支持 | `operation_submit_invalid` / `operation_submit_unavailable` | 不发送请求，保留工作区 | 完成编辑或检查连接后重试 | `ClientOperationController.submitWorkspace` |
| 工作区 | 本地序列化或临时存储失败 | `operation_submit_storage_failed`：无法准备操作数据 | 清除请求 owner，解锁并保留工作区 | 检查客户端日志和文件权限后重试 | `ClientOperationController.submitWorkspace` |
| 工作区 | 请求 owner 变化或已有提交 | `operation_submit_state_changed` / `operation_submit_pending` | 拒绝重复提交，不改变当前请求 | 等待当前结果或超时 | `ClientRequestTracker`、`ClientOperationController` |

### 服务端结果

| 流程 | 阶段与触发条件 | 玩家提示 | 当前结果 | 玩家下一步 | 代码入口 |
|---|---|---|---|---|---|
| 工作区 | 服务端拒绝或报告冲突坐标 | `operation_submit_rejected`：部分方块冲突，选区保留 | 解锁工作区，只隐藏失败目标的虚影和命中 | 修改冲突位置后重试 | `ClientOperationController.handleWorkspaceResult` |
| 工作区 | 30 秒没有匹配回执 | `operation_submit_timeout`：工作区保留 | 清除旧请求 owner，解锁草稿；迟到回执失效 | 检查服务器后重新提交 | `ClientOperationController.tick` |

## 选区与历史

### 选区操作

| 流程 | 阶段与触发条件 | 玩家提示 | 当前结果 | 玩家下一步 | 代码入口 |
|---|---|---|---|---|---|
| 选区操作 | 选区未完成、为空、过大或内存不足 | `operation_need_points` / `operation_empty` / `operation_too_large` / `operation_memory_unsafe` | 不开始扫描或写入 | 完成或缩小选区后重试 | `OperationManager.execute` |
| 选区操作 | 扫描、首写、后续写入或提交失败 | `operation_failed_rollback` / `operation_failed_no_recovery`：显示阶段、操作 ID 和恢复结果 | 有安全日志时回滚；否则停止并保留诊断信息 | 等待恢复，或按操作 ID 检查日志 | `OperationManager.tickTask` |

### 选区调整

| 流程 | 阶段与触发条件 | 玩家提示 | 当前结果 | 玩家下一步 | 代码入口 |
|---|---|---|---|---|---|
| 选区调整 | 源方块与保存的映射不同 | `operation_source_changed`：源方块已变化，重新框选以再次读取 | 不开始拖动或点调整，保留保存的源快照 | 重新框选该区域 | `ClientOperationController.aabbAdjustDecision`、`FastPlaceClientInput.beginWorkspaceFaceDrag` |
| 选区调整 | 没有可调整的选区或参数无效 | `operation_adjust_unavailable`：当前没有可调整的选区 | 不建立编辑，不改变选区 | 选中一个世界来源的方盒部件 | `ClientOperationController.adjustActiveAabbPoint` |
| 选区调整 | 已有提交未结束 | `operation_submit_pending`：等待结果或超时 | 不建立编辑 | 等待当前提交结束 | `ClientOperationController.aabbAdjustDecision` |
| 选区调整 | 客户端 level 不可用 | 无提示，记录一次警告 | 不建立编辑 | 检查客户端日志 | `ClientOperationController.reportMissingLevel` |

### 撤回与重做

| 流程 | 阶段与触发条件 | 玩家提示 | 当前结果 | 玩家下一步 | 代码入口 |
|---|---|---|---|---|---|
| 撤回/重做 | 没有历史或另一个任务占用 | `history_no_undo` / `history_no_redo` / `history_wait_task` | 不改变世界和历史顺序 | 完成当前任务，或先执行新操作 | `WorldHistoryManager.startHistoryTask` |
| 撤回/重做 | 世界已被外部修改 | `history_conflict`：停止且不覆盖外部修改 | 保留外部修改，停止当前批次 | 检查冲突位置后决定新操作 | `WorldHistoryManager.HistoryTask.tick` |
| 撤回/重做 | 维度未加载或写入 owner 忙碌 | `history_dimension_failed` / `world_write_waiting` | 暂停任务，不丢弃批次 | 等待维度加载或当前写入结束 | `WorldHistoryManager`、`WorldWriteCoordinator` |

## 生命周期与存储

### 生命周期

| 流程 | 阶段与触发条件 | 玩家提示 | 当前结果 | 玩家下一步 | 代码入口 |
|---|---|---|---|---|---|
| 恢复 | 启动日志损坏、日志无法写入或回滚暂时失败 | `recovery_journal_blocked` / `recovery_journal_failed` / `restore_failed_retry` | 阻止不安全的新写入，保留可恢复数据并重试 | 检查服务器日志、磁盘和文件权限 | `PersistentRecoveryJournal`、`WorldHistoryManager` |
| 取消 | Q 在写入前、写入后或恢复期间取消 | `task_cancelled_before_write` / `restore_cancelled_task` / `task_recovery_blocked` / `quit` | 写入前直接结束；写入后转入恢复；恢复受阻时保留日志 | 等待恢复完成；受阻时检查服务器日志 | `ServerInputDispatcher.quit`、`FastPlaceManager.quit` |
| 断线与重启 | 断线后存在可恢复草稿 | 重连后显示 `reconnect_restore_prompt`：Enter 恢复，Q 放弃 | 暂存持久数据，不恢复按键、拖拽或旧请求 | 连接同一服务器后选择恢复或放弃 | `ClientSessionManager`、`ClientOperationController` |

### 存储

| 流程 | 阶段与触发条件 | 玩家提示 | 当前结果 | 玩家下一步 | 代码入口 |
|---|---|---|---|---|---|
| 客户端草稿 | 保存失败、文件损坏或无法删除 | `operation_draft_save_failed` / `operation_draft_corrupt` / `operation_draft_delete_failed` | 保存失败不伪装为成功；损坏文件不进入恢复；删除失败说明下次可能再次提示 | 检查客户端日志和文件权限，再创建或清理草稿 | `ClientSessionManager`、`OperationClipboardStore.loadStrict` |
| 历史存储 | 历史落盘失败或稍后恢复 | `history_save_retry` / `history_save_recovered` | 世界修改保留，后台继续保存；成功后通知玩家 | 保存失败时检查磁盘空间和权限 | `WorldHistoryManager.flushDirtyHistories` |

## 实机验收边界

- 在 `.233` 分别触发普通放置、工作区、选区和撤回/重做的失败路径。
- 对每条提示保存截图，并记录触发阶段、操作 ID、世界结果和可重试状态。
- 断线界面由 Minecraft 显示。FastFormer 在重连后负责恢复提示和草稿状态。
- 结束客户端进程属于最终生命周期验收，不能由单元测试代替。
