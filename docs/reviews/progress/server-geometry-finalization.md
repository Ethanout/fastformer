# 服务端几何工作流的状态边界（2026-11-15）

本轮按完整重构手册第 1、2.1、3.2、3.4、4.1、5.2 和 8 节核对当前代码。只记录本轮改动，不替代主进度索引。

## 已落地

- common.geometry.workflow.GeometryWorkflow 只接收不可变视图，提供阶段、能力、生成计划、交互目标和预览计划查询。删除其可变会话动作和未使用的 status 默认方法。
- common.geometry.workflow.plan 的五个形状规划器由 ShapeWorkflow<V> 检查视图类型，不依赖 ServerPlayer、设置存储和服务端会话协调。生成计划先捕获不可变参数，后续草稿变更不影响延迟生成。
- server.geometry.workflow.GeometryWorkflowEditor<S> 在注册调用边界检查草稿的形状类型，再分派到五个专用编辑器。墙面、球、圆锥棱柱、复合形状和凸多面体编辑实现均不接收完整 GeometrySession，也不能调用其他形状的访问器。
- 点增删相关不变量属于 GeometryShapeState。GeometrySession 保留草稿身份、形状切换和回退协调；直接编辑专用状态时，也会清除失效的凸多面体控制点选择。
- 六个视图类型及查询注册归 common.geometry.workflow，纯规划器归其 plan 子包，服务端编辑器和捕获请求生命周期归 server.geometry.workflow。三个包分别有 8、6、9 个类型，均有职责说明；原 fastplace.workflow 已退役。
- GeometryWorkflows 和 GeometryWorkflowEditors 分别使用会拒绝重复项的冻结 Registry。查询装配检查全部内置模式，服务端装配检查每个共享规划都有编辑器。客户端只依赖查询接口。
- ServerModes 从几何编辑器注册构造服务端模式，不再另按枚举构造一份独立的装配清单。编辑器停放、恢复、取消和清理仍只登记一次。
- 圆锥编辑器负责打开时恢复底面偏好、切换底面模式后保存偏好。设置值和保存回调由 GeometrySystem 注入上下文，编辑器不依赖设置存储。凸多面体编辑器负责控制点选择清除及其手势条件；GeometrySystem 不再硬编码这两种模式的通用生命周期分支。

保留现有形状生成结果、三条面入口默认策略、TranslatedScan、协议字段和草稿请求水位规则。没有改动磁盘格式、未知数据处理和架构债务基线。

## 验证

新增 GeometryWorkflowOwnershipTest，覆盖注册全覆盖和不可变列表、错配工作流拒绝默认动作且不改变草稿、凸多面体选择清除的手势与所有权、专用状态编辑和回退使旧选择失效，以及注入的圆锥偏好生命周期与 gizmo 坐标系切换互不干扰。原依赖编辑实现的状态测试迁至该服务端工作流测试，纯状态测试不再依赖服务端实现。

新增 GeometryWorkflowGameTests.coneWorkflowRestoresAndPersistsItsOwnPlanePreference，覆盖保存偏好后打开、循环模式、取消后重开和草稿身份更新。

FastCtx 检索确认：五个具体编辑器均无 GeometrySession 参数和跨形状访问器；全源码树无旧 fastplace.workflow 引用和旧上下文、请求序列的路径引用。

本轮子代理没有运行 Gradle。定向单元测试、完整 check 和 GameTest 由主代理在共享工作区串行执行。测试通过前，不将新增用例写为已通过。

## 服务端全模式生命周期补充

ServerMode<S> 现在绑定真实的 ServerEditor<S> 仓库。快速起形、选区、五种几何编辑器、QuickReplace 和 SmartFill 均通过 mode.open 激活现有状态，并以该状态分派输入、预览、确认、取消、tick、detach 和维度变化钩子。几何模式共享一个 GeometrySession 仓库，以模式所有权过滤动作；无状态手持物品模式只使用当前玩家环境，不创建持久草稿。

