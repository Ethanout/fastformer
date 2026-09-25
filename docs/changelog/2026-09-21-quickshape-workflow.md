# 快速起形草稿事件入口

设点、闭合和回退原先分别在 `FastPlaceManager` 中修改草稿、判断阶段和执行后续动作。现在这些规则进入 `fastplace.quickshape.QuickShapeWorkflow`。

- `ConfirmPoint` 保存不可变坐标，处理普通阶段推进、多边面闭合和高度确认。
- `ClosePath` 在点数或模式不符合要求时拒绝操作，不清除草稿偏移。
- `Undo` 逐点回退，删除最后一个点后返回结束草稿的结果。
- `FastPlaceManager.dispatchQuickShape` 根据结果同步预览、提交或结束草稿。候选计算和世界访问仍由管理器负责。
- 快速起形和已迁移的几何鼠标事件进入 `PhysicalInputMailbox`，在客户端 tick 中按序取走。鼠标回调不再同步排空队列，但 operation 专属分支及尚未迁移的路径仍需继续检查。
- 几何设点与闭合载荷固定 request ID、revision、callback scope 及按下时的视线；左键回退与 Gizmo 拖动载荷还携带 draft ID。迟到释放不能结束新捕获。

本次迁移保留现有行为，包括普通闭合路径的结束处理。特殊形状及伪代码中的待定规则不在本次修改范围内。

当时的验证：流程测试覆盖普通阶段、多边面、重复确认、无效闭合、回退和输入坐标固定；Gizmo、回退、指针序列及取消路径均有单元或 GameTest 覆盖。当时 `check`、`runGameTestServer` 与 `git diff --check` 通过。客户端实机交互未验证。

补充验证边界：操作面拖动已覆盖首包、连续增量、身份不匹配、完成后重放和会话替换；`QuickShapeDraft.stage` 已有阶段归属测试。客户端实机验收仍受当前环境的 CUA 窗口枚举认证错误影响，不能以自动化测试替代。

客户端原始鼠标回调的部分同步请求仍需迁移。当前工作未完成统一的 `enter / onEvent / tick / exit` 会话入口。操作载荷已增加 request ID、revision 和 callback scope 等身份字段，网络协议版本已随之更新。
