# 测试运行目录与类路径同步边界

## 已确认问题

`prepareAsciiTestRuntime` 使用 `Sync` 同步测试类路径，但目标目录还包含测试工作目录 `work`。同步默认删除源中不存在的文件，因此会尝试删除 Minecraft 日志。日志被持有时，任务在运行测试前失败。

## 修复

同步任务保留 `work/**`，继续同步并清除过期的 main、test、minecraft 类路径文件。工作目录和构建输出的生命周期分别管理。

## 本轮验证

- 首次测试在删除旧 `work/logs/debug.log` 时失败，尚未执行测试。
- 改用项目下临时目录后触发非 ASCII 路径类加载限制，不能用作功能测试结论。
- 改用独立英文临时目录后执行 1198 项测试：2 项失败，1 项跳过。失败集中在 `ClientOperationWorkspaceTest` 的 no-op 与历史记账断言，已交给历史审计代理修复。
- 修改同步规则后，在原默认临时目录运行 `gradlew test --tests '*VoxelRotationFrameTest' --tests '*RotationFrameContractTest' --tests '*WorkspaceSelectionBoundsTest' --console=plain` 成功。同步任务不再因旧日志占用而失败，三个旋转测试类通过。

自动测试不替代客户端实机验收。当前仍保留旋转、源遮罩、剔面和输入模式的验收项目。
