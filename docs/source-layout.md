# 源码结构

Java 包统一位于 `io.github.fastformer` 下。按职责放置代码，测试使用与被测代码一致的包路径。

模组包顶层只保留 `FastFormer` 入口。`fastplace` 顶层不直接存放 Java 类。

| 包 | 职责 |
| --- | --- |
| `client` | 客户端输入、交互状态、界面和预览 |
| `client.input` | 输入会话与紧密协作的调度控制器；规则、状态和通用手势分别放在 `policy`、`state`、`gesture` |
| `client.operation.input` / `client.quickshape.input` | 选区操作与快速起形各自的输入规则、事件和捕获状态 |
| `client.interaction.intent` | 交互上下文与意图解析 |
| `client.session` | 客户端会话、受限磁盘任务与草稿／提交回执存储 |
| `client.session.reconnect` | 有界快照等待；不持有草稿、不决定恢复行为 |
| `client.network.inbound` | 客户端载荷类型注册、连接校验与 tick 投递 |
| `client.network.outbound` | 可选通道检查与向当前连接发送请求；设置界面和放置网关共用 |
| `core.registry` | 无游戏依赖的注册表；拒绝重复键，装配结束后冻结 |
| `core.command` | 类型明确且冻结的 IntentBus；先校验 Gate，再执行处理器 |
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
| `server.settings` | 设置的类型化处理器、修改与查询、权限校验及反馈 |
| `server.network` | 服务端通道注册；统一线程投递和网络到 Intent 的转换 |
| `server.network.upload` | 单服务器上传准入、分块／解码资源、请求作用域、回执与取消 |
| `server.sync` | 服务端预览装配与发布；玩家作用域持有 revision、提交参数和回调身份 |
| `server.session` | 单服务器会话系统；拥有快速起形、几何、选区工作流及任务队列 |
| `server.task` | 统一任务／推进结果／取消契约、恢复优先调度、队列所有权与恢复移交 |
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
| `fastplace.world` | 世界写入、事务、写入锁与任务预算 |
| `fastplace.world.memory` / `fastplace.world.snapshot` | 内存准入与预留；可逆方块快照 |
| `fastplace.task.runtime` | 三类写任务的租约、日志、事务、提交、预算与批次指标生命周期 |
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

`ServerInputDispatcher` 从所属玩家作用域获取 `ServerInputState`。请求水位和手势是单个玩家的字段，不再使用全局 UUID 表。双击快照和扳手 token 使用同一生命周期；退出和玩家实体替换丢弃瞬时状态，换维统一重置。活动工作流、停放会话和世界任务仍由各自的仓库持有，完整服务器实例化尚未完成。

`PlayerIntentSystem` 装配共享的 `IntentBus`。会话、历史和设置处理器分别持有自己的门禁和动作；命令与网络构造同类意图。具有请求 ID 的原入口继续先校验序列和作用域，再把 ID 传入意图上下文；不含 ID 的请求使用 0，不能据此绕过功能自身的去重规则。拖动、上传和部分业务动作尚未迁入 Intent。

`ServerChannels` 为 37 个服务端通道统一执行服务端线程投递与玩家类型检查。注册处选择 `handle` 或 `toIntent`；业务处理器仍负责请求身份、手势、回执和上传校验。客户端连接校验继续由原入站队列持有。

原 `network.payload` 已移空，不提供旧包路径的转发类。收发方向不能互相导入；共享作用域、设置声明和上传分块契约各自独立。协议检查禁止引用客户端、服务端、旧网络实现及玩家设置对象，且每包最多 12 个类型。协议还引用部分尚未搬迁的 `fastplace`／`workspace` 共享领域类型，完整四层迁移仍未完成。

