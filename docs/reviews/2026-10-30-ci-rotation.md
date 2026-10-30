# GitHub 游戏测试修复（2026-10-30）

失败运行：[Build #40129408939](https://github.com/Ethanout/fastformer/actions/runs/40129408939)。唯一失败的必需测试是 `clipboardQuarterTurnsDoNotDriftWhenAxisWidthsHaveDifferentParity`：带平移、长宽奇偶性不同的剪贴板选区，连续四次旋转后没有回到原位置。

## 原因与修复

旋转以整体选区中心为轴。第一次旋转产生半格偏移时，实际方块必须取整到网格；后续快捷键却重新使用取整后的包围盒中心，导致偏移累积。

`WorkspaceSelectionBounds.continuousWholeBox` 现在为快捷键提供保留精确偏移的整体边界。它复用现有缩放、重复和旋转边界计算，再应用未取整的最终偏移。绘制和方块放置继续使用原来的网格边界。`SelectionShortcutCalculator` 统一使用此中心，旋转和镜像不再把取整误差反馈到下一次变换。

没有修改按键、方块状态转换、存储或网络格式。新增单元测试覆盖正负坐标、不同长宽奇偶性和整数／小数平移；保留原有游戏回归测试。

## 验证

- 修复前本地复现相同游戏测试失败。
- 修复后定向旋转游戏测试：8 项全部通过。
- `gradlew.bat --offline check`：通过。
- 全量 `runGameTestServer`：264 项必需测试全部通过。
