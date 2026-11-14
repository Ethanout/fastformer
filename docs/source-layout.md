# 源码结构

Java 包统一位于 `io.github.fastformer` 下。按职责放置代码，测试使用与被测代码一致的包路径。

模组包顶层只保留 `FastFormer` 入口。`fastplace` 顶层不直接存放 Java 类。

| 包 | 职责 |
| --- | --- |
| `client` | 客户端输入、交互状态、界面和预览 |
| `client.input` | 输入会话与紧密协作的调度控制器；规则、状态和通用手势分别放在 `policy`、`state`、`gesture` |
| `client.operation.controller` | 作用域持有的 OperationContext：协调编辑、捕获、提交与生命周期；能力拆分仍在继续 |
| `client.operation.preview` | 作用域自己的预览 revision、快照等待和显式恢复确认 |
| `client.operation.clipboard` | 绑定工作区的复制／原位复制／粘贴、明确结果和客户端共享剪贴板存储 |
| `client.operation.transform` | 绑定工作区的移动、旋转、翻转、堆叠和面／点调整；自管扫描、完成与取消 |
| `client.operation.submission` | 绑定编辑器的提交准备、等待、超时、在线结果与回执查询；存储、连接及输入通过接口注入 |
| `client.operation.edit` | 绑定选区会话的选点、智能采样、草稿补全、局部撤回和删除捕获 |
| `client.operation.pointer` | 绑定选区的手柄／面／部件命中、创建候选与变换方块缓存；不依赖渲染入口 |
| `client.operation.input` / `client.quickshape.input` | 选区操作与快速起形各自的输入规则、事件和捕获状态 |
| `client.interaction.intent` | 交互上下文与意图解析 |
| `client.interaction.pointer` | 所属输入环境的方块射线采样；按用途缓存，命中与距离共同返回 |
| `client.mode` / `builtin` / `input` | 冻结的模式注册表、工作流观察、统一模式视图、阶段策略和各模式按键／滚轮决策 |
| `client.session` | ClientSessionSystem：连接与作用域、草稿／回执协调和提交编排 |
| `client.session.scope` | 服务器、维度、玩家的不可变身份，以及所属输入、编辑上下文、选区、工作区和暂存草稿 |
| `client.session.receipt` | 作用域回执仓库：缓存、严格读取、文件保护、明确重试和驱逐条件 |
| `client.session.draft` | 作用域草稿仓库：严格解码、回执判定、耐久版本身份、结算和文件删除 |
| `client.session.storage` | 有界磁盘队列、主线程完成回调和共享的严格 NBT 字段读取 |
| `client.session.submission` | 双文件提交准备、未发送回执重试及暂存／耐久草稿分别结算；每次操作冻结所属作用域 |
| `client.session.reconnect` | 有界快照等待；不持有草稿、不决定恢复行为 |
| `client.session.preview` | 会话系统持有的服务端快照、revision、起形提交快照和重连确认；不依赖渲染 |
| `client.session.lifecycle` | 客户端世界离开事件、按顺序通知清理能力并汇总失败 |
| `client.network.inbound` | 客户端载荷类型注册、连接校验与 tick 投递 |
| `client.network.outbound` | 可选通道检查与向当前连接发送请求；设置界面和放置网关共用 |
| `core.registry` | 无游戏依赖的注册表；拒绝重复键，装配结束后冻结 |
| `core.command` | 类型明确且冻结的 IntentBus；先校验 Gate，再执行处理器 |
| `core.math` | 不依赖游戏的数学轴、向量数值操作及按单位区分的容差 |
| `common.math` | Minecraft 向量构造适配；不持有输入或渲染状态 |
| `core.async` | 有界执行器、可取消任务、最新结果计算与 tick 邮箱 |
| `core.gate` | 带拒绝原因的组合准入规则 |
| `core.rule` | 冻结的有序规则链，显式区分未匹配与终止结果 |
| `core.lifecycle` | 类型化资源作用域与统一生命周期遍历 |
| `core.persist` | 原子文件、CRC32C 字节信封、载荷预算、隔离和存储路径规则 |
| `common.persistence` | 当前 Minecraft 数据版本、严格方块调色板与有预算的压缩 NBT 信封 |
| `common.tinker` / `data` / `state` / `shape` / `rule` | 共享扳手预测、数据定义、状态循环、形状解释与规则顺序 |
| `common.replace` | 快速替换目标预测与自适应射线补采样 |
| `common.history` | 不可变的三档历史压缩参数、设置坐标和层级；客户端与服务端共用 |
| `common.raycast` | 冻结的命中规则、形状快照和数值校验 |
| `common.preview.quickshape` | 起形预览的共享快照、会话、参数和效果值；不读玩家设置、不实现网络包 |
| `server.lifecycle` | 单个服务器的后台工作线程和显式关闭 |
| `server.player` | 按服务器隔离的瞬时玩家状态与实例绑定 |
| `server.replace` | 快速替换模式、笔画与请求去重；状态由玩家作用域持有 |
| `server.fill` | 智能填充发现、预览确认与请求去重；状态由玩家作用域持有 |
| `server.session.slot` | 玩家活动会话与按维度停放的会话 |
| `server.input` | 服务端输入分发、请求顺序与手势身份 |
| `server.intent` | 玩家意图系统装配、会话动作、撤销／重做与取消路由 |
| `server.intent.quickshape` / `selection` | 起形参数与阶段模式、选区提交与模式意图；处理器各自执行准入 |
| `server.command` / `edit` | 两个命令根的装配、设置／历史和按编辑能力划分的命令语法 |
| `server.command.syntax` | 类型明确的参数声明、参数链装配与模式词汇映射；不使用反射 |
| `server.settings` | 设置的类型化处理器、修改与查询、权限校验及反馈 |
| `server.network` | 服务端通道注册；统一线程投递和网络到 Intent 的转换 |
| `server.network.upload` | 单服务器上传准入、分块／解码资源、请求作用域、回执与取消 |
| `server.sync` | 服务端预览装配与发布；玩家作用域持有 revision、提交参数和回调身份 |
| `server.session` | 单服务器会话系统；拥有快速起形、几何、选区工作流及任务队列 |
| `server.session.execution` | 放置、选区及工作区任务的队列、推进、恢复移交、结算与迟到回调校验 |
| `server.task` | 统一任务契约、上下文、预算、恢复优先调度与队列所有权 |
| `server.task.block` | 普通放置、选区操作与工作区提交的方块写入算法及计划 |
| `server.task.runtime` | 写任务的租约、日志分段、事务、提交与资源释放 |
| `server.worldwrite` | 单运行实例的维度写入租约、令牌身份与未使用资源清理重试 |
| `server.feedback` | 单服务器的离线任务提示；保留最新进度与最多四条结果 |
| `server.submission` | 服务端提交账本、去重记录与任务结算接口 |
| `server.history` | 每个服务器的历史服务装配、玩家资源所有权与存储接口 |
| `server.history.entry` | 不可变条目、区域边界、顺序组合及按必填 Type 分派的 Codec |
| `server.history.store` | 不透明历史信封、批次文件和耐久顺序索引；不依赖游戏运行时 |
| `server.history.compaction` | 压缩策略和不可变条目上的纯分组算法 |
| `server.history.memory` | 内存历史、完整顺序、分页及按 revision 校验的后台任务 |
| `server.history.vanilla` | 原版方块编辑观察与历史事件发送 |
| `server.history.commit` | 耐久提交状态机；不依赖 Minecraft、存储或世界运行时 |
| `server.history.rollback` | 回滚请求与捕获队列；自管就绪等待、快照物化、准入及有序重试 |
| `server.history.replay` | 通用增量重放循环、类型化状态与日志适配；冲突提示单独拥有令牌及高亮流 |
| `server.recovery` | 单服务器恢复系统、日志保留与清理、当前分段格式、启动扫描和重放 |
| `server.recovery.gate` | 无游戏依赖的实例写入闸门、恢复版本标记和永久关闭 |
| `server.recovery.spi` | 注入历史协调接口；恢复实现不依赖历史实现 |
| `fastplace.world` | 世界写入、事务、写入锁与批次反馈 |
| `fastplace.world.memory` / `fastplace.world.snapshot` | 内存准入与预留；可逆方块快照 |
| `fastplace.session` | 会话数据、活动类型和交互状态 |
| `fastplace.settings` | 放置与交互设置 |
| `fastplace.text` | 翻译文本契约与玩家消息 |
| `fastplace.interaction` | 空手方块操作和特殊物品交互 |
| `fastplace.geometry.raycast` | 远距离方块射线查询 |
| `fastplace.placement.context` | 放置上下文和可放置物品判断 |
| `fastplace.placement.effect.woodframe` | 木框朝向与放置效果 |
| `fastplace.geometry` | 几何数据与算法；锥体、多面体和生成规则放在各自子包 |
| `fastplace.geometry.generation` | 方块离散生成；四边形共用固定边缘与法向平面求解，算法选择不进入设置或协议 |
| `fastplace.geometry.controlpoint` / `interaction` / `text` | 控制点、指针交互模型和几何文本契约 |
| `workspace.geometry` | 工作区几何预算与成本 |
| `workspace.submission` | 工作区提交计划、校验与冲突语义 |
| `workspace.transform` | 共享选区变换与重复步长算法 |
| `network.codec` | 网络数据编码与解码 |
| `network.transport` | 公共侧客户端投递入口；不引用客户端实现 |
| `protocol.c2s.<feature>` / `protocol.s2c.<feature>` | 按方向与功能划分的当前消息和 wire Codec；不持有运行时处理器 |
| `protocol.callback` | 收发两端共用的玩家、维度与连接身份 |
| `protocol.settings` | 当前设置键、值类型和查询／修改请求 Codec；消息位于 `protocol.c2s.settings` |
| `protocol.upload` | 两种当前上传载荷共用的分块字段契约；不持有接收状态 |

