# 客户端架构闭包审计（2026-11-15）

本轮先读取编译报告和源码，再完成世界写入分包、选区绑定 Host 收口。本文是下一轮可实施清单，不是整份重构手册的完成声明。不修改债务基线，不用包说明或例外代替依赖拆解。

## 报告版本和证据

| 读取阶段 | 顶层类型 | 剩余债务 | 新增 | 分类 |
|---|---:|---:|---:|---|
| h/i 报告快照 | 1110 | 416 | 42 | cycle 182、超限包 150、缺包说明 43、旧后缀 14、单类型包 27 |
| j 报告快照 | 1108 | 372 | 2 | cycle 154、超限包 136、缺包说明 42、旧后缀 12、单类型包 28 |
| k 最终统一报告 | 1106 | 368 | 0 | cycle 154、超限包 134、缺包说明 42、旧后缀 11、单类型包 27 |

三份报告的 strict boundary 均为 0，layer、oversize-source、unmigrated-type 债务已归零。j 的两个新增分别是根 input 的 SelectionInputController 和 binding.action.selection 的单类型包；随后分别由本轮 Host 分工和命令枚举归属修复。k 验证无新增债务，完整 verifyArchitectureStrict 已实际运行，因 368 条既有债务失败。

主任务 k 轮完整 check 与新世界 GameTest 通过：503 个测试套件、2736 项单元测试，0 失败、0 错误、1 跳过；326 项必需 GameTest 通过。日志为 `.temp/refactor-final-20261115k.log`，新世界为 `build/gametest-refactor-20261115-k`。这些执行证据不覆盖真实客户端视觉和物理交互实测。

## 本轮已实施

世界写入的原 block 包在新增快照读取能力后达到 13 类，现拆为 snapshot 5 类、transaction 5 类、effect 3 类。事务拥有 refreshOwnedNeighbors；快照不再调用事务，只在回放时调用 effect 的副作用抑制。两个历史回放快照的完整类型名白名单、源检查、字节码规则、打包条目和测试包同步迁移，未扩大白名单或可见性。详见[世界写入迁移](world-layout-finalization.md)。

原根 input/SelectionInputController 混合物理取样、绑定匹配和队列执行，现按已有所有者分工：

| 所有者 | 职责和边界 |
|---|---|
| interaction.binding.tool.SelectionInputController | 绑定匹配、一次冻结、press 结果记录与 release 保底清理；通过 Host 接受事实和命令，不查询全局会话，不访问根 input 类型。 |
| input.BindingInputController | 冻结实际指针、工具、通道和 scope/revision；将选中的命令转成既有捕获和队列调用。 |
| input.OperationDragController | 消费 SelectionPointerEvent，校验按压身份，执行原子动作，按活动 capture 的 token/owner/scope 结算释放。 |
| input.FastPlaceClientInput | 保留对外入口及初始选区适配。 |

Host 有 8 个操作，不返回 ClientInputSession 或可变捕获器。FrozenPress 只包含冻结事实、已绑定命令和释放结果读取函数。已归属的 release 在取样抛错时仍清理；press 取样失败不会制造 release。7 项新增 Host 测试及定向生产编译通过。原 OperationInputSemantics 和旧测试无生产调用，已删除。

跨类型 capture 的替换由选区分工补齐：已受理的新命令先按 FIFO 排入两个 capture 的 Cancel，再排入新按压；禁用或放行规则不产生替换。该行为及本轮 Host 测试已包含在上述 k 轮整批回归中。

## 后续闭包顺序

### 1. 指针上下文与平台注入接口

InteractionContext 是射线、几何和选区共同读取的快照，但其 capture/vanillaOwnsPointer 反向调用 FastPlaceClientInput 和 ClientSessionSystem，形成 intent → input/session 的逆边。应将取样装配移到已有输入或会话装配能力，快照构造接受显式 alternative/control/vanillaOwnsPointer。几何和起形指针只接收该快照，不在深层重新取全局状态。保留一次采样、摄像机位置和原版近距准入语义。

