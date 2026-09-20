# Gizmo 可见性组件

## 行为与依据

选区伪代码仍将未选中时的 Gizmo 显示规则列为待定。本次保留现有显示行为：可编辑选区显示 Gizmo，锁定的变换部件只在选中时显示 Gizmo。

此前渲染隐藏未选中锁定部件的 Gizmo，命中路径却仍检测其手柄。本次让两条路径读取同一个可见性组件，避免不可见手柄截获输入。部件标签和边框仍可选择，选中后允许整体变换。

## 实现

- `InteractionVisibility` 表达始终显示、选中时显示和隐藏。组件保存显示策略，选择状态仍由会话持有。
- `SelectionGizmoInteraction` 在发布对象时设置显示策略。
- `OperationPreviewRenderer` 与 `WorkspaceInteractionResolver` 使用相同的可见性判断。
- `ClientSelectionSession` 在发布场景时清除已隐藏对象的 hover，并拒绝旧隐藏目标再次建立 hover。

## 验证与范围

2026-09-19 17:23 的完整 `./gradlew test check` 实际执行通过。

- 修改命中回归测试，覆盖未选中时无法命中、重新选中后可以命中，以及提交锁拒绝命中。
- 新增 hover 测试，覆盖取消选择后清理、拒绝旧 Gizmo 目标、标签仍可悬浮，以及重新选中后恢复同一对象身份。
- `InteractionHoverTest` 的 8 项测试和 `WorkspaceInteractionResolverTest` 的 17 项测试均通过，无跳过、失败或错误。

本次没有修改源遮罩、跨层剔面或服务端写入路径。客户端实际显示和连续拖动仍待验收。本次修改后的 GameTest 未重跑，17:08 的 85 项通过记录属于本次修改前的集成证据。
