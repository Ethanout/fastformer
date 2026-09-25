# 快速起形实现索引

行为以 [02-快速起形](02-快速起形.md) 和 [00-状态机](00-状态机.md) 为准。本页只说明代码归属，不增加交互规则。

## 数据与规则

| 伪代码概念 | 实现 | 责任 |
| --- | --- | --- |
| 已确认点、可编辑偏移、逐点回退 | `fastplace.quickshape.QuickShapeDraft` | 保存草稿，修改和撤回已确认点 |
| 确认点、闭合、回退事件 | `fastplace.quickshape.QuickShapeWorkflow` | 只读计算阶段决策，返回预览、提交、结束或拒绝结果 |
| 点、线、面、体 | `fastplace.quickshape.QuickShapeDraft.stage`、`QuickShapeStage` | 草稿集中根据点数和多边面闭合状态计算阶段 |
| 各阶段的输入模式 | `fastplace.quickshape.QuickShapeMode`、`QuickShapeModeRules` | 声明模式归属，校验和循环选择模式 |
| 多边面中键无操作 | `fastplace.quickshape.QuickShapeInputRules` | 客户端与服务端共用的输入规则 |
| Enter 默认提交已确认点，线滚轮模式保留候选 | `fastplace.quickshape.QuickShapeSubmissionPoints` | 固定本次提交使用的点 |
| 按键时固定提交数据 | `client.quickshape.QuickShapeSubmissionSnapshot` | 保存本次输入对应的数据和身份 |
| 计算等待、重复提交、取消未发送意图 | `client.quickshape.QuickShapeSubmissionIntent` | 管理一次提交的计算结果和有效性 |
| Enter 提交入口与结果发送 | `client.quickshape.QuickShapeSubmissionController` | 接收输入快照，检查和推进提交 |
| 鼠标物理事件邮箱 | `client.input.PhysicalInputMailbox` | 按客户端 tick 顺序保存鼠标、键盘和滚轮事件；回调不执行会话副作用 |
| 几何设点与闭合输入 | `client.input.GeometryInputController`、`GeometryPointerSequence` | 捕获按钮、视线、修饰键、请求号、revision 和 scope，再在 tick 中派发设点、闭合和回退 |
| 特殊形状 Gizmo 捕获 | `client.input.GeometryGizmoCapture`、`GeometryDragController` | 绑定按下与释放的按钮、revision、scope、draft ID；过期释放只能清理自身捕获 |

查找“线阶段 Enter”时，先看 `QuickShapeSubmissionPoints.capture`，再看 `QuickShapeSubmissionController.submit`。修改等待或取消时，再看 `QuickShapeSubmissionIntent`。

`QuickShapeStage.fromPointCount` 只描述点数对应的基础阶段。涉及多边面的交互与预览必须使用 `resolve`，使未闭合的多边面继续处于面阶段。

## 所有权边界

- `QuickShapeDraft` 是草稿数据，不代表玩家当前输入会话，也不拥有世界任务。
- `QuickShapeModeRules` 是模式规则，不执行会话切换，也不读取玩家设置对象。
- `QuickShapeWorkflow.onEvent` 处理服务端已解析的点和编辑事件，不读取玩家、网络或世界。`FastPlaceManager.dispatchQuickShape` 统一执行结果对应的预览同步、提交及结束动作。
- `PhysicalInputMailbox` 拥有待派发的物理事件；鼠标回调只负责归属判断、原版动作拦截和入队。事件在客户端 tick 开始时批量取走，新入队事件延迟到下一 tick。
- `GeometryGizmoCapture` 拥有客户端拖动捕获；Gizmo 拖动载荷携带 request ID、revision、callback scope 和 draft ID。几何设点与闭合载荷没有 draft ID，服务端按当前会话、scope、revision 和请求顺序判断。旧事件的会话替换边界仍需验收。
- 提交快照、提交意图与显示预览各自保留用途。提交不得重新读取后来的鼠标候选。
- 服务端继续通过现有管理入口持有草稿和执行世界任务。

## 尚未迁移

已完成快速起形类型归属和服务端草稿事件入口整理，尚未实现完整的五会话结构。

五类会话的目标归属如下：

| 会话 | 草稿与确认数据 | 输入阶段 | 世界任务 |
| --- | --- | --- | --- |
| 空闲 | 无草稿；只保留当前环境身份 | `ClientInputStateMachine.State.IDLE` | 无 |
| 快速起形 | `QuickShapeDraft`、提交快照 | `quickshape` 输入事件 | `FastPlaceManager` 的提交任务 |
| 特殊形状 | 几何专用草稿（待定） | `GeometryInputController` 捕获 | 几何生成任务 |
| 特殊物品 | 物品专用参数（待定） | 物品输入控制器 | 物品动作任务 |
| 选区操作 | 选区草稿、确认选择集和部件 | `SelectionPointerEvent` | `ClientOperationController` / 世界操作任务 |

这里的“会话”表示输入和数据所有权；世界任务的生命周期独立管理。选区退出不能隐式清空工作区，快速起形取消也不能代替服务端任务取消完成。

鼠标分派仍位于 `client.input`。顶层 `ClientInputStateMachine` 仍包含交互与任务等待状态。后续需要按 `00-状态机` 整理事件入口、阶段处理和统一切换，保留事件顺序、捕获身份及取消规则。操作面拖动和部分选区路径仍需继续验证。

特殊形状和选区的类型归属尚未迁移。伪代码中的待定行为保持待定。