`FastPlaceClientPreviewCore` 将初始方块预览、快速替换和建筑外壳绘制委托给独立渲染类。`BuildingPreviewCache` 持有快速起形的生成任务、降级结果与渲染帧缓存，通过参数接收预览状态、修饰键读取与错误记录。外壳绘制显式接收透明度和动画相位。

`WorldSessionEvents` 统一接收客户端登出／世界卸载，`WorldSessionLifecycle` 复用 `Scope.detach` 按既有顺序清理输入、源遮罩、预览连接、编辑器、交互缓存、反馈和渲染资源。一次运行时清理失败仍通知后续能力，结束后汇总异常；重复边界仍可再次清理。渲染核心只提供自身的重置能力，不订阅世界离开事件、不调用编辑器断线清理。

`ClientSessionSystem` 持有 `ClientPreviewState`，输入与绘制读取同一份服务端快照。`modeView()` 从真实工作流所有者捕获事实，注册模式决定观察阶段；当前输入路由和待发送起形请求决定是否允许空闲操作。删除输入总入口中的 `vanillaSession()`、`activeSession()` 和重复观察链。任务遮盖仍保留底下的活动交互标志，取消／提交期间仍由路由状态持有输入。视觉层的既有绘制优先级本阶段不改。

`ClientScope` 持有完整的 `ClientScopeId`，管理器从当前对象派生身份，不另存可能错配的当前键。草稿读取直接接收所属作用域，按其身份记录读取结果。`ClientScopeFiles` 统一原有三字段摘要和两个当前格式文件位置，不新增迁移路径。

