# 架构重构进度归档：客户端输入与模式

返回 [进度索引](../2026-11-13-architecture-refactor-progress.md)。按时间顺序追加，不在此处维护当前计划。

## 作用域仓库与输入边界（2026-11-14）

- `ClientScopeRepository` 拥有作用域集合、当前作用域、激活边界、备用输入／选区和驱逐；总入口仅在共享 IO 队列及延迟回执保存空闲后调用驱逐。不再保留总入口的 playerSessions、独立当前字段、备用状态和 cleanupReplayed 集合。
- 清理重放标记归具体作用域，重新进入时重新设为待处理；重复激活同一作用域不会重复重放。跨维度、跨玩家／服务器的不同清理语义保留。草稿／回执的读取失败和未保存内容仍阻止驱逐。
- `hasLiveWork` 和 `canEvict` 由作用域表达；输入测试直接查询所属对象。三项输入边界测试改用真实仓库，不再反射构造会话总入口或调用其私有激活／草稿门禁方法；输入内部字段继续保持包内可见。
- `.temp/architecture-client-scope-repository-verified.log`：完整 check 与独立新目录全量 312 项必需 GameTest 通过。新增清理重放的每次进入／跨环境隔离测试。提交编排、OperationContext、Mode 与实机验收仍未完成；实际试玩存档未清除。

## 共享方块指针采样（2026-11-14）

- 原子文件发布阶段已提交并推送：`405d951`。
- 删除渲染包中的 `InteractionRaycastOwner`。新增环境持有的 `PointerResolver`，输入、工作区命中、智能编辑和世界／HUD 预览共用；普通轮廓、忽略可替换物的放置和不穿流体的选点分别缓存。命中及射线距离绑定返回，删除隐式读取“最后一次结果”的通道。
- 视图按玩家／世界对象身份、眼睛、视线和世界 tick 判断。帧开始、输入状态同步及环境重置显式失效，不再依赖 16ms 超时或多个用途互相替换。同帧同用途只计算一次；冻结交互的历史射线不改为读取实时指针。
- 六项新增测试覆盖用途交错且距离不串用、视角／tick／所有者变化、相同坐标复用、显式边界、异常恢复和双输入环境隔离。架构检查禁止恢复旧缓存及指针包反向依赖输入／渲染／会话装配。
- `.temp/architecture-shared-pointer-verified.log`：完整 `check` 与独立新目录全量 312 项必需 GameTest 通过；`git diff --check` 通过。
- 这是 PointerResolver 的方块采样阶段；工作区意图解析仍在旧渲染目录，完整 PointerState／PreviewFrame、Mode 注册与事件路由仍需后续迁移，不能据此视为手册 3.3 完成。实际试玩存档和正式构建仓库配置未改动。

## 选区指针能力与模型缓存所有权（2026-11-14）

- 方块指针采样阶段已提交并推送：`cdd7792`。
- 删除静态渲染目录的 WorkspaceInteractionResolver；`WorkspacePointerResolver` 持有所属选区及模型缓存，拖面从所属手势读取。失败位置、射线和创建候选通过构造注入，所有目标计算均不查询全局编辑器。缓存增加交互所有者身份校验，编辑器清理／离开环境释放自己的缓存。
- `SelectionCreationPointer` 接管 Alt 创建、已有工作区／本地草稿候选和服务端初始选点，保留原棱柱底面／高度计算与距离规则，不依赖渲染核心。OperationContext 装配时接收真实的 ClientInputSession，射线和创建门禁不在使用时改查全局当前输入。
- 输入直接访问所属指针能力，渲染复用其可见模型。原命中回归测试移至新包并使用独立选区／编辑器；新增跨选区目标隔离和失败位置动态更新／隔离测试。源码约束禁止恢复静态解析器、能力反向依赖总入口／输入／渲染，以及输入调用旧渲染命中转发。
- `.temp/architecture-workspace-pointer-final-check.log`：最终完整 `check` 通过（2473 项单元测试，0 失败，1 跳过）。`.temp/architecture-workspace-pointer-final-verified.log` 中独立新目录全量 312 项必需 GameTest 通过；该轮同时运行的测试编译曾失败，随后只修改测试夹具并单独完成最终 check，不将该轮整体构建记作成功。
- Mode／PreviewFrame／完整帧内意图缓存、操作树及其余手册项目仍待实施。正式构建仓库配置和实际试玩存档未改动。

## 客户端模式注册与输入策略（2026-11-14）

