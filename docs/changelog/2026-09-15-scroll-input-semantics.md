# 滚轮输入语义边界（2026-09-15）

`FastPlaceClientInput` 现在先将滚轮事件映射为 `ScrollInputSemantics.Command`，再执行该命令。

`ScrollInputSemantics` 保留原有优先级：阻塞阶段取消事件，活动工作区先处理移动，其他会话再处理候选滚动。Alt 滚轮让给原版，同时消耗本次修饰键手势。准星命中原版方块时，候选滚动不发送网络包。

定向测试：

```powershell
.\gradlew.bat test --tests io.github.fastformer.client.input.ScrollInputSemanticsTest --tests io.github.fastformer.client.input.OperationInputSemanticsTest --tests io.github.fastformer.client.input.BuildingInputSemanticsTest --tests io.github.fastformer.client.input.WorkspaceKeyboardSemanticsTest --tests io.github.fastformer.client.input.SubmissionKeyboardSemanticsTest --no-daemon --console=plain
```

该命令在 2026-09-15 通过。

剩余边界：鼠标按键和拖拽、Alt 手势仍由 `FastPlaceClientInput` 协调。后续拆分必须保留原版让行、手势 generation/token 和网络包时序。