`ClientScopeRepository` 拥有作用域集合、当前身份、激活边界及无世界时的共享备用手势。跨维度清实时交互、保留暂存草稿；跨玩家或服务器只清旧输入，保留原有工作。草稿和回执共同决定能否驱逐；总入口仅在共享存储和延迟保存空闲后触发驱逐。已应用草稿的清理重放标记属于具体作用域，每次重新进入后重新执行一次。

每个作用域持有 `ClientReceiptRepository`，内部保存回执缓存、读取结果和文件位置。损坏或不支持的文件保持只读，失败只在实际读取时上报；只有明确重试或进入环境时的暂时失败重试才重新读取。脏回执或失败读取阻止驱逐；写盘成功前不清脏状态。

`ClientDraftRepository` 持有草稿文件位置、读取状态和已保存内容的对象身份，负责严格读取、回执等待、提交归属探测、结算余量和删除。它只返回读取结果，不修改输入或选区。旧写盘回调只能确认原草稿，无法让替换后的未保存草稿变为可驱逐。总入口保留暂存／恢复协调与提示，不再管理双文件提交或并行草稿／回执映射。

`ClientScope` 持有自己的 `OperationContext`，输入、渲染和网络消费通过当前作用域取得实例，不保留静态控制器转发。捕获、提交等待、失败高亮和源遮罩不跨作用域共享；捕获及保存回调继续检查当前选区所有者。环境离开取消临时任务，重置预览边界；首次进入不制造重连提示。剪贴板属于客户端仓库，继续跨世界共享。

`OperationClipboardActions` 构造时绑定工作区、共享仓库、提交／编辑门禁和变更通知。它不查询全局编辑器，直接返回 `ClipboardActionResult`；提示层使用该结果，不再从编辑器读取剪贴板失败。`OperationClipboardRepository` 内部拥有缓存与文件位置，成功落盘后才发布内存，读取失败保持可重试。复制、原位复制和粘贴保留原有容量、内容预算与定位规则。

`OperationTransforms` 构造时绑定工作区、提交／捕获门禁、当前作用域校验、失败反馈和变更通知。它完整管理面调整扫描、延迟完成、编辑令牌和取消；输入与渲染直接使用该组件，不通过编辑器转发。工作区快照、滚轮历史合并、局部重复步长和单次失败提示的原有规则继续保留。

`OperationReceipts` 通过 `SubmissionReceiptAccess` 访问回执，不依赖会话总入口。查询按当前维度过滤并按协议上限分批；答复必须匹配本地回执，已知成功不能被较弱结果覆盖。清理失败保留提示与重试，未知结果不触发重新提交。实际存储和双文件清理由 `ClientSubmissionPersistence` 实现；消息发送、时钟和提示均在装配时注入。

`ClientSubmissionPersistence` 统一接管双文件准备、回执缓存读取／失败通知、显式重试、结果保存与草稿分别结算。操作开始后使用冻结的作用域及文件位置，迟到结果不查询另一个世界的仓库。两个文件均保存且写入令牌仍有效时才发布发送命令；排队拒绝、磁盘失败、离开作用域或失去请求所有权均保留原作用域的可重试回执。总入口只装配、推进队列和在无待存工作时驱逐作用域。

客户端压缩 NBT、历史与恢复文件共用 `core.persist.AtomicFiles`。需要校验写入代次时，先用 `prepare` 在锁外编码并强制临时文件，再在所属锁内校验令牌并 `commit`；取消或过期时关闭准备对象只删除临时文件。原子替换失败直接报告，不回退到普通覆盖。

`OperationSubmissions` 持有一次提交的不可变 `SubmissionScope`、请求身份和等待阶段，先保存草稿与回执，再发送。`SubmissionStorage` 返回发送命令，`SubmissionEnvironment` 处理连接及输入请求，组件不查询总入口。迟到准备回调先核对请求、会话所有者和连接，重复提交不改写已有范围。`WholeWorkspace` 和 `SelectionParts` 区分整区与局部提交；后者明确保留或移除选点草稿。动作返回 `SubmissionActionResult`，没有读后即清的错误字段。离开环境保留耐久余量，明确取消才清余量；超时保留可编辑内容并拒绝迟到结果。

