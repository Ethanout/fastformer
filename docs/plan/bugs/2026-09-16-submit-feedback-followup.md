# 提交反馈与双层渲染复核

## 已修复：不可重试失败的提示与状态矛盾

`ClientOperationController.applyWorkspaceResult` 在 `retryable=false` 时清空工作区，却显示“选区已保留，可修改后重试”。玩家无法按提示继续操作。

该分支改用独立的 `operation_submit_discarded` 中英文提示，说明工作区已清空，需要重新选择。可重试分支继续保留工作区和原提示。没有修改提交、清理或重试行为。

## 双层渲染优化仍未完成

本轮尝试把双层面渲染改为两个独立 FaceBuffer，复核后撤销。两个缓冲只能分别按相机排序，不能证明等价于原来的同一批次。此前报告关于保持两层调用顺序即可保持透明混合结果的建议不充分。

后续方案：缓存两层合并后的面顶点，保留各层颜色和透明度，统一按相机排序。透明度随呼吸变化时，需要避免重建全部几何。完成后必须实机检查跨层遮挡、相机移动与层数切换。

## 验证范围

提示修复后运行 `gradlew test --tests '*ClientOperationControllerTest' --tests '*HoverTextLanguageTest' --console=plain`，结果为 BUILD SUCCESSFUL。该命令同时完成 Java 编译和资源处理。测试不证明新提示的实机显示，也不覆盖 GL 透明混合。