BindingInputController 的原版重放直接引用 client.mixin.MinecraftUseInvoker，mixin 又调用 input。将该注入访问契约放入窄的既有平台访问包，并让 mixin 实现它；输入只依赖访问契约。不能为消除边而恢复反射或通用 Object 调用。

回归：同帧指针一致、近距原版放行、Alt、选区初始点、按压后会话换代、原版重放一次、失焦和换世界清理。

### 2. 模式定义与输入状态机

ClientInputStateMachine.State 的接受/分发调用 ClientModes.registry()；ClientMode、ClientModeView、ModeInputPolicy 和 builtin 定义又使用 State/InputKind/Dispatch。另有 ClientModes 装配位于 mode 包并依赖 mode.builtin，builtin 反向依赖 mode 契约。

最小完整边界是让纯阶段/输入/分发/语义请求契约由独立的契约层拥有，状态机接受冻结策略查询，不从 State 静态查全局注册表；ClientModes 默认注册装配迁到已有应用装配所有者。模式接口、策略和观察值不导入 builtin。替换所有 State 静态调用点时一起迁移，不能只移一个枚举后留下另外两种逆边。

回归：注册缺失/重复/冻结、全部模式阶段路由、取消保持、提交回执身份、旧请求和旧 release 不影响新手势。

### 3. 工作区与剪贴板

OperationClipboard 同时拥有不可变内容、fromWorkspace 和 instantiate；WorkspaceContentPreparer/ClipboardPreparation 反向使用 OperationClipboard.Part。ClientOperationWorkspace 又依赖 selection.ClientSelectionStack，selection 会话反向拥有 workspace。

先将“已解析的复制内容”和值结果放在内容所属边界，把从工作区复制/向工作区粘贴的装配留 OperationClipboardActions。数据构造中的 MAX_PARTS 限制应由实际共享契约拥有，不能为了读常量重新依赖 ClientOperationWorkspace。再将 ClientSelectionStack 的不可变选择顺序/栈操作归给 workspace，selection 会话只调用工作区能力，避免 workspace ↔ selection。

回归：重叠层、选择顺序、同 ID 不同实例、组合预算、复制失败、粘贴时选区实例化、逆操作、删除保留层、保存失败不发布。

### 4. OperationContext 与作用域装配

OperationContext 已以回调构造多个操作能力，但仍通过全局 ClientSessionSystem 查 submissions/previews/作用域，构造直接接受 ClientInputSession 并绑定 selectionGestures。ClientScope 反向创建 OperationContext 并拥有输入、工作区和仓库，形成 controller → input/session/scope → controller。

由 ClientScope 一次装配：传入 PointerResolver、SelectionGesturePointer、canCreateSelection、当前交互身份和 SubmissionStorage 等已存在的窄能力；OperationContext 不负责寻找或重新绑定输入所有者。ClientSubmissionEnvironment 与跨系统回执适配应归给实际装配所有者。所有权判断返回值必须包含现有 scope/连接/维度边界，不能退成一个永久 true 的 BooleanSupplier。

回归：两作用域并存、切维草稿、上传中取消、旧回执、收据查询失败、断线重连、异步迟到结果、被停放的操作不能写当前世界。

### 5. 绑定配置、数据和设置视图

BindingConfiguration 依赖 data/option，而 data.BindingLoader 和 option.OptionSettingsSection 反向导入 BindingConfiguration。配置装配、资源重载监听器和设置界面行不应与原始 BindingFile/BindingOption 值混在同一依赖闭包。

让 BindingLoader 接受资源替换消费者；OptionSettingsSection 接受冻结选项列表和读取/切换能力，或由现有 UI 所有者直接装配行。data 与 option 保持值和存储职责，不取全局配置。先核对运行时 query 的可变性和资源重载所有权，再决定具体契约，不能简单给每类建单类型包。

回归：资源重载、错误 JSON、覆盖和禁用、选项持久化、重复动作、默认树完整矩阵。

### 6. 渲染内部循环