`SelectionEdits` 完整持有选点和智能选区捕获、点击前后逆操作快照、局部撤回及模式切换。会话、预览读取、提交门禁、所有者校验和清理／反馈通过构造传入；内部不查询编辑器或总入口。异步删除通过 `unchanged()` 校验冻结基线，不读取内部历史快照。捕获按同一 tick 截止推进，退出统一取消。

`SelectionDeletions` 绑定相同会话，自管删除扫描、完成和取消。删除选中部件时保护未选中的源位置；未完成草稿按当前预览体扫描，开始前冻结选中 ID、工作区 revision 和选点基线，失去世界／所有者或草稿变化就丢弃结果。空删除本地完成，其余复用 `OperationSubmissions`，不在删除组件重建持久化或请求生命周期。输入直接调用该能力，协调器只推进和清理。

`ClientInputSession` 持有自己的 `PointerResolver`。同一视图的普通轮廓、放置和流体选点分别缓存；返回完整 Result，调用者不能从另一次采样读取距离。玩家／世界身份、眼睛、视线及世界 tick 改变时失效；每次渲染帧和输入状态同步开始显式失效，环境重置释放所有命中。几何计算、工作区面检测和智能选区复用这些采样；已冻结的双击／长按交互仍按自己的原始射线与规则处理。

`WorkspacePointerResolver` 绑定具体 ClientSelectionSession、失败位置供应及该环境的 PointerResolver。拖面、手柄、面、部件和创建候选保留原优先级；创建由 `SelectionCreationPointer` 使用草稿／远端预览处理，完全不读取渲染核心。变换模型缓存属于解析器实例，按工作区 revision 和交互所有者失效；清理一个选区不影响其他选区。输入直接查询此能力，渲染读取同一能力的可见方块。

`ClientPreviewFrameSystem` 在渲染帧开始时捕获 SelectionPointerState 与 AnimationPhase。HUD、世界渲染和视觉悬停共享该帧结果，不在绘制时重新解析。物理输入仍实时 resolve，不覆盖视觉快照。失去交互所有者、隐藏画面或捕获异常时清空当前结果；模型缓存清理不会意外清除当前帧。

`FrameAnimationClock` 以一份单调时间推进蜡笔绘制帧；AnimationPhase 同时提供虚线位移、呼吸透明度和扩展样式的时间。每帧只采样一次，所有材质／线条共享相位；静态线始终使用 sheet 0。资源中的 boil_fps／boil_sheets 和既有虚线、呼吸周期不变。视觉种子的重复哈希收敛到 core.math.StableHash。

预览不再使用全局可变 worldPreviewOpacity。正常预览没有距离淡化；按住 Alt 的服务端选区通过参数将 0.35 透明度传给面、轮廓、法线、拖动参考、控制点与 Gizmo。工作区和草稿保持各自原有的显式淡化规则，任何绘制分支都不改写其他预览的透明度。

ShapeShellRenderer 与 BuildingShellFaceBuffer 直接使用 PencilStroke／PreviewQuads，不反向调用 FastPlaceClientPreviewCore。SelectionVolumeRenderer 独立绘制权威选区体轮廓，输入为几何体和显式 alpha；原总入口中的体轮廓、线和写面转发及无调用的虚线盒／旧轮廓已删除。

`ClientModeRegistry` 冻结模式优先级、所属阶段与输入策略；拒绝重复 ID／阶段、缺少阶段、冻结后注册和观察其他模式的阶段。`ModeObservation` 仅携带工作流事实；空闲、起形、几何、选区、替换、填充和任务分别声明规则，不持有客户端全局状态。ClientInputStateMachine 继续独占提交请求、取消和手势代次；分派及提交准入查询注册策略。旧 ObservedInputState 已删除，空闲／活动判断和草稿读取门禁复用同一注册表。滚轮由模式 onScroll 决策，专用工具键由 onKey 决策，PASS 才回落空闲；输入边界捕获事实并执行返回命令。其余通用按键、指针事件与预览注册尚待迁移。

旧 `ClientSessionManager` 已删除，由 `ClientSessionSystem` 提供当前作用域及协调入口，查询命名为 `currentScope`。草稿模型和 Codec 位于 draft，回执模型、Codec、缓存及重试保存位于 receipt，队列和 NBT 辅助位于 storage，来源和结算规则位于 submission；相关纯单元测试跟随被测能力迁包。整个 client.session 树执行包容量和职责说明检查，不保留旧路径别名。

`ServerInputDispatcher` 从所属玩家作用域获取 `ServerInputState`。请求水位和手势是单个玩家的字段，不再使用全局 UUID 表。双击快照和扳手 token 使用同一生命周期；退出和玩家实体替换丢弃瞬时状态，换维统一重置。活动工作流、停放会话和世界任务仍由各自的仓库持有，完整服务器实例化尚未完成。

`PlayerIntentSystem` 装配共享的 `IntentBus`。会话、历史和设置处理器分别持有自己的门禁和动作；命令与网络构造同类意图。具有请求 ID 的原入口继续先校验序列和作用域，再把 ID 传入意图上下文；不含 ID 的请求使用 0，不能据此绕过功能自身的去重规则。拖动、上传和部分业务动作尚未迁入 Intent。

几何模式选择、锥体平面／椭圆、多面体尺寸及欧拉角／四元数／矩阵／步进旋转均通过 `GeometryIntents`。模式选择保留“无其他活动编辑器、几何尚未设置首点”的条件；参数修改保留原可用门禁，不扩大为新的写任务排他规则。矩阵意图冻结输入与输出数组，工作流继续负责状态校验、设置保存、提示及预览同步。输入分发器不再提供模式选择转发。

