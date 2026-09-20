# 工作区键盘语义边界（2026-09-14）

`FastPlaceClientInput` 现在只把 Ctrl+C/V/A、删除和 Ctrl+Z 的物理按键转换为 `WorkspaceKeyboardSemantics.Command`。

`WorkspaceKeyboardSemantics` 根据输入阶段路由和工作区活动状态接受或拒绝命令，并返回 `PHASE_BLOCKED` 或 `WORKSPACE_INACTIVE` 原因。入口只执行已接受的复制、粘贴、选择、删除和撤销命令。

定向测试：

```powershell
.\gradlew.bat test --tests io.github.fastformer.client.input.WorkspaceKeyboardSemanticsTest --tests io.github.fastformer.client.input.ClientInputStateMachineTest --tests io.github.fastformer.client.input.OperationInputSemanticsTest --no-daemon --console=plain
```

该命令在 2026-09-14 通过。

剩余边界：鼠标按键和拖拽、滚轮、Alt 手势及 Enter 提交仍由 `FastPlaceClientInput` 做物理事件协调。后续拆分必须保留原版让行、手势令牌和网络包时序。
