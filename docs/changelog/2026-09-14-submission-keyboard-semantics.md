# Enter 提交语义边界（2026-09-14）

`FastPlaceClientInput` 现在只把 Enter 与小键盘 Enter 转换为确认命令。

`SubmissionKeyboardSemantics` 根据提交阶段、候选提交资格和近距离原版目标决定是否接受，并返回明确的拒绝原因。既有 building、operation、geometry 的提交分支、取消手势、请求令牌和网络包顺序保持不变。

定向测试：

```powershell
.\gradlew.bat test --tests io.github.fastformer.client.input.SubmissionKeyboardSemanticsTest --tests io.github.fastformer.client.input.WorkspaceKeyboardSemanticsTest --tests io.github.fastformer.client.input.ClientInputStateMachineTest --no-daemon --console=plain
```

鼠标按键和拖拽、滚轮及 Alt 手势仍由 `FastPlaceClientInput` 协调。