起形数值、角度、距离与阶段模式使用 `QuickShapeIntents`；选区提交、复制及模式选择使用 `SelectionIntents`。命令提交保留直接应用语义，网络提交仍先校验请求顺序、继续交互门禁及已确认状态，不把两种入口强行合并为同一业务行为。处理器负责参数边界与失败反馈，命令只解析并构造意图。

选区点选、增删点、闭合底面、拖点、扩展及延伸同样构造 `SelectionIntents`；坐标在意图构造时冻结。网络输入先核对所属预览、revision、请求序列和完整手势，再派发业务动作；拖动结束仍在原 finally 中更新完成 revision。业务处理器统一执行活动选区与编辑门禁，延伸步数仍限制为每包 128；不把协议身份校验移入领域动作。

`CommandSystem` 装配 `/ff` 和 `/fastformer`；删除旧注册类及旧包路径，不保留转发。编辑语法由起形、几何、旋转和选区模块分别声明。`CommandParameter` 同时声明 Brigadier 参数和类型明确的读取方式，`IntentCommands` 生成有序参数链；`CommandChoices` 冻结模式词汇及既有用法提示，保留不同入口先校验准入或先校验词汇的顺序。Reach 属性查询和管理员清理仍保留专用源上下文处理，整个命令迁移尚未完成。

设置命令构造共享 `SettingRequest` 或 `PlayerSettingsIntents`，状态修改、查询、界面数据装配和预览同步归 `server.settings`。共享设置绑定按来源保留现有同步行为；命令历史限额查询／修改要求编辑可用，网络设置不因此新增门禁；网络不支持的查询仍返回未处理。强制放置命令的源上下文反馈留在适配层。`CommandAdmission` 统一拒绝反馈，`SessionSystem.stopUnavailable` 持有不可用时的取消组合；输入分发器不再提供清理转发。

`ServerChannels` 为 37 个服务端通道统一执行服务端线程投递与玩家类型检查。注册处选择 `handle` 或 `toIntent`；业务处理器仍负责请求身份、手势、回执和上传校验。客户端连接校验继续由原入站队列持有。

原 `network.payload` 已移空，不提供旧包路径的转发类。收发方向不能互相导入；共享作用域、设置声明和上传分块契约各自独立。协议检查禁止引用客户端、服务端、旧网络实现及玩家设置对象，且每包最多 12 个类型。协议还引用部分尚未搬迁的 `fastplace`／`workspace` 共享领域类型，完整四层迁移仍未完成。

`BuildingPreviewSnapshot` 是共享预览值，不是消息。删除未注册的旧合并预览 Codec，仅发送当前会话、参数和效果三个分包；接收方验证 revision 后再组合。服务端 `QuickShapePreviews` 负责从草稿和设置装配快照；`PreviewSync` 负责发布。压缩设置改用 `HistoryCompressionLimits`、`CompressionTier` 和 `CompressionSetting`，客户端不再为显示配置而依赖服务端历史实现。

`PreviewSyncState` 由玩家作用域持有，不再使用全局 UUID 表。revision 查询与清理不创建状态；同 UUID 的旧玩家实例不能清除新实例的发布。换维先通知旧环境失效，再恢复目标维度工作流并发布新快照；断线和服务器关闭由玩家作用域统一丢弃，删除预览同步的全局 `clearServer` 入口。

`SessionSystem` 按服务器身份显式启动和停止，实例持有三类编辑工作流及两类任务执行能力。编辑器分别拥有自己的 `PlayerSessions`，任务队列由 `execution` 单元持有；不再保留旧静态 Manager 或兼容转发。UUID 工作流与世界任务在断线后继续保留，换维仍按维度停放和恢复。服务器关闭先结束准入，再统一取消任务、释放资源并清空工作流；一个清理失败不会跳过其他任务，旧 resume 回调不能推进已关闭的实例。停服后的迟到 logout 使用非创建查询。

物理 Alt 状态由玩家作用域的 `InteractionModifierState` 持有，断线、换维和实例替换归零。恢复或重新绑定草稿前以当前物理状态更新其修饰键；旧实例的迟到清理不能改变新实例的草稿。会话系统的实例化不等于业务拆分完成，三类系统的进一步职责拆分及剩余服务端状态仍待迁移。

`SessionSystem` 统一处理跨会话分类、修饰键同步、活动编辑取消、退出、玩家脱离、换维停放／恢复与当前预览发布。`cancelEditing` 保留其他维度的草稿及已排队世界任务；`quit` 按既有恢复规则取消任务；`discardPlayer` 清除玩家绑定的编辑状态。快速起形只结束自己的草稿，不再直接调用选区／几何系统；全局调用方使用所属会话系统。历史恢复状态直接查询 `HistoryService`，不经两套工作流转发。截获丢弃事件的物品归还由 `server.player.TossedItems` 负责。