- 选区指针阶段已提交并推送：`0bbf865`。
- 新增 `ClientMode`、冻结的 `ClientModeRegistry`、完整 `ModeObservation` 和 `ModeInputPolicy`。空闲、起形、几何、选区、快速替换、填充和任务各自声明观察优先级与阶段策略；删除 ObservedInputState 和 State 内的重复分派表，草稿读取门禁也查询同一策略。模式定义不访问实时客户端／渲染状态。
- 注册拒绝重复 ID／阶段与空定义，冻结拒绝遗漏阶段；阶段表及优先级在注册时冻结，不受调用者后续修改影响。观察结果必须属于当前模式。State 继续拥有提交、取消、请求和手势代次，不在注册表创建第二套请求状态。
- 空闲与活动会话查询复用模式观察；保留任务遮盖交互、取消持续至空闲、提交期间忽略观察、SELECTING 允许工作区提交但不接受放置提交等原规则。
- 新增 512 组工作流组合优先级、全阶段提交／草稿准入、注册失败原子性、定义冻结、外来阶段拒绝及提交／取消所有权测试；原分派矩阵与输入测试全部通过。
- `.temp/architecture-client-modes-verified.log`：完整 `check` 与独立新目录全量 312 项必需 GameTest 通过。提交时的暂存区检查发现七个实现文件末尾多余空行；随后已清除，最终 `git diff --check` 通过。
- 本阶段仅完成 Mode 的观察与策略注册，具体按键／指针／滚轮处理器、服务端 Mode、PreviewMode／PreviewFrame 与其余操作树迁移仍待完成；没有将整个手册标记为完成。正式构建仓库配置和实际试玩存档未改动。

## 各模式拥有滚轮决策（2026-11-14）

- 模式观察阶段已提交并推送：`c24b904`；末尾空行与校验记录修正：`5a4ff26`。
- ClientMode 新增 onScroll，注册表按当前阶段唯一所有者调用，只有 PASS 才回落到空闲模式。模式接收不可变的 ScrollInput／ScrollView，返回类型明确的命令；不查询实时客户端或总入口。删除 ScrollInputSemantics 及原集中式分派规则，输入边界保留物理状态捕获与排队。
- 起形、几何和未绑定工作区的选区复用候选滚动规则；已有工作区先处理移动／堆叠；填充与任务阻止滚动，空闲与快速替换交给原版。零增量、修饰键、原版交互接管等既有优先级不变。
- 穷举所有阶段、16 组上下文及 5 种滚轮增量，对照迁移前规则；另测 PASS 回落、输入事实对象复用、空闲不自递归和错误结果拒绝。新增源码约束禁止恢复旧分派器。
- `.temp/architecture-mode-scroll-verified.log`：完整 `check` 与独立新目录 312 项必需 GameTest 通过。Alpha 当前格式策略继续生效，实际试玩存档无需清除。
- 按键／指针模式处理、服务端 Mode、完整 PointerState／PreviewFrame、操作树及其余手册项尚未完成，继续推进。

## 专用工具按键由模式处理（2026-11-14）

- 独立绘制组件阶段已提交并推送：`5d9f3f2`。
- ClientMode 新增 onKey，KeyInput／KeyView 为不可变事实，KeyDecision 表达动作；注册表按所属模式调用，只有 PASS 回落到空闲。QuickReplaceMode 处理 R 与 Ctrl+Z／Y，SmartFillMode 处理 F／Enter，IdleMode 声明工具进入。ClientModeKeyInput 在输入边界执行动作，模式不引用客户端总入口／网络／渲染。
- 删除总输入中的工具按键 if 链及 ClientSmartFillSession 的物理按键判断；填充能力只保留 start／confirm。保留绑定和历史冲突优先、任务快照到达时已有工具优先、松开与重复事件只消费不二次确认、Ctrl+F 不触发填充等规则。物理按压时的首个填充请求仍立即建立，避免快回复将排队副本误当作确认。
- 穷举所有阶段、128 组上下文／修饰状态、8 个按键与三种动作，对照原规则；补充填充确认／取消通过及活动会话不继承空闲入口用例。
- `.temp/architecture-mode-key-verified.log`：完整 `check` 与独立新目录 312 项必需 GameTest 通过；实际试玩存档和正式构建仓库配置未修改。
- 这是专用工具键迁移，通用快捷键／取消／提交和指针处理仍在原输入流程；服务端 Mode、完整 PreviewFrame／PreviewMode、操作树与其余手册项目尚未完成。

## 普通鼠标目标由注册模式决策（2026-11-14）

- 网络预览应用阶段已提交并推送：`e16374e`。
- ClientMode／注册表新增 onPointer。`PointerInput`、密封的左右事实与决策放入独立子包；只有 PASS 回落 Idle，返回空值或左右类型错配在边界拒绝。删除 MousePressRoutingSemantics，起形／几何／选区分别拥有普通按压规则，稳定的目标捕获优先级复用 PointerCaptureRules。
- 输入按原捕获路由调用模式，再执行原有穷尽动作分支。保留几何右键与选区右键差异、原版接管优先、工作区目标优先、确认选区手柄捕获等行为。入口、Alt、路径、先前手势释放仍先处理；未把普通按压决策扩大到所有鼠标生命周期。
- 对照迁移前规则，穷举 131072 组左键事实 × 四个阶段、8192 组右键事实 × 三个阶段；另测松开／重复／其他键不被认领、PASS／BLOCK 回落及错误处理。新增源码约束禁止恢复旧分派类。
- `.temp/architecture-mode-pointer-tests.log` 定向测试通过；`.temp/architecture-mode-pointer-verified.log` 完整 check 与新目录 312 项必需 GameTest 全部通过。实际试玩存档和正式仓库配置未改。
- 通用按键／取消／提交、完整模式鼠标生命周期、PreviewFrame／PreviewMode、服务端 Mode、操作树及手册其余项目仍待实施。

