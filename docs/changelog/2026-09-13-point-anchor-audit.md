# 2026-09-13 `point1/point2` 输入锚点审计

本次为静态审计，不替代客户端和断线恢复验收。

在 `src/main` 中搜索 `point1/point2` 的直接读取后，唯一一处生产调用方是
`ClientOperationController.adjustSelectionFace(...)`：它从当前选区读取两个点，
作为新的 `OperationSelectionVolume` 输入锚点，同时用已派生的 `bounds` 计算调整后的
`min/max`。该传递保持点字段仅作为下一次点编辑的输入锚点，不参与渲染、命中、拖拽、
预览或提交判定。

`CuboidSelectionSession.pushPull/expand` 和 `OperationSelectionVolume.expandCuboidTo`
只改变派生范围；`OperationSelectionVolume.withCuboidPoint` 在点编辑时重新计算范围。
渲染器、交互解析器、预览合成、放置计划和服务端生产路径均未发现直接读取点字段。

因此当前没有可由静态证据支持的 BUG-08 删除或重构项。仍需客户端覆盖移动、旋转、
缩放、取消及重连恢复，确认所有行为都以 `min/max` 和部件模型为准。