`WorldTaskScheduler` 只负责恢复 → 起形写入 → 选区写入 → 提交账本的推进顺序，不持有玩家消息。写入闸门关闭后仍分别尝试两类任务的恢复移交；调度异常继续关闭闸门并保留日志与租约。原 `WorldTaskFeature` 已删除。离线消息由 `SessionSystem` 的 `TaskFeedback` 持有，不跨服务器或存档共享；玩家重新登录后发送一次。`WorldTaskContext` 只向仍存在的所属运行实例投递，关闭后的迟到消息被丢弃，不创建备用全局状态。

`WorldTask.tick(context)` 是三类写任务的统一推进入口，`TaskStep` 区分等待与终态。`LeasedJournaledTask` 统一执行维度检查、内存准入、租约获取和批次指标采集；子类实现 `tickBody`，起形的生成前置步骤和小操作预算通过钩子保留。已返回终态的任务在历史发布等待期间不重复执行算法。取消先停止准入；恢复移交保留工作集，停服取消则等待日志和发布不再使用数据后释放预算。

数学轴统一使用 `core.math.Axis`。工作区旋转、镜像、局部坐标、堆叠、手势、协议校验和 Gizmo 共用同一类型；不再由 Gizmo 定义通用数学轴，也不保留嵌套类型别名。X/Y/Z 的既有序号及相关磁盘／网络字段保持不变；取分量、旋转次序和朝向选择算法不因迁包而改变。

`core.math.Vectors` 通过调用方工厂构造结果，不依赖 Minecraft，也不产生额外中间向量。`normalize` 按长度比较，`normalizeSquared` 按长度平方比较；`Epsilon` 分别命名既有两种阈值，不能互换。`common.math.MinecraftVectors` 适配 `Vec3`，明确区分严格输入和 null 返回零的调用；长度分支保留倒数相乘、平方长度分支保留原版逐分量除法。输入、几何、射线和指南渲染直接复用，不保留旧工具方法转发。

`Vectors.dominantAxis` 按绝对分量选择主轴，同量时依次优先 X、Y、Z；分量提取和轴向量构造共用 `Axis`。`MinecraftVectors.dominantStep` 保留选中轴的符号，与正向基轴构造分开。起形滚轮、棱柱投影、体积回退轴和铅笔线端点共用相同主轴规则，铅笔哈希仍使用原 X/Y/Z 序号；工作区缩放使用共享分量提取。

`common.math.BlockQuads` 统一格点四边形的对角坐标和判定，先提升为 long 再求和。浮点输入通过明确命名的落格入口处理，不直接以浮点相等替代旧逻辑；退化、平面和预算仍由生成器分别校验。

`WorldTaskQueue` 核对任务对象身份后才推进；恢复移交中的槽位、后继任务和已关闭队列拒绝旧 resume。关闭先清空槽位并结束准入，再统一逐个取消；一次清理失败仍继续清理其他任务。已删除无身份删除、直接清空及两套旧任务接口。队列交接仍等待日志追加和历史发布终止，再接受事务快照。附属资源清理失败仍释放共同资源；工作区 tick barrier 先解除引用再关闭，重复清理不会再次关闭它。具体任务与运行层已迁入 `server.task.block` 和 `server.task.runtime`，禁止反向依赖会话编排。固定预算构造仅由同包共享测试源码提供，生产代码不含测试工厂或旧包转发。

`SessionSystem.placementTasks()` 返回所属的 `PlacementTasks`，完整拥有普通放置的准入队列、tick／resume、取消、关闸移交、历史发布等待与结算提示。`QuickShapeSystem` 只组织编辑和生成请求，提交后调用该能力；它不持有任务队列，也不再转发任务查询或执行方法。调度器、门禁、撤销／退出和测试都使用同一个能力。关闭父作用域后，旧 resume 即使还在消息队列中也不能继续推进。

`SessionSystem.operationTasks()` 返回所属的 `OperationTasks`，拥有选区和工作区任务的执行、恢复移交及完成回执。运行时通过谓词注入普通放置的优先约束，通过发布接口注入工作区结果发送，执行模块不反向依赖编辑或上传系统。`OperationSystem` 保留编辑和提交准入；准入仍先入队，再记录账本、安排 resume、报告提示。任务查询、取消和上传回执查询直接访问执行能力，不经编辑工作流转发。

两类执行共用包内 `TaskExecution`：统一队列准入、tick 与 resume 仲裁、恢复移交、取消和关闭。延迟唤醒同时检查任务身份、移交状态、原恢复闸门、历史忙碌和前置执行优先级；父运行实例关闭后，tick 和关闸移交不再创建上下文。恢复未接受时保留槽位，接受后先移除槽位再发送业务回执。叶类只维护各自的完成结算与反馈。

`server.worldwrite.WorldWriteLeases` 由所属 `RecoverySystem` 持有，不维护静态服务器表。每个令牌绑定授予它的服务对象、维度、玩家及代次；旧令牌不能释放后继令牌或替代运行实例的租约。未使用资源通过 `UnusedWriteCleanup` 清理，失败保留租约并按单调时钟重试；清理异常也保留重试。关闭实例永久拒绝新租约，迟到回调只能清理自己的旧资源，不能重建租约状态。旧静态协调器及生产强制重试钩子已删除。

`RecoverySystem` 按服务器显式启动和关闭，持有恢复执行器、`RecoveryWriteGate`、`WorldWriteLeases` 和已提交日志的保留状态。恢复期间关闭写入；仅当前版本的成功恢复能重新开放，较早结果、后续故障或运行实例关闭均不能被覆盖。`WorldTaskContext` 保留原闸门及租约服务身份，派生 resume 上下文继续使用它们；迟到任务不能改用替换后的恢复系统。无服务器上下文使用独立或显式注入的服务，没有全局备用状态。