`BuildingPreviewSnapshot` 是共享预览值，不是消息。删除未注册的旧合并预览 Codec，仅发送当前会话、参数和效果三个分包；接收方验证 revision 后再组合。服务端 `QuickShapePreviews` 负责从草稿和设置装配快照；`PreviewSync` 负责发布。压缩设置改用 `HistoryCompressionLimits`、`CompressionTier` 和 `CompressionSetting`，客户端不再为显示配置而依赖服务端历史实现。

`PreviewSyncState` 由玩家作用域持有，不再使用全局 UUID 表。revision 查询与清理不创建状态；同 UUID 的旧玩家实例不能清除新实例的发布。换维先通知旧环境失效，再恢复目标维度工作流并发布新快照；断线和服务器关闭由玩家作用域统一丢弃，删除预览同步的全局 `clearServer` 入口。

`SessionSystem` 按服务器身份显式启动和停止，实例持有 `QuickShapeSystem`、`OperationSystem`、`GeometrySystem`。三者分别拥有自己的 `PlayerSessions` 和任务队列；不再保留旧静态 Manager 或兼容转发。UUID 工作流与世界任务在断线后继续保留，换维仍按维度停放和恢复。服务器关闭先结束准入，再统一取消任务、释放资源并清空工作流；一个清理失败不会跳过其他任务，旧 resume 回调不能推进已关闭的实例。停服后的迟到 logout 使用非创建查询。

物理 Alt 状态由玩家作用域的 `InteractionModifierState` 持有，断线、换维和实例替换归零。恢复或重新绑定草稿前以当前物理状态更新其修饰键；旧实例的迟到清理不能改变新实例的草稿。会话系统的实例化不等于业务拆分完成，三类系统的进一步职责拆分及剩余服务端状态仍待迁移。

`WorldTaskScheduler` 只负责恢复 → 起形写入 → 选区写入 → 提交账本的推进顺序，不持有玩家消息。写入闸门关闭后仍分别尝试两类任务的恢复移交；调度异常继续关闭闸门并保留日志与租约。原 `WorldTaskFeature` 已删除。离线消息由 `SessionSystem` 的 `TaskFeedback` 持有，不跨服务器或存档共享；玩家重新登录后发送一次。`WorldTaskContext` 只向仍存在的所属运行实例投递，关闭后的迟到消息被丢弃，不创建备用全局状态。

`WorldTask.tick(context)` 是三类写任务的统一推进入口，`TaskStep` 区分等待与终态。`LeasedJournaledTask` 统一执行维度检查、内存准入、租约获取和批次指标采集；子类实现 `tickBody`，起形的生成前置步骤和小操作预算通过钩子保留。已返回终态的任务在历史发布等待期间不重复执行算法。取消先停止准入；恢复移交保留工作集，停服取消则等待日志和发布不再使用数据后释放预算。

`WorldTaskQueue` 核对任务对象身份后才推进；恢复移交中的槽位、后继任务和已关闭队列拒绝旧 resume。关闭先清空槽位并结束准入，再统一逐个取消；一次清理失败仍继续清理其他任务。已删除无身份删除、直接清空及两套旧任务接口。队列交接仍等待日志追加和历史发布终止，再接受事务快照。附属资源清理失败仍释放共同资源；工作区 tick barrier 先解除引用再关闭，重复清理不会再次关闭它。具体任务和资源运行层尚未迁入服务端任务包。

写入租约的启动和停服清理只作用于对应服务器，已删除全局 `clearAll`。清理一个服务器及其迟到日志回调不能释放另一个服务器的租约；测试也仅清理自己创建的服务器标识。

`RecoverySystem` 按服务器显式启动和关闭，持有恢复执行器、`RecoveryWriteGate` 和已提交日志的保留状态。恢复期间关闭写入；仅当前版本的成功恢复能重新开放，较早结果、后续故障或运行实例关闭均不能被覆盖。`WorldTaskContext` 保留原闸门身份，派生 resume 上下文继续使用它；迟到任务不能改用替换后的恢复系统。无服务器上下文使用独立或显式注入的闸门，没有全局备用状态。

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