当前报告没有 preview.assembly ↔ render 循环。assembly 读取会话、输入、模式和渲染帧模型是现存装配职责；不能将已解问题重复记为未完成。

真实剩余环为 render → guide/type，geometry → render/guide，guide → geometry，type → render。具体来源包括 HaloLineRenderType/PreviewRenderTypes 读取 FastPlaceClientShaders/PreviewStyle；DashedBoxRenderer 调 GuideRenderer.renderAlternatingDashedLine，GuideRenderer 又调 PencilStroke。

先把线段/虚线的底层绘制能力归入同一叶子几何绘制包，让 GuideRenderer 只组装引导面/线，DashedBoxRenderer 不依赖高层 guide。再把 shader 注册与 render-type 工厂放在同一资源所有者下，或让工厂接受显式 shader supplier，避免基础 RenderType 回调到业务 renderer。PreviewStyle 与 GhostOutlineDepthBias 归入已有窄样式/几何包；root 渲染器只向下组合。

回归：虚线相位/偏置、相机深度偏移、主题 revision、shader 重载、透明度、各图层渲染、资源清理和 GPU 生命周期。像素/游戏实测不能由纯数字测试替代。

## 剩余反射测试

当前剩余 9 类：8 类通过 Unsafe 构造未初始化的 BlockSnapshot，1 类构造未初始化的 OperationClipboard。PlayerMessagesTest 已由主任务改成公开 text 的可观察参数断言，不再反射 cleanArgs。

| 测试 | 被绕过的对象 | 替代方向 |
|---|---|---|
| common/workspace/submission/OperationWorkspaceValidatorTest | BlockSnapshot | 使用合法非空气状态及不可变快照，保持预算/来源/一致性结果断言。 |
| client/operation/workspace/WorkspaceContentPreparerTest | BlockSnapshot | 合法快照与实际组合预算；不伪造空字段对象。 |
| client/operation/workspace/SelectionDeletionPlanTest | BlockSnapshot | 合法共享快照，保留重叠删除和未选源保护。 |
| client/operation/controller/OperationContextTest | BlockSnapshot | 工作区/选区夹具提供合法内容，继续从公开操作验证逆操作和结果。 |
| client/operation/controller/GroupRepeatTest | BlockSnapshot | 合法多部分场景，验证组重复坐标、顺序和身份。 |
| client/operation/selection/SmartSelectionEditsTest | BlockSnapshot | 显式捕获结果或真实快照，保留采样编辑边界。 |
| client/operation/render/SourceBlockRenderMaskTest | BlockSnapshot | 合法快照建立实际源内容，验证 mask 的可观察映射。 |
| common/workspace/model/ClientSelectionPartTest | BlockSnapshot | 合法构造值，保留不可变性、移动和来源结果。 |
| client/operation/clipboard/OperationClipboardStateTest | OperationClipboard | 通过公开 record 构造器建立一个真实的非空 Part；仍验证写成功后才发布及失败可重试。 |

BlockSnapshot 明确拒绝 null state。后续应在已有 testkit/testSupport 提供一次正确初始化的 Minecraft 方块状态夹具，先核对当前测试 JVM 的注册环境；需要游戏运行时的用例应进入 GameTest。不得把 Unsafe 换成另一种反射、放宽生产构造器，或用空字段 mock 延续无效对象。

## 最终验收仍未完成的事项

1. 所有客户端包环、超限包、缺包说明、旧后缀及单类型包逐项清零，并通过不接受基线的完整严格检查。
2. 后续改动继续执行完整 check、新世界 GameTest 和打包验证，分别记录失败/跳过和对应报告版本；本轮执行结果见上方 k 轮证据。
3. 真实客户端的短按/长按/双击、重绑/禁用、原版放行、Alt、远距、水中、特殊物品、失焦和世界生命周期矩阵。
4. 几何/起形/选区预览与写入一致、三主题与资源重载、选择变换、提交、撤销重做和冲突策略的游戏内证据。

四边面和多边面继续保留各自默认形状，TranslatedScan 保留。不添加旧磁盘/协议兼容，不删除未确认数据。