每个 `PersistentRecoveryJournal` 在创建时绑定所属执行器和 `CommittedJournalRetention`。清理先完成持久化 IO 屏障，再核对日志登记的对象身份；排队失败保留文件，旧清理不删除重新登记的路径，也不释放新登记的待清理标记。关闭和启动恢复使旧登记失效。等待历史发布的封存不登记为可清理，发布完成后才等待下一次耐久存盘清理。日志类不再保存全局闸门、提交表或测试复位开关。

`UploadSystem` 按服务器显式启动和停止 `WorkspaceUploads`，查询不能创建实例。两种上传使用同一接收流程，各自保留独立分块通道；分块、解码和回执作用域均由实例持有。断线、换维和玩家实体替换取消未提交上传，关闭后拒绝新请求。解码取消同时释放待消费的大结果；旧回调按任务身份校验，不能释放同 ID 新请求的名额。tick 保留仍属于分块、解码或世界任务的作用域，清理已无所有者的作用域。内部实现不向包外暴露。

`WorkspaceReceipts` 先记账、再清理作用域和投递，离线结果仍保留在账本。重复提交运行中的任务不替换其原作用域；重复首包也不能把已接收分块改绑到新连接。解码完成检查原连接、玩家实例和维度。任务通过不可变的 `WorkspaceCompletion` 与 `WorkspaceResultSink` 报告结算；取消和实际服务端调用共用同一注入入口。工作区与形状两种现有载荷仍保留原 wire 格式，只共用 `UploadChunk` 契约。

`WorkspaceSubmissionLedger` 由 `SessionSystem` 的作用域创建和关闭，上传系统装配时显式接收同一实例。账本不保存服务器映射，也不查找运行系统；查询和迟到写入不能重新创建已关闭账本。任务清理先于账本丢弃，停服结果结算后统一清空；一个服务器的关闭不影响另一服务器的相同玩家和 transfer ID。记录容量、完成记录的六小时期限、运行记录不驱逐以及强结果不被弱结果覆盖的规则保持不变。

历史及恢复共用 `Envelope`／`EnvelopeFile` 的 24 字节当前格式信封，CRC32C 同时覆盖元数据和实际载荷字节。完整性验证成功后才分类已知版本不兼容，损坏不能被当作可跳过的旧格式。历史存储仍只处理不透明字节，由装配方传入当前 DataVersion；恢复通过 `NbtEnvelopeFile` 处理压缩与解码预算。修正数据绑定完整分段集合的操作 ID、数量及顺序摘要；旧历史信封、分段 CRC 包装、链式摘要和首段 SHA-256 路径已删除。

设置修改只使用 `SettingPayload`。`SettingCatalog` 按注册键选择值 Codec，拒绝未知键和其他注册表创建的同名键；`SettingBindings` 将同一键的值类型与服务端处理器绑定。缺失值明确表示查询，不能被当作默认值写入。界面发送目标值而不是翻转命令，重复写入不会反向切换。落沙规则仍检查操作员权限；压缩参数和历史容量继续使用原有边界约束。旧的五种独立设置包及其注册已删除。当前协议版本为 84，不保留旧通道或旧预览字段。

四边形由 `QuadFaceRasterizer` 建立离散投影域，`NormalPlaneFaceRasterizer` 在固定边缘约束内选择高度。相对边保持首两条边的整数平移；冲突边缘保留完整高度区间，空心体保留这些面样本及实体外表面。生成结果仍支持取消、限额和退化回退。退役的算法枚举、点扫／交叉插值求解器及其设置、Modes、预览字段已删除；通用多边形生成与失败时的有界回退继续保留。

`HistorySystem` 按服务器身份装配和关闭 `HistoryService`；查询只返回已启动实例，不能隐式重建。服务显式接收准入 Gate 和 `HistoryRepository`，拥有自己的玩家表；`OwnerHistory` 持有单玩家状态，`HistoryReplayTask` 执行单次写入、回滚和恢复。`DiskHistoryRepository` 拥有存储和 `HistoryWriteQueue`，队列预算及同玩家写入顺序不跨服务器共享。关闭后拒绝新请求，已接收写入继续完成；服务器后台执行器负责最终关闭。

`OwnerHistory` 自管任务取消、离线、耐久提交与发布重试。`HistoryLoading` 拥有初始加载和分页生命周期；初始加载失败阻止编辑并等待显式重试，分页失败保留现有历史，仅记住失败请求。`HistoryPersistence` 拥有待写计数、失败状态、重试倒计时及发布代次；关闭后忽略迟到完成。`RollbackQueue` 保留失败捕获，成功安装重放后才释放队列所有权。历史重放与其他写任务共用 `TaskWriteLease`，迟到清理仅能释放原租约。

历史存储使用冻结的 `EntryCodecRegistry`，按必填 `Type` 选择类型明确的 Codec；缺失或未知类型直接拒绝，不回退到旧方块格式。`HistoryEntryFormat` 统一字节预算和 NBT 编解码，仓库加载时还检查条目操作 ID 与索引文件名一致。生产服务、存储、分页、缓存及压缩调度使用 `HistoryEntry`，不将解码结果强制转换为方块批次。