ServerModes 从冻结注册的模式按仓库对象身份生成生命周期集合，保留 selection > geometry > quickshape 的活动优先级和 geometry > selection > quickshape 的确认优先级。取消编辑只结束活动草稿，停放草稿与任务继续保留。断线清理连接工具并保留 UUID 所有的编辑草稿；原有替换实体身份检查继续阻止旧实体清理新实体的工作流。换维事件先结束连接工具，再停放与恢复各维度草稿。玩家正常 tick 进入模式钩子。

SessionSystem 统一关闭模式及仓库；所有模式先退役，关闭后保留的模式引用不能重新打开会话。持久草稿仓库清除全部在线、离线和停放状态；连接工具的实际状态仍由 PlayerStateRegistry 的 scope 在关闭时清理。没有新增第二份玩家状态表。四个纯选区会话类型移入 common.selection.session，冲突策略枚举移入 common.workspace.model，算法正文和取值保持不变。

新增 ServerModeLifecycleTest 验证同一状态对象经过完整动作链、共享仓库的所有权过滤、无状态环境不占编辑活动，以及退役后拒绝打开和停止分派。SessionSystemTest 覆盖真实仓库绑定、停放离线草稿关闭、所有几何模式的晚到打开请求拒绝；SessionBoundaryGameTests 使用 mode.open 验证形状路由、优先级、换维与断线保留。QuickReplace、SmartFill GameTest 断言绑定实际 scoped 状态并在模式 detach 时清除临时所有权。本子代理未运行 Gradle，测试结果由主代理串行验证后登记。

## 仍保留的领域入口

旋转、椭圆和尺寸等专用命令仍由 GeometrySystem 验证并调用专用形状状态。纯算法分包与依赖闭包迁移由协作代理完成，其范围和验证另见主进度。

GeometrySession.canUndoStep() 仍按原契约允许空草稿进入回退入口，由会话系统取消空编辑器。它与选区快照回退的差异没有被擅自合并。

## 选区指针绑定资源化

BindingInputController 在物理回调冻结工具身份与能力、工作区目标、世界采样位置、远端选区身份、版本、回调作用域和拖拽坐标系。default/selection.json 的 73 条规则决定草稿、点、面、手柄、远端点与短按回退的优先级；binding.tool.SelectionInputController 只通过窄 Host 匹配资源与清理松开，原子命令进入现有邮箱后由 OperationDragController 执行。SelectionInputDispatcher 和原 queueSelectionPointer、queueSelectionDraft、selectionPressRoute 已删除；ClientPhysicalInputDispatcher 的鼠标与原版交互入口使用同一份按下处理结果，不再保留选区按钮后备树。

逻辑左、右、中键与实际捕获键分离，额外鼠标键可以复用同一个点编辑或拖拽动作。工具以 SelectionTool.id 与能力参与事实冻结，同一种几何可以使用多个工具。资源重载使排队操作失效，并取消活动选区编辑；松开规则禁用时仍执行捕获清理。远端点、点选择及插边发送前核对拥有者、选区身份、版本和回调作用域。排队的拖拽按下使用同样的严格校验，已开始的拖拽则以指针 token、拥有者和回调作用域结束，允许自身操作推进预览版本。受理新原子动作时，先在队列中取消两种旧捕获；原版放行、阻止和禁用规则均不触发该取消。

DefaultSelectionBindingsTest 读取真实资源，分组遍历本地草稿／工作区、世界采样和远端指针组合，并覆盖禁用子树、原版放行、额外鼠标键、自定义工具身份和动作时间参数。现有邮箱测试新增重载取消、实际键配对、远端版本变化拒绝、跨类型拖拽取消顺序，以及逻辑右键在拖拽状态复制中保留的用例。

主代理统一执行的 k 轮完整 check 与全新世界 GameTest 已通过：503 个测试套件、2736 项单元测试，0 失败、0 错误、1 项跳过；326 项必需 GameTest 全部通过。新增架构债务为零，现存债务为 368 项；严格架构检查仍失败，不能将本阶段写成整份架构手册已完成。
