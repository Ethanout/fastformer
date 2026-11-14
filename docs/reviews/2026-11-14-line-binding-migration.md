# 起形线阶段绑定迁移（2026-11-14）

## 本批范围

- `bindings/default/quick_shape_line.json` 接管活动线阶段的鼠标按下／松开及滚轮，包括让给原版和无操作的终止分支。右键重合提交、中键设置和左键短按撤点保持现有结果。
- `QuickShapeLineContext` 冻结原版指针归属、候选重合、通道、中键设置、鼠标捕获和滚轮事实。纯 `QuickShapeLineBindingInput` 不读取 Minecraft 或全局会话，只调用明确的命令端口。
- 输入适配器复用现有点按队列、`QuickShapeUndoGesture` 和滚轮队列；撤点的 250ms 窗口、版本／作用域校验仍由原能力拥有。
- 一旦归属线阶段，即使没有规则或用户放行原版，也不会落回旧鼠标捕获分支。按下后已切到下一阶段的释放，继续由原捕获状态收尾。
- 原版交互回调复用本次物理按下的吞入／放行结果，不再由旧起形分支二次判定。没有物理鼠标按下时，只用同一绑定的无业务副作用探测。
- 物理松开先执行当前释放绑定；绑定放行或缺失时，只取消残留捕获，不补做撤点。资源重载取消捕获，并丢弃尚未派发的旧线阶段指针、撤点和滚轮命令。
- KEY 未迁移：Q 的按下捕获、取消与丢弃原版 click，Alt／Ctrl 的物理转移和径向菜单，Ctrl+Z/Y 与 Enter 的冻结提交仍是共享生命周期。该批不能算完整线阶段操作树完成。

## 接入点

- 鼠标：几何路径捕获之后，旧 `QuickShapeMouseInputController.capture` 之前，调用 `captureLine`；`owns` 后始终返回，只在 `consumesVanilla` 时取消原版。
- 滚轮：填充所有权之后，旧模式滚轮决策之前，调用 `scrollLine`，按同一结果契约返回。
- 原版交互回调：工具所有权与空会话早退之后，旧起形分支之前，调用 `interactionLine`；`owns` 后始终返回，只在 `consumesVanilla` 时取消原版。该入口只查询归属，不重复执行鼠标命令。
- 后续 Enter 应把“捕获的提交快照 → 清理当前手势 → begin 提交意图”封装成原子端口。Q 应复用捕获的取消决定及既有清理；不能通过重新调用整个旧键盘处理器来伪装迁移。

## 验证

- `DefaultQuickShapeLineBindingsTest` 加载实际 JSON，对照全部冻结事实组合的鼠标／滚轮默认行为，并覆盖空规则／用户放行不回退、非线阶段不派发、KEY／交互不冒用及同帧重载。
- `QuickShapeLineInputStateTest` 覆盖物理按下归属和代次失效。`QuickShapeUndoMailboxTest` 使用实际默认绑定，对照默认撤点、放行释放和缺失释放，并检查重载后已排队撤点不会执行。
- 本子任务未运行 Gradle。主任务定向运行 `test --tests "*DefaultQuickShapeLineBindingsTest" --tests "*QuickShapeUndoMailboxTest" --tests "*QuickShapeButtonCaptureTest" --tests "*DefaultToolBindingsTest" --tests "*BindingSystemTest" verifySourceLayout`，随后统一架构检查与整批回归。