`EntryReplayRegistry` 按条目类型创建独立的 `EntryReplayState`；冲突镜像、部分写入指纹和回滚由该状态提供，`ReplayJournal` 提供对应的耐久与取消生命周期。通用重放循环负责预算、租约、顺序与推进，不捕获方块快照。当前方块实现复用原有分段日志；新增类型必须同时提供 Codec、重放状态及可恢复的日志实现，不会自动获得实体或生物群系的恢复能力。`BlockHistoryRecorder` 处理方块专属的手势合并与逆操作识别；后台方块压缩策略不跨越其他类型条目合并。历史各包仍最多 12 个类型，并检查依赖方向及通用层不能引用具体方块条目。

扳手使用不可变的 `TinkerContext` 和 `TinkerAction`，预览和实际编辑共用有序规则。规则读取世界但不写世界；服务端入口单独负责准入和事务。未匹配才继续下一规则，明确返回 PASS 会终止并允许原版交互。

工作区模型不依赖网络层。提交结果语义位于 `workspace.submission`，网络载荷引用该模型。`JournalStartupRecovery` 扫描分段日志并调用注入的 `HistoryReconciler`，`JournalReplayer` 用同一遍历框架执行提交重放或逆向回滚；历史协调失败时保留已封存日志并阻止新写入。Alpha 阶段只支持当前格式与当前 Minecraft DataVersion，不保留旧格式迁移；已知版本不兼容的数据隔离到 quarantine，未知损坏仍保留恢复保护。确认的试玩旧数据允许手动清除，生产读取器不自动删除未知文件。

客户端网络结果由公共侧 `ClientboundTransport` 交给 `client.network.inbound`。`ClientInboundSystem` 只负责装配类型明确的处理器与校验规则；`InboundPayloadQueue` 持有注册表和 tick 邮箱，消费时检查连接与会话作用域。设置页面使用同一路径。客户端退出世界时使待处理批次失效；处理期间产生的新事件留到下一 tick。公共网络层不使用字符串反射，也不加载客户端实现。

`ClientPlayerSession` 将同一个选区手势状态交给输入和选区。首次进入世界前的备用输入与备用选区由 `ClientSessionManager` 一起装配。指针查询和输入结算使用传入会话的手势状态。输入重置统一释放指针捕获，包括远端变换捕获。两个重连流程复用 `ReconnectSnapshotWindow`，仍分别持有自己的业务状态，等待上限仍为 40 tick。

磁盘任务通过 `ClientStorageQueue` 串行执行，容量包含尚未消费的完成结果，工作失败也必须返回完成通知。草稿与回执共用 `ClientStorageNbt` 校验必要的复合标签列表，禁止把缺失字段或错误元素类型静默转换为空数据。

## 源码集与检查

- `src/main/java`：发布源码。
- `src/test/java`：JUnit 单元测试。
- `src/gametest/java`：游戏集成测试与恢复进程测试。开发运行加载该源码集，发布 JAR 不包含它。
- `src/testSupport/java`：单元测试和 GameTest 共用的类型化夹具；同时编入两个测试源码集，不编入发布源码。历史测试的等待、状态检查和失败注入放在这里；`testkit.ServerPlayers` 创建需要真实登录身份的测试玩家。
- `gradle/architecture.gradle`：检查包路径、生产代码与测试入口隔离，以及共享代码对客户端的源码依赖。工作区不得依赖网络；`core` 不得依赖游戏和业务；公共传输不得依赖客户端／服务端实现；入站队列与重连等待不得反向依赖业务。网络注册不得持有上传状态；上传包不得依赖客户端或网络注册入口，且每包最多 12 个类型。禁止重新引入退役的四边形算法枚举与旧求解入口。此检查不是完整的字节码依赖分析。
- `gradle/release.gradle`：发布时移除测试钩子并检查产物。

运行 `./gradlew.bat check` 执行单元测试、测试源码编译、目录与依赖检查、发布包检查。运行 `./gradlew.bat runGameTestServer` 执行游戏集成测试；可用 `-PgameTestDirectory=<目录>` 指定独立运行目录。

临时脚本和诊断产物放在已忽略的 `.temp/` 下。旧根目录产物保存在 `.temp/legacy-artifacts-2026-09-26/`，不作为源码参与构建。

## 项目根目录

根目录保留 `README.md`、Gradle 构建文件、源码、文档、开发工具与工具配置。NeoForge MDK 模板的 MIT 许可保存在 `licenses/neoforge-mdk-mit.txt`，清理模板文件名时保留原版权声明。Minecraft 开发运行仍使用常规的 `run/`。Gradle 输出与缓存仍使用 `build/` 和 `.gradle/`。

当前任务和问题分别集中在 `docs/plan/current_todo.md` 与 `docs/plan/design_questions.md`。未实施的设计草案放在 `docs/plan/proposals/`。旧 `issues.md` 只声明没有阻塞问题，未包含需迁移的问题条目。

恢复验证脚本位于 `tools/recovery/`，体素实验位于 `tools/voxel_lab/`。原根目录 `bin/`、`logs/` 和 `recovery/` 内容保存在 `.temp/legacy-artifacts-2026-09-26/root-cleanup/`。这次归档没有更改第三方工具的默认输出路径。