## 起形指针、路径闭合与输入事实（2026-11-14）

- 几何交互阶段已提交并推送：`0a5b1d1`。QuickShapePointer 按输入所有者持有，接收快照、上下文和既有用途射线，统一起形候选与放置上下文。QuickShapeModes 共享 Alt 嵌入、面偏置、阶段和滚轮规则，生成器不再是输入查询来源。
- PathCloseTarget 显式接收三类开放路径与候选；保留选区 → 几何 → 起形的判定顺序、悬停最少 3 点／双击最少 2 点和路径首点世界遮挡。按压时冻结的 PathClosePress 与既有请求 revision／scope 不变。
- 输入通过会话系统读取选区是否活动和滚轮上下文；ModifierReticlePolicy 接管嵌入／半格准星语义，绘制只消费结果。删除 Core 中对应业务查询和重复候选算法约 190 行；输入对 Core 剩余调用为反馈通知、清理及预览 tick，仍待进一步解耦。
- 新增有效模式组合与候选计算的迁移前后等价测试、固定多边形高度／无上下文／所有者隔离测试，以及路径不同最少点数／优先级／遮挡测试。扩展源码守护禁止起形指针依赖渲染／输入／会话，禁止输入恢复已迁出的查询。
- `.temp/architecture-quickshape-pointer-tests.log` 定向测试通过；`.temp/architecture-quickshape-pointer-verified.log` 完整 check 及独立新目录 312 项必需 GameTest 全部通过，退出码 0。首次编译漏导入 BlockPos 已补齐。
- 后续仍为前文第 2～7 项；没有将本阶段当作完整 InputView／PointerState 或模式生命周期完成。试玩存档和正式构建仓库配置未改，尚未进行本阶段客户端手感验收。

## 输入反馈与指针生命周期所有权（2026-11-14）

- InteractionFeedback 归所属 ClientInputSession，拥有自由滚轮过渡、计时衰减和最后一次 Gizmo 值。HUD 只消费显示数据和透明度；删除输入对渲染 Core 的全部调用与残留 import。reset／环境切换一起清理所属反馈，新增双所有者隔离与非有限基线测试。
- ClientSessionTick 在生命周期装配层保持原有顺序：绑定配置、暂停声音恢复、玩家观察、入站、输入队列、操作结算、遮罩、重连、预览结果、输入推进。ClientPreviewSystem 拥有起形／几何预览能力与共享失败节流，输入不再推进渲染入口。
- 删除输入会话重复保存的 pointerGestureToken；捕获／校验／迟到释放均读取 PointerGestureState 的唯一活动 token。PointerDragSnapshotView 的操作手柄查询依据传入会话捕获类型，不再读取全局当前选区状态；新增隔离和被替换捕获回归。
- `.temp/architecture-client-geometry-bindings-tests.log` 定向测试通过。`.temp/architecture-modes-feedback-verified.log` 完整 check 和独立新目录 312 项必需 GameTest 通过，进程退出码 0。资源重载竞态补丁在该轮之后另做定向验证。
- 整份手册未完成：大输入／预览入口、完整模式生命周期、全操作树、全包迁移与客户端实测仍待办；没有修改试玩存档或正式构建仓库配置。

## 本地选区变换手势完整能力（2026-11-14）

- 新增 `client/interaction/selectiongesture`：实例绑定真实工作区、只读场景、变换端口与同一份指针 token；内部拥有面／Gizmo 状态、EditToken、源等待、完成与取消。旧 SelectionGestureController／SelectionGestureState 及外部裸 begin／clear 已删除，不保留别名。
- OperationContext 组装能力，ClientInputSession 只绑定能力并提供原指针 token；reset、离开环境、清空工作区及草稿挂起先取消所属编辑。排队松开／源回调只认原 capture，旧回调不能清理新租约。编辑取得过程中端口失败会释放本次 EditToken 和已获 pointer token，保留原异常及清理 suppressed。待完成 resize 按原机制转交变换能力收尾。
- PointerDragSnapshotView、工作区指针与渲染只读 SelectionGestureSnapshot；纯拖动值和数学迁入 `client/interaction/drag`。AabbAdjustDecision 移到共享值模型，避免手势端口与变换实现形成包环。保留 140ms 面短按、Ctrl 自由旋转／默认90度、repeat stride 与局部轴计算。
- 新增 15 项能力／旋转规则测试及 2 项真实作用域所有权测试；排队按压／松开、作用域与指针测试改用真实能力入口，删除旧裸状态注入和相关反射。旧类型引用与能力反向依赖静态扫描均为0，diff whitespace 检查通过。Gradle 与 GameTest 由主代理统一执行，本阶段尚未据此声明运行通过。
- 本批只收口本地选区面／Gizmo 变换。远端确认选区、几何／起形手势及完整操作树生命周期仍按剩余计划推进。
