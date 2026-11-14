# 架构重构进度（2026-11-13）

依据：[重构手册](2026-11-13-architecture-refactor-manual.md)，已重新读取包含 9.5.1 共存规则的 1235 行版本。目标仍是完成整份手册；以下区分已验证项、当前改动和未实施项。

## 已完成

- 用 `Registry`、`ClientboundTransport`、`ClientInboundSystem`、`InboundPayloadQueue` 替换客户端反射分发。20 种载荷通过类型明确的注册关联处理器与校验规则，不再维护载荷 switch 和成组重载。公共侧仅引用公共传输入口。
- 保留三种不同校验：建立预览会话、已建立会话的回调、当前连接回执。校验仍在 tick 消费时执行。切换世界使当前批次失效；重入投递延后；处理异常丢弃批次剩余事件，并允许后续 tick 继续处理。
- 输入与选区共享所属玩家的手势状态。修复无世界时两套备用状态互不一致的问题，备用状态也由会话所有者一起装配。指针视图、输入分发和手势结算使用传入会话。
- 输入重置复用指针清理，补上 `operationTransformCapture` 的释放，删除重复取消。
- 两套重连等待复用 `ReconnectSnapshotWindow`，保留 40 tick 上限、快照优先与超时后保留草稿的行为。
- 补充注册、投递、旧连接、批次失效、异常恢复、玩家隔离、备用状态、变换捕获清理及等待边界测试。新增对应源码依赖约束。

## 验证

- `./gradlew.bat --offline check`：通过。2204 项测试，0 失败，1 跳过；另包含 GameTest 源码编译、架构约束与发布产物检查。
- 独立目录运行快速替换、空闲入口、扳手入口 GameTest：26 项全部通过。
- 全量 `runGameTestServer`：在独立新目录中 268 项全部通过。此前复用旧测试世界时，故障注入留下的 `index.dat` 目录造成 `AccessDeniedException` 并干扰后续测试；隔离历史持久化测试 13 项通过，随后新目录全量通过。该结果对应后续持久化改动之前的代码，不能代替最新版本的全量验收。
- `./gradlew.bat --offline runClient`：已启动，完成客户端初始化、资源加载与声音引擎启动，未见启动异常。尚未验证进入世界后的全部交互。
- 日志与隔离脚本保存在 `.temp/architecture-*.log`、`.temp/architecture-client.init.gradle`，未改变正式构建文件的仓库配置。

## 后续阶段

1. 最新持久化与输入改动完成后，重新运行 check 和新目录全量 GameTest。
2. 继续拆分客户端生命周期与业务控制器。目前渲染和控制器仍有全局入口，不能视为客户端已全部实例化。
3. 验证世界任务租约／日志的共同生命周期，再提取运行单元；随后处理上传接收流程。
4. 服务端实例化已有历史、玩家作用域及后台任务的实现；其余业务状态、Intent／Mode 系统与全包迁移仍未完成。Alpha 阶段不保留旧格式兼容代码；算法分支仍需按当前调用关系验证，不能仅按名称删除。

## 缺陷修复与持久化：当前改动

- B1：点击捕获改为 PointerButtonCapture，空状态和左键归属不再用同一个整数隐式表达；释放路由改用传入会话的手势。输入与指针相关定向单元测试通过。
- B2：分段 v2 对实际保存的二进制 NBT 字节计算 CRC32C。按用户最新要求，已删除 v1 SNBT 校验与兼容读取。
- B3／B4：history 只支持批次 v2；历史和恢复严格检查当前 Minecraft DataVersion。已删除 v1 默认版本、DataFixer 迁移、单文件恢复日志读写及其路径分支。旧版本测试改为拒绝读取，现有恢复测试改用当前分段格式。隔离后的启动恢复尚需专门验证。
- Alpha 兼容清理：工作区计划拒绝 v1，工作区／草稿不再补旧版 Symmetry；删除旧压缩设置、旧木框开关和旧 reach 自动迁移。试玩服务端旧数据允许直接清除，当前尚未清除实际试玩存档。
- B6：队列拒绝或磁盘写入失败时保留未发送回执并重试，不再忽略准入失败。队列满和磁盘失败测试通过。
- B7：断线和清工作区复用临时状态清理，保留已挂起草稿；定向控制器测试通过。
- B9：主题选择和资源安装使用单调 revision；定向测试通过。
- B12：serverEpoch 使用 volatile，上传解码准入和结果发布断言服务端线程。
- AtomicFiles 已用于历史信封和恢复文件；原子移动失败不降级为非原子覆盖。文件先 force，再原子替换。支持目录 fsync 的平台会同步目录；Windows JDK 无目录句柄支持时记录一次平台限制。原子写入与失败保护测试通过。
- B8：重复上限集中为 `FastPlaceSettings.MAX_PLACEMENT`，保留既有 20,972,152 上限，不将推测的笔误当作行为修改依据。
- B5：离开的耐久草稿作用域在存储空闲、无未保存替换、无脏回执且无加载失败时驱逐；保存完成后再次检查，按草稿对象身份区分已保存版本。失败作用域仍保留，完整 ClientScope 迁移尚未完成。
- B10：删除仅存内存的 pendingRecords 成功路径及其无调用重载。捕获失败明确返回失败并记录日志，由事务保留快照和租约、恢复原状态或转交恢复；恢复捕获失败记录一次并保留重试所有权。
- B11：统一 PointMotionLimits 与 ClientPointValidator，检查单包每轴 128 格、世界高度／边界、编辑射线范围；分组移动先整体校验再修改。远距离编辑保留原编辑范围，不套用原版 reach。

## Alpha 旧格式清理验证

- `--offline check` 通过，日志 `.temp/architecture-current-format-check.log`。
- 新世界全量 GameTest 274 项通过，日志 `.temp/architecture-current-format-runtime2.log`。首轮活塞事务用例与同批其他写事务竞争，已分配独立批次并在失败消息中报告事务 Outcome；没有修改游戏事务的准入规则。
- 移除单文件恢复后，修正分段回滚对最终修正数据的反向遍历；乱序修正须在首次世界写入前拒绝。提交重放、未提交回滚和耐久保存失败重试均通过。

## 数据化交互绑定 / 长按画线

- 数据基础已接入：扁平／树状绑定 Codec、字段注册与匹配、动作注册、用户按 id 覆盖、资源重载、options.json 队列原子保存、自动设置项。选项默认关闭。整棵旧操作树尚未迁移，不能视为第 9 节完成。
- 顺序：BindingSystem 数据加载／匹配／动作注册与输入仲裁 → 服务端 SessionProfile 约束 → 长按画线及回归测试 → 按手册顺序迁移现有操作子树。
- 长按画线默认关闭；启用后 deferred 首按、短按补发一次、松开提前提交。固定 AXIS，仅线阶段，不修改玩家原有模式设置。
- 用户覆盖、资源重载、自动设置项、异常取消及默认绑定行为对照测试均属于完成条件。
- 已接收 9.5.1：先处理既有入口保护；Alt、远距离单击、有效双击第二击、扳手和可交互方块优先。长按只占剩余的近距离普通放置。
- 短按补发使用按下时冻结的黑框 hit、eye、view、流体规则和原始时间戳，复用首次快照 → 原版使用的顺序；不重新采样，不变更服务端首次锚定语义。
- 长按入口及结束不启动 entryPressGuard；松开确认、取消、写入完成均清理 idleEntry。水中规则在按下时冻结。
- 双击窗口暂保留原始按下时间；尚未做游戏手感实测，不能报告成功率结论。只有实测证明明显下降时，才允许改为补发时刻计时，距离分档和余量保持不变。
- 服务端注册并同步 SessionProfile；入口只接收已注册 ID，校验嵌入与可达条件；draft 保存不可变约束。预览同步有效线模式和 profile，中键确认、模式切换与多边形闭合入口执行约束，不覆盖玩家设置。协议版本升为 82。
- 长按输入由所属 ClientInputSession 持有：冻结按下、短按复用入口后补发原版 use、阈值起形、松开保留冻结射线等待服务端预览后走现有 MIDDLE。失焦／界面／世界／物品／超时取消。尚需客户端实机验证延迟补发、混合模组和快速松开的手感。
- 最新 `check` 通过；新增绑定 Codec、选项往返、覆盖、阈值松开、原时间戳补发和取消测试。此后补充的共存测试仍需重新执行。
- 新目录全量 `runGameTestServer` 274 项全部通过，包含 4 项 HoldLineGameTests：嵌入首点、锁定模式且保存设置不变、中键开关关闭仍提交、未知／超距变体拒绝、水面冻结锚点。日志：`.temp/architecture-hold-runtime.log`。
- 七项共存要求已有生产原语的组合测试与水中服务端用例；近／远双击、保护、Alt 优先和立即单击尚未完成客户端输入链的游戏验收，不能以这些单元测试替代手感实测。

## 异步任务、准入与维度停放

- TickMailbox 迁入 core.async，删除旧客户端包装。LatestOnlyTask 统一建筑生成、外壳和待确认网格的代次、取消及结果消费，各业务仍保留自己的退避／回退策略；执行器拒绝不会抛入渲染回调。
- IoExecutors 统一命名、有界串行队列及共享关闭期限。客户端工作线程由 ClientBackgroundTasks 统一拥有，进程退出时关闭；不再按存储队列安装 JVM shutdown hook。
- ServerBackgroundTasks 按 MinecraftServer 身份持有 history-io、history-compaction、recovery，SERVER_STOPPED 关闭。创建必须经过启动，迟到回调不能重新创建已停止服务器的工作线程。存储与历史发布显式使用所属执行器；恢复队列拒绝转换为可处理失败，已提交日志保留供后续恢复。
- AsyncTasks 用可取消的队列任务承载完成结果。关闭超时时，队列丢弃同步结束对应 Future 及其后续链，避免长期保留快照、内存预留和历史队列计数。运行中任务仍须响应中断；超时记录错误。
- Gate 统一可用、所有者空闲、世界锁、会话与历史准入。取消／恢复不经过编辑准入。原版 useItem 在历史观察事务内会再次发布物品事件，因此仅对同玩家、同世界、同观察事务且持有租约的继续事件放行；新写事务仍要求世界未锁定。
- PlayerSessionSlot 和 PlayerSessions 统一快速起形、选区、几何的 active／parked 生命周期。结束活动会话保留停放会话，完全移除清除两者；恢复冲突不消耗停放会话。删除旧 DimensionSessionStore；仍未完成服务端 PlayerStateRegistry 实例化，不能视为阶段 3 完成。

## 本轮验证

- architecture-async-runtime.log：新目录全量 277 项 GameTest 通过，覆盖 B10／B11、当前格式隔离与异步预览。
- architecture-server-workers-check3.log、architecture-session-slots-check.log、architecture-session-admission-check.log：check 通过。
- architecture-admission-runtime.log：新目录全量 277 项 GameTest 通过，包含共享 gate、服务器工作线程和维度槽改动。首次运行暴露受控队列测试未推进新增压缩任务、原版水面放置被自身租约拒绝；均已修复并重跑通过。
- architecture-shutdown-futures-check.log、architecture-shutdown-runtime.log：check 和全量 277 项 GameTest 通过，包含排队 Future 取消、拒绝准入、回调／发布取消及历史持久化排序测试。
- 试玩存档尚未删除。测试均使用独立 .temp 目录；未提交或推送 Git。

剩余主线仍包括：服务端业务状态的完整迁移与任务运行单元、历史条目扩展及包内职责拆分、Intent／声明式协议、ClientScope／Mode／PreviewFrame／PointerResolver、完整操作树迁移、全包迁移与反射测试清理。

## 解码队列、共享规则与玩家作用域

- WorkspaceDecodeQueue 不再创建线程。workspace-decode 由所属服务器创建和关闭，队列容量为 2；任务取消同步结束 Future，并按代次保护旧回调，拒绝准入不会占用容量。测试使用显式注入的执行器。
- 删除无生产调用者的 BoxGenerator、ScalarAdjuster、BoundaryConstrainedFaceRasterizer、ConvexPolyhedronGenerator 和旧 HistoryStore。后者的 GameTest 已迁至当前 HistoryBatchStore，仍验证磁盘往返；没有删除实际使用的算法路径。
- core.rule 的 RuleChain 冻结有序规则，区分未匹配与终止结果。扳手拆为 11 条具名规则，保留 property → copper → structure → state → Shift → ground → torch → wood → shape → surface → generic 的顺序。明确的 PASS 会终止，不会继续执行兜底。
- 扳手纯预测迁入 common.tinker，按 data、state、shape、rule 分包，服务端准入与事务留在原入口。预览与实际操作复用同一规则；规则 ID 使用 ResourceLocation，外部可组合标准规则和自定义规则。新增公共侧依赖检查。
- core.lifecycle 提供惰性类型化 Scope。生命周期遍历会继续清理其他单元，并汇总运行时失败；关闭后不能重新创建状态。PlayerStateRegistry 按服务器身份隔离玩家，按玩家实例校验替换与迟到回调。
- 双击入口快照和扳手 token 已迁入玩家作用域；输入序列和手势也已迁入，内部改为单玩家字段，删除重复 UUID 映射。Logout／Clone 清理瞬时状态，换维执行统一通知；原有 UUID 工作流、停放会话和世界任务保留其生命周期。
- architecture-decoder-runtime.log、architecture-tinker-layout-check.log、architecture-tinker-rules-runtime.log：解码生命周期／清理版本全量 277 项及扳手规则版本 check／全量 277 项 GameTest 通过。
- architecture-player-scopes-runtime.log：check 和全量 278 项 GameTest 通过，新增作用域 detach／换维／丢弃／禁止迟到重建用例。该结果早于随后输入状态迁移；最终验证尚待更新。
- architecture-input-lease-runtime.log：check 和全量 278 项 GameTest 通过，包含单玩家输入字段及三类写入任务共享 TaskWriteLease。工作区特有的 tick barrier 仍由工作区任务管理。
- JournalSegments 已接入普通放置、选区操作和工作区提交，统一待写日志快照和已确认游标。只接受当前批次确认，重试使用同一不可变批次；追加期间捕获的新快照不会改变正在写盘的批次。
- architecture-task-segments-runtime.log：全量 278 项 GameTest 通过；同次 check 中 3 个新增单元测试错误初始化游戏注册表。夹具已改为仅构造游标所需数据，architecture-task-segments-check2.log 的完整 check 通过，2256 项单元测试、0 失败、1 跳过。
- 原子文件流补上批量 write 转发，避免 FilterOutputStream 将大块压缩数据拆成逐字节通道写入；关闭与 force／原子替换顺序保持原有约束。

## 快速替换、智能填充与 Alpha 兼容清理

- 快速替换、智能填充原有的 6 张静态玩家状态表已删除，模式、笔画、采样去重、预览和请求去重均由 PlayerStateRegistry 的类型化作用域持有。换维、离线和丢弃统一清理，不需要每个入口列出这些表。
- 共享替换目标预测和自适应射线采样迁入 common.replace；服务端替换状态与写入入口归 server.replace，填充入口归 server.fill。长按替换仍复用同一笔画历史，保留补采样与松开后的迟到请求拒绝。
- 补充快速替换和智能填充的换维、断线与重新绑定回归测试。修正快速替换生命周期测试的初始化：先等待模拟玩家历史加载，再断言启用，避免把异步准入等待误判为清理失败。
- 复查发现客户端仍有旧字段兼容：缺失 SecondPointOnly 时按旧选点语义解码，缺失 CleanupPending 时推断清理状态。现已删除，草稿所有权也要求当前格式的 Unsubmitted 标记，已提交数据必须有提交 ID 与来源。拒绝缺失字段及矛盾所有权，不删除正常的未提交草稿路径。
- 更新手工构造提交数据的测试夹具；普通会话存储测试使用独立 ClientSessionManager，避免全局状态污染其他输入测试。必须覆盖全局控制器的重连测试显式提供临时存储路径。
- `.temp/architecture-alpha-verified.log`：完整 check 与新目录全量 GameTest 通过，280 项必需 GameTest 全部通过。随后客户端格式清理的最终 check 见 `.temp/architecture-alpha-client-format-check3.log`，包含单元测试、GameTest 编译、架构及发布包检查。
- 试玩旧数据可直接清除的授权已写入手册；本轮使用独立测试目录，没有清除实际试玩存档，没有提交或推送 Git。整份重构手册仍有未实施主线，以前文剩余项为准。

## 恢复与历史服务实例化

- 原 `fastplace.history`、`fastplace.recovery` 的生产、单元测试和 GameTest 已迁至 `server.history`、`server.recovery`。历史侧实现恢复 SPI 后注入启动恢复，架构检查禁止恢复实现导入历史实现。
- 恢复职责拆为日志实例、`RecoveryJournalCodec`、`JournalStartupRecovery`、`JournalReplayer`。只保留当前分段格式；严格检查 palette 布局、当前 DataVersion、分段顺序和修正对齐。存储路径与 ASCII 分段命名集中到 `StoragePaths`。
- `DurableCommit` 显式执行批次发布 → 日志封存 → 索引发布。索引失败按原有延迟重试，不重复发布批次或封存；启动历史协调失败会保留封存日志，成功重试后才清理。
- `HistoryService` 取代全静态 `WorldHistoryManager`，每个服务器一个实例，显式注入可用／活动 Gate；`HistorySystem` 只允许在服务器线程启动、查询和停止，查询不会创建实例。`OwnerHistory` 持有单玩家状态，统一计算 busy／needsTick 的共同条件，保留加载失败与持久化重试的行为差异。
- 十个 `startRollback` 重载已统一为 `rollback(RollbackRequest)`。任务队列、接管、唤醒与事务显式传递所属历史服务；迟到回调仍按任务和玩家对象身份校验。
- 删除静态 `WorldHistoryPersistence`。`HistoryService` 持有 `HistoryRepository`；磁盘实现持有自己的存储、队列预算和玩家顺序链。`HistoryWriteQueue` 在执行前预留顺序，支持同步重入；取消调用者 Future 不释放实际写入的预算。关闭拒绝新请求，已接收写入继续完成。
- 测试显式注入无磁盘存储，不提供 null server 的全局备用服务。补充服务隔离、关闭后禁止重建、队列隔离、取消、拒绝与同步重入测试。两处任务调度测试改为包内类型化调用；恢复通知失败测试改为注入反馈，删除其反射与 Unsafe 服务器外壳。
- `.temp/architecture-history-repository-verified3.log`：完整 `check` 和新目录全量 GameTest 通过，281 项必需 GameTest 全部通过。此前运行暴露旧反射签名、未启动服务器测试外壳，以及局部测试服务未 tick 的夹具问题，均已修正；不放宽生产线程和生命周期限制。
- 历史服务的 14 个 `*ForTest` 入口已移出生产代码；`src/testSupport/java` 中的 `HistoryTestAccess` 同时编入单元测试与 GameTest。删除旧 `WorldHistoryManagerTestAccess` 桥接。历史顺序与快照合并由 `OwnerHistory` 自己维护；删除只被测试调用的重复合并算法，测试改用实际 `HistoryMemoryCache.merge`。
- `.temp/architecture-history-test-support-verified2.log`：共享夹具迁移后的完整 `check` 与新目录全量 281 项必需 GameTest 通过。启动恢复测试只对自己的未提交 operation 禁止历史协调，其他日志交给真实协调器，避免同批日志影响断言。
- 关闭历史服务时，由 `OwnerHistory.discard` 取消初始加载、分页与压缩结果，再释放重放任务。取消日志失败仍会释放任务内存；同一任务不会重复释放。新增初始／分页结果取消和迟到结果拒绝测试。
- 继续复查旧格式入口后，删除仅被测试调用的 `ClientSessionManager.forPlayer` 旧作用域入口和长方体 `expand` 别名；测试改用实际的 `forScope` 与 `expandTo`。当前算法所用的 Set／生成结果适配器仍有生产调用，不将这些 API 适配误认为旧磁盘格式。
- 最新 `.temp/architecture-history-cleanup-verified.log`：完整 `check` 与独立新目录全量 281 项必需 GameTest 通过，包含共享测试源码、清理生命周期与旧作用域入口移除。`git diff --check` 通过；正式构建仓库配置未改动。
- 当时尚未完成：历史包职责拆分及条目扩展基础。后续进展见下一节；其他生产类的测试钩子、其余手册主线和长按画线的实机手感验收仍未完成。

## 历史目录与严格条目格式

- 历史拆为 entry、store、compaction、memory、replay、vanilla；根包保留装配、服务与仓库。各包补职责说明，检查每包最多 12 个类型及依赖方向。压缩输入和结果均为不可变快照；独立的 HistoryReplacement 消除内存容器对压缩器嵌套类型的依赖。
- 删除 WorldChangeBatch.fromPairsForTest，测试使用生产捕获 API。快照位置错配仍按真实契约拒绝；测试归入所属包，没有为测试公开内部冲突解析器、压缩作业或 NBT 辅助入口。
- 删除旧 vanilla 分组键归一化和缺失 CompressionStage 的推断。区域缓存缺失或损坏时重算实际 cells 的行为保留，满足 AABB 不消失的要求。
- 引入 HistoryEntry、类型明确且冻结的 EntryCodecRegistry。存储入口按必填 Type 分派；缺失、未知或类型不匹配均拒绝，不补旧格式。BlockChangeEntryCodec 拆出 NBT、调色板与布局校验，WorldChangeBatch 降至约 750 行。
- `.temp/architecture-history-packages-verified.log` 和 `.temp/architecture-entry-registry-verified.log`：完整 check 与各自独立新目录的全量 281 项必需 GameTest 均通过。最新单元测试 2289 项，0 失败，1 跳过；git diff --check 通过。
- 尚未完成：历史队列／恢复端到端使用 HistoryEntry，HistoryService 和重放任务的进一步拆分，以及前文其他主线。实际试玩存档未清除，未提交或推送 Git，正式构建仓库配置未修改。

## 重放提示与恢复捕获的所有权

- HistoryConflictPrompt 独立持有提示令牌、冲突位置快照、分批高亮游标和提醒倒计时；关闭后拒绝旧响应，断线、换维和失去通道终止提示。冲突覆盖前镜像仍由 HistoryConflictResolution 维护。重放任务降到 800 行以内。
- 历史重放复用 TaskWriteLease，取消时交出原租约对象；统一租约补充维度检查，跨维度获取前释放前一维度的租约。未取得租约时仍取消日志准备，但不尝试按玩家释放其他任务。
- `.temp/architecture-conflict-prompt-verified.log`：完整 check 与独立新目录全量 281 项必需 GameTest 通过。新增提示分包、状态快照、旧令牌、重复清理、断线／换维／通道失效和租约边界测试。
- RollbackQueue 接管原来散落在服务和玩家状态中的捕获队列、就绪等待、内存准入、快照物化及重试。安装重放成功后才移除捕获；提示异常不改变接管结果。定向历史测试已通过，完整回归待下轮验证。
- 其余手册主线仍未完成；长按画线尚需实机手感验收。没有清除实际试玩存档，没有提交或推送 Git。

## 玩家历史能力封装

- HistoryPersistence 统一持有待写计数、发布代次、脏状态与 100 tick 重试窗口；较旧快照成功不能清除较新失败，关闭后忽略迟到回调。测试夹具通过真实 Future 构造待写与失败状态，不增加生产测试钩子。
- HistoryLoading 自管初始快照、分页、合并失败和取消。初始失败继续阻止编辑；分页失败继续保留缓存与耐久顺序，不扩大为永久阻塞。
- OwnerHistory 收回重放任务推进、结束清理、失败保留、取消、离线、待恢复任务安装、耐久提交和持久化通知。HistoryService 降至 800 行以内；没有仅靠改空行或移动代码块隐藏超限。
- `.temp/architecture-rollback-persistence-verified.log` 与 `.temp/architecture-owner-history-verified.log`：分别完整 check 与各自独立新目录全量 281 项必需 GameTest 通过。最新单元测试 2306 项、0 失败、0 错误、1 跳过；git diff --check 通过。
- 历史条目的端到端泛化、其余服务端业务状态、Intent／协议、客户端生命周期和 Mode 迁移、全操作树数据化及手册其他剩余项仍未完成。

## 玩家意图与当前设置协议（2026-11-14）

- 新增无游戏依赖的 `core.command`：冻结的类型化注册、带来源和请求 ID 的上下文、Gate 拒绝原因，以及 HANDLED／PASS／DENIED 结果。处理器异常继续交给所属运行时处理，不静默转为成功。
- `PlayerIntentSystem` 位于 `server.intent`，统一装配会话、历史和设置处理器。命令与网络共用 cycle、adjust、confirm、submode、undo、redo、back 和 quit 路由；来源不同而确有差异的旧行为显式保留。撤销仍可中断活动写入，不套用普通编辑 Gate。
- 删除输入分发器的重复转发入口及悬空命令注释；`/ff mode` 使用 COMMAND 来源。请求序列、作用域和几何手势校验仍位于原入口；确认与几何回退把已校验的实际请求 ID 继续传入上下文，不移除旧水位保护。
- `ServerChannels` 收拢 37 个 C2S 通道的线程投递和玩家类型检查；普通动作可直接声明 `toIntent`。上传、拖动、提交回执等仍使用自己的校验，不以通用注册替代业务准入。客户端设置界面和放置网关开始复用 `ServerGateway`。
- 设置使用类型化 `SettingKey`、冻结的 `SettingCatalog` 和 `SettingBindings`。同一设置的值 Codec 与处理器保持配对；未知键、同名外部键和错误值类型均拒绝。查询不推断默认值，重复设置目标值保持幂等。
- 删除 MiddleConfirmSettingPayload、FaceRasterizationSettingPayload、TinkerHistorySettingPayload、PlacementEffectSettingPayload、SettingsActionPayload 及旧注册，统一为 SettingPayload；协议升为 83，不提供旧通道兼容。落沙权限与回执、效果 ID 校验、容量和压缩参数边界继续保留。
- 删除已退役的离散面偏好存储、无效 setter、设置快照的旧字段和旧参数构造器，以及旧枚举保持 wire ID 的 next 入口。当前面生成算法未改变；生成层仍有算法枚举和历史实验路径，完整算法接口整理尚未实施。
- 删除仅被测试调用的 `HistoryService.releaseResolvedLease` 桥接，测试直接验证实际租约协调器。设置权限 GameTest 改为通过真实 Intent 入口，不再代理 IPayloadContext；上传测试仍有待迁出的反射调用。
- `.temp/architecture-history-intent-verified.log`：上一阶段完整 check 和独立新目录 284 项必需 GameTest 通过，2311 项单元测试、0 失败、1 跳过。
- `.temp/architecture-intent-channels-final-verified.log`：本轮最终完整 check 与独立新目录全量 286 项必需 GameTest 通过；2320 项单元测试、0 失败、0 错误、1 跳过。首轮新设置 GameTest 使用了已被替换的设置对象，已改为按真实 load 契约重新读取；未修改生产边界来迎合测试。
- 尚未完成：全部业务 Intent 迁移、Brigadier 声明生成、其余协议隔离和设置快照数据化，以及前文其余主线。长按画线仍需实机手感验收。没有清除实际试玩存档，没有提交或推送 Git，正式构建仓库配置未改动。

## 上传接收与结算生命周期（2026-11-14）

- `UploadSystem` 显式装配单服务器的 `WorkspaceUploads`；查询不创建实例。分块、解码、请求作用域及关闭状态不再放在 `FastPlaceNetwork` 的静态表中；网络注册只选择上传种类并转交当前载荷。两种上传共用同一流程，各自的分块通道、内存预算、超时与队列容量保留。
- `WorkspaceReceipts` 拥有作用域、准入失败、账本回放、离线结算和回执查询。先记账再投递，保留账本强结果与运行任务的优先级。首包作用域在接收成功后冻结，替换／超时清理不能删掉新作用域，重复首包不能改绑连接。
- 断线、换维及玩家实体替换取消该玩家的未提交解码；队列使用任务身份释放容量，同一 transfer ID 重试也不会被旧回调释放。取消同时丢弃待消费计划，服务器队列中留下的失效回调不继续持有大结果；tick 清理已无分块、解码或世界任务持有的作用域。
- 任务使用不可变的 `WorkspaceCompletion` 与 `WorkspaceResultSink` 结算，失败位置复制为不可变坐标。取消的生产路径与隔离测试共用注入入口，不为缺少服务器的测试放宽运行时线程检查。首轮完整单测暴露了旧测试依赖 null 服务器的隐含投递行为，已通过该接口移除耦合。
- 上传 GameTest 迁到对应包并直接使用实际入口，删除私有网络处理器的反射调用和 `ForTest` 作用域查询。新增取消前／中／后、同 ID 重试、队列隔离、错误释放、连接变更、无主作用域和离线结算覆盖；通用登录夹具位于 `testkit.ServerPlayers`。
- 分块、解码和重组实现收为包内可见；剩余的解码截止流迁到编码包，旧 `network.transfer` 源码已移空。新增上传包容量／职责说明与禁止反向依赖检查。协议仍为 83，没有添加旧格式兼容入口；手册中的“迁移说明”改为当前格式和试玩旧数据清理范围。
- `.temp/architecture-upload-finalized-verified.log`：最终完整 check 与独立新目录全量 292 项必需 GameTest 通过；2328 项单元测试、0 失败、0 错误、1 跳过。`git diff --check` 通过。
- 其余手册主线仍未完成，包括历史条目端到端泛化、其余业务状态实例化、全部 Intent／协议与客户端 Mode／预览边界迁移。长按画线仍需实机手感验收。没有清除实际试玩存档，没有提交或推送 Git，正式构建仓库配置未改动。

## 当前四边形算法与退役接口清理（2026-11-14）

- 删除 `FaceRasterizationMode`、点扫与交叉插值两套退役求解器。`QuadFaceRasterizer` 统一构建固定边缘的投影域，继续使用现有法向平面高度求解。相对边整数平移、负分数挤出取整、冲突边缘的完整高度区间、空心体保留面样本、取消及限额行为均保留；通用多边形与有界失败回退没有删除。
- 删除设置 getter、Modes、两种预览载荷、预览参数及客户端构造链中的无效算法字段；协议升为 84，不读取旧预览格式、不保留旧算法重载。架构检查禁止重新引入退役枚举和旧求解入口。
- 删除前先记录当前生产算法的 288 次面／体生成输出，包含两种边缘取舍、三种填充、限额、退化和负分数坐标；清理后坐标及结果状态的 SHA-256 基线保持一致。旧测试中仅属于退役交叉插值的两项测试删除；通用边界与体测试改为验证当前生产约束，不再错误要求裁掉厚边缘或所有列只能有一个方块。
- `.temp/architecture-raster-verified.log`：完整 `check` 与独立新目录全量 292 项必需 GameTest 通过；2327 项单元测试、0 失败、0 错误、1 跳过。`git diff --check` 通过。
- 整份手册仍未完成。历史条目端到端泛化、其余业务状态实例化、全部 Intent／协议、客户端 Mode／预览边界与数学原语整理仍待继续，长按画线仍需实机验收。本轮没有清除实际试玩存档，没有提交或推送 Git，正式构建仓库配置未改动。

## 历史条目端到端泛化（2026-11-14）

- 生产装配、玩家历史、仓库、分页、缓存与压缩调度统一使用 `HistoryEntry`。`HistoryEntryFormat` 按注册的 Codec 返回真实类型，检查 NBT 字节预算、截断和尾随字节；磁盘加载还校验条目操作 ID 与索引文件 ID 一致，不提供旧格式回退。
- `EntryReplayRegistry` 为每次重放创建独立的类型化状态；`EntryReplayState` 拥有冲突镜像、部分写入指纹和回滚，`ReplayJournal` 拥有对应日志的准备、完成与取消。通用重放循环不再引用具体方块批次、方块快照或方块日志准备器。当前方块恢复继续使用原有分段日志；新类型仍须提供自己的可恢复日志实现。
- `BlockHistoryRecorder` 收拢扳手记录、手势合并和精确逆操作抵销。方块压缩只合并连续的方块条目，其他类型构成顺序屏障，不能跨越它们合并。`HistoryService` 保持在 800 行以内；新增通用层禁止依赖具体方块类型的架构约束。
- 租约取消先交出原租约对象，再调用对应日志清理，迟到清理不能释放新租约。恢复重试保留原条目、日志及操作 ID，重新创建独立重放状态。
- 新增混合类型仓库、分页与 redo 测试，以及真实历史服务中的混合撤销／重做、非方块部分失败回滚 GameTest。覆盖未知类型、类型错配、重复／冻结后注册、文件 ID 错配与格式预算边界；共享夹具位于 `testkit.CounterHistory`，不编入发布源码。
- `.temp/architecture-entry-verified.log`：最终完整 `check` 与独立新目录全量 294 项必需 GameTest 通过。格式与显式 import 收尾后的 `check` 也通过，日志为 `.temp/architecture-entry-final-style-check.log`；最新单元测试 2333 项、0 失败、1 跳过。`git diff --check` 通过。测试使用独立目录，没有清除实际试玩存档，没有提交或推送 Git，本轮未修改正式构建仓库配置。
- 历史条目通用链路已完成本轮验证；整份手册仍未完成。其余业务状态实例化、全部 Intent／协议、客户端 Mode／预览边界、全操作树数据化与数学原语整理仍待继续，长按画线仍需实机验收。

## 统一持久化信封（2026-11-14）

- 落地手册 4.5 的 `core.persist.Envelope`／`EnvelopeFile`，24 字节头保存文件种类、格式版本、当前 DataVersion、载荷长度与 CRC32C。校验覆盖元数据和实际保存字节，统一预算、截断、尾随字节及流式验证；原子发布继续使用 `AtomicFiles`。游戏版本由装配方传入，历史磁盘层仍不依赖 Minecraft。
- 历史批次、顺序索引、退休记录和恢复的 manifest／segment／seal／correction 全部接入。`NbtEnvelopeFile` 分别限制压缩前后字节数及 NBT 解码内存；`PayloadOutputStream` 同时供历史编码复用。删除旧历史信封与其文件包装、分段自带的 CRC 包装、`31*h+crc` 摘要及首段 SHA-256 路径，不保留旧格式读取器。
- 恢复修正文件用操作 ID、分段数量与有序集合摘要绑定完整分段集合；替换后续分段同样导致恢复拒绝。seal 的摘要必须有正确 NBT 类型，不能把缺失字段当成零。
- 只有完整性验证成功后的已知版本差异才归类为不兼容。历史隔离对应文件并保留索引，恢复隔离整个操作目录；版本字节损坏、未知 magic 与其他损坏继续保留失败保护，不被误判成可跳过的旧数据。
- `.temp/architecture-envelope-final-verified.log`：最终完整 `check` 与独立新目录全量 294 项必需 GameTest 通过，2346 项单元测试、0 失败、1 跳过。覆盖所有信封字节损坏、预算、解压后超限、原子写入失败保留、版本分类、修正身份与完整集合绑定；实际试玩存档未清除，未提交或推送 Git。
- 已更新当前磁盘格式和源码结构文档，并增加禁止重新引入旧历史信封及绕过统一恢复信封的架构约束。整份手册的其他剩余主线继续保留，不将本节验证视为全部完成。

## 协议方向与共享数据边界（2026-11-14）

- 37 个 C2S、20 个 S2C 消息及预览 Codec 迁入 `protocol/c2s/<feature>` 与 `protocol/s2c/<feature>`。工作区上传和回执另按 workspace 分包；共享回调作用域归 `protocol.callback`。原 `network.payload` 移空，不保留旧路径别名；消息 ID、字段和协议版本 84 不变。
- 删除未注册的合并 `BuildingPreviewPayload` 及其无生产调用的 wire Codec。共享 `BuildingPreviewSnapshot`、会话、参数和效果值归 `common.preview.quickshape`；真实分包的 Codec 往返测试继续覆盖原数据，接收方验证 revision 后复用统一组合入口。
- 设置装配移到服务端 `QuickShapePreviews`；`PlayerPreviewSync` 改为 `server.sync.PreviewSync`。压缩参数、层级和设置坐标移至 `common.history`，上限归参数模型所有；删除客户端与设置载荷对服务端历史类型的引用。冻结命中规则、形状和射线数值校验移至 `common.raycast`。
- 新增协议与共享模型的运行时依赖约束、收发方向隔离、包容量及职责检查。旧 `network.payload`／`network.sync` 引用被禁止；协议仍引用尚未迁移的共享领域类型，不能据此宣称完整四层依赖迁移已完成。
- `.temp/architecture-protocol-verified.log`：完整 `check` 和独立新目录全量 294 项必需 GameTest 通过，2346 项单元测试、0 失败、1 跳过。首次编译暴露迁包后失效的隐式同包引用，已补充明确 import 并完整重跑；没有改动交互规则。
- 用户新增阶段提交／上传要求。本节点将此前尚未提交且已验证的重构一并保存；后续验证通过的阶段分别建立提交。实际试玩存档未清除，正式构建仓库配置未修改。长按画线的实机手感验收及前述其余主线仍未完成。

## 预览同步的玩家作用域（2026-11-14）

- 删除 `PreviewSync` 的 7 张静态 UUID 表，由 `PreviewSyncState` 保存单玩家的活动、四类 revision、发布参数和回调身份。状态使用已有 `PlayerStateRegistry`，受服务器线程、服务器身份和玩家实例共同约束；查询和清理使用 `find`，断线后不能重新创建状态。
- 同 UUID 玩家替换后，旧实例的迟到清理不能删除新实例的发布。换维先通知瞬时状态失效，再恢复目标维度工作流并发布；保留原有维度停放与世界任务的生命周期。删除两处服务器关闭时的预览全局清理及对应入口，统一由玩家作用域丢弃。
- 新增 4 项 GameTest，覆盖不创建查询、迟到清理、换维／断线、同 UUID 替换，以及真实换维事件中的发布顺序。首次运行暴露测试夹具错误：mock 玩家自动登录，且未连接的替身会改绑在线玩家的进度对象。已使用不加入世界的玩家验证无附着状态和替换，未放宽生产生命周期约束。
- `.temp/architecture-preview-scope-final-verified.log`：完整 `check` 与独立新目录全量 298 项必需 GameTest 通过，2346 项单元测试、0 失败、1 跳过。新增预览包容量与静态状态检查。正式构建仓库配置和实际试玩存档未改动；本阶段单独提交并推送。
- 整份手册仍未完成。其余服务端业务实例化、完整任务运行单元、全部 Intent／客户端 Mode／PreviewFrame／PointerResolver、全操作树数据化、领域迁包和数学原语等继续保留；长按画线仍需实机手感验收。

## 会话系统与任务队列的服务器实例化（2026-11-14）

- `SessionSystem` 按服务器身份显式启动和停止，实例装配 `QuickShapeSystem`、`OperationSystem`、`GeometrySystem`。三类旧静态 Manager 已删除，不保留别名或转发；玩家工作流与两类写入任务队列均由所属实例持有，同 UUID 在不同服务器中互不影响。
- `Scope` 统一关闭三个单元。任务队列先结束准入再清理，一个任务取消失败仍继续清理其余任务，资源释放位于 finally；关闭幂等，旧 resume 回调不能推进已关闭的实例。停服后才触发的 logout 和回执查询改用非创建查询，不再访问已移除的运行实例。
- 物理 Alt 状态迁入 `InteractionModifierState`，断线、换维及玩家实例替换归零。保留 UUID 工作流、维度停放和世界任务的既有语义；恢复草稿前同步当前修饰键。旧玩家实例的迟到清理不能改变同 UUID 新实例的输入与草稿。
- 删除本阶段的任务、会话和反馈 `ForTest` 入口。测试通过所属实例的包内仓库和队列验证状态，反馈故障注入只修改该运行实例并在 finally 恢复。新增服务器隔离、关闭异常汇总、停止准入及三项修饰键生命周期测试。
- `.temp/architecture-session-instance-final-verified.log`：完整 check 和 298 项必需 GameTest 通过。新增生命周期边界测试后，`.temp/architecture-session-modifier-final-verified.log` 的完整 check 和独立新目录全量 301 项必需 GameTest 通过；2348 项单元测试、0 失败、1 跳过。服务器正常停服，未再出现关闭后会话查询异常。首次新增换维测试使用了无网络连接的玩家，已修正夹具，未放宽生产约束。
- 本阶段单独提交并推送；实际试玩存档未清除，正式构建仓库配置未修改。三类系统仍需继续拆分业务职责，完整任务运行单元、剩余服务端状态及前述手册主线未完成。

## 取消追加日志的清理边界（2026-11-14）

- 复查任务共同生命周期时，发现 `releaseAfterCancellation` 只等待日志创建，没有等待已启动的追加；可能在追加仍使用目录时删除日志并释放租约。现先冻结本次日志、创建 Future 与追加 Future，等追加结束后再清理，成功和失败都执行清理。
- 清理闭包不读取重置后的准备器状态，仍使用原租约身份。新增受控执行器测试，验证追加期间目录与租约保留，以及旧追加失败后不删除新日志、不释放后继租约。
- `.temp/architecture-cancel-append-verified.log`：完整 check 与独立新目录全量 301 项必需 GameTest 通过；2350 项单元测试、0 失败、1 跳过，正常停服。本修复独立提交并推送；完整任务运行单元与其余手册主线继续保留。

## 任务调度与离线反馈分离（2026-11-14）

- 删除 `WorldTaskFeature`。`server.task.WorldTaskScheduler` 只推进恢复、起形写入、选区写入和提交账本；原有异常关闸、独立恢复移交与日志／租约保留顺序不变。
- 两张静态 UUID 消息表迁入 `server.feedback.TaskFeedback`，由所属 `SessionSystem` 统一持有和关闭。断线继续保留最新进度及最多四条结果，重连按原顺序发送一次；不同服务器不共享消息。关闭清空队列，迟到投递不重新创建运行实例；没有服务器的测试上下文不再写入全局备用表。
- 删除两个反馈 `ForTest` 读取入口；单元测试通过真实发送路径检查消息内容和顺序。调度故障夹具迁到所属包，保留真实写入闸门 GameTest。新增反馈包与任务包的容量／依赖约束，禁止重新引入旧门面及反馈静态玩家表。
- `.temp/architecture-task-feedback-final-verified.log`：最终完整 check 与独立新目录全量 301 项必需 GameTest 通过；2352 项单元测试、0 失败、1 跳过，正常停服。单独提交并推送；正式构建仓库配置、实际试玩存档未改动。任务资源完整封装及其余手册主线仍未完成。

## 三类写任务的共同资源生命周期（2026-11-14）

- 普通放置、选区操作、工作区提交继承 `LeasedJournaledTask`，统一拥有事务、日志准备与分段、提交、租约、内存预算和批次指标。取消、恢复移交和成功提交清理不再复制三套实现；各自算法和阶段推进保留在子类。
- 子类通过活动停止、附属资源释放、提交后 staging 清理三个钩子保留差异。附属清理抛错仍释放共同资源；预算等待日志追加和历史发布结束。工作区 tick barrier 先解除引用再关闭，重复清理不再次关闭原资源。
- 日志准备器构造后不可替换且拒绝 null。测试经包内构造注入受控执行器，删除反射修改准备器及追加 Future 的夹具。原先反射破坏 final 字段的 GameTest 已移除；提取前失败与重试改在真实任务扩展点及共同队列验证，提取后拒绝／失败保留快照的原测试继续保留。其他失败标志的反射尚未清完。
- 新增共同生命周期与队列失败边界测试；日志准入、历史发布失败、内存竞争、停服资源释放及维度停放夹具改用类型化构造、真实 Future 和共享测试资源入口。历史发布失败通过拒绝执行器制造，不再反射覆盖已完成的 final Future。新增任务资源依赖和包容量守护，禁止三类任务重新持有独立租约／日志准备器／日志游标。
- `.temp/architecture-task-runtime-final3-verified.log`：最终完整 check 与独立新目录全量 300 项必需 GameTest 通过，2359 项单元测试、0 失败、1 跳过，正常停服。GameTest 减少的一项是反射破坏 final 准备器的结构性夹具，其失败前后所有权断言已转入共同生命周期与队列测试。首次回归发现其他夹具反射访问已迁移字段，均已修正；未放宽生产约束。
- 任务统一 tick 抽象、服务端迁包及前述其余主线仍未完成；实际试玩存档未清除。

## 服务器启动时的写入租约隔离（2026-11-14）

- 发现启动事件调用 `WorldWriteCoordinator.clearAll`，会同时清除其他服务器仍在使用的租约。现只清理当前服务器；全局清理入口已删除，测试清理也改为所属服务器标识。
- 回归用例验证一个服务器的清理及迟到日志回调不影响另一个服务器的活动租约。原有事务身份、后继租约保护和异步清理重试规则不变。
- `.temp/architecture-server-lock-final-verified.log`：完整 check 与独立新目录全量 300 项必需 GameTest 通过，2359 项单元测试、0 失败、1 跳过，正常停服。首轮 Gradle 常驻进程因 GC thrashing 退出；改用一次性进程及命令行 2 GiB 堆重跑，未修改项目构建配置。
- 本阶段单独提交并推送；实际试玩存档未清除。其余服务端共享状态、任务迁包及前述手册主线仍未完成。

## 提交账本的实例所有权（2026-11-14）

- `WorkspaceSubmissionLedger` 删除静态服务器映射、写入时自动创建及全局／单服务器清理入口。账本由 `SessionSystem` 作用域持有，上传模块显式注入同一实例；工作流与 tick 通过所属运行实例访问，不保留旧静态转发。
- 关闭顺序为工作流任务清理、账本丢弃。关闭清空所有记录，旧引用的 begin／finish／tick 不重新建立账本，finish 返回 UNKNOWN；一个运行实例关闭不改变另一个实例的相同玩家和 transfer ID。原有容量、TTL、离线回执和强结果优先规则不变。
- 新增账本关闭、跨实例隔离、所有者清理及 Scope 生命周期回归；原有回执、重放、取消结算和上传 GameTest 改用实际所属实例。架构检查禁止账本重新引入静态状态或依赖运行系统。
- `.temp/architecture-submission-ledger-verified.log`：完整 check 和独立新目录全量 300 项必需 GameTest 通过；2364 项单元测试、0 失败、1 跳过，正常停服。首次检查中新增约束错误涵盖了依赖 ServerPlayer 的结果接口，已限定为账本状态实现，未改变业务准入。
- 本阶段单独提交并推送；实际试玩存档未清除。恢复日志的全局写入闸门／清理追踪、统一任务 tick、任务迁包及其余手册主线仍未完成。

## 恢复系统的实例所有权（2026-11-14）

- `RecoverySystem` 按服务器显式创建和关闭，持有恢复执行器、写入闸门、日志序号及已提交日志的保留状态。删除 `PersistentRecoveryJournal` 的全局 blocked 标志、提交表、待清理集合和静态运行入口；世界存盘使用非创建查询，启动前或关闭后的事件不重建系统。
- `RecoveryWriteGate` 不依赖游戏或存储。恢复期间关闭写入，仅当前版本的成功恢复可开放；后续故障、较早恢复回调和永久关闭不能被覆盖。调度失败只关闭所属服务器的闸门。
- `WorldTaskContext` 保存原闸门身份，withResume 保持同一身份，日志准备不能改用替换后的恢复系统。两类调度、选区写入、工作区写入和历史重放复用上下文查询；无服务器上下文使用独立或显式注入的闸门，没有全局备用状态。
- 日志构造时绑定所属执行器和保留状态。清理任务以登记对象身份核对路径，耐久 IO 屏障后才删除；重登记、启动恢复与关闭会使旧清理失效，旧回调不能释放新登记的 pending 标记。清理排队／耐久检查失败保留文件并允许后续存盘重试。等待历史发布的封存不再短暂登记为可清理，发布成功后才登记。
- 新增实例隔离、永久关闭、恢复版本、队列拒绝、耐久失败、同路径重登记、旧实例关闭、上下文派生和历史发布边界测试；删除生产全局测试复位开关。测试经包内构造注入依赖，既有恢复 GameTest 继续使用当前分段格式，未放宽格式或业务准入。
- `.temp/architecture-recovery-scope-final3-verified.log`：最终完整 check 与独立新目录全量 300 项必需 GameTest 通过；2376 项单元测试、0 失败、1 跳过，正常停服。此前修正两处无服务器调度夹具的直接运行实例查询；新增封存测试改用元数据夹具，并显式检测游戏版本，消除其对其他测试执行顺序的依赖。
- 本阶段单独提交并推送；正式构建仓库配置与实际试玩存档未改动。统一任务 tick、任务迁包、剩余业务状态拆分、声明式 Intent／协议、ClientScope／Mode／PreviewFrame／PointerResolver 和完整操作树等主线仍未完成；长按画线的实机手感验收仍需进行。

## 世界写任务统一推进与取消（2026-11-14）

- 三类写任务统一实现 `server.task.WorldTask.tick(context)`，共用 `TaskStep` 和 `CancelReason`。删除 `WorldWriteTask`、`WorldOperationTask`、`OperationTaskResult` 及三参数公开 tick，不保留旧入口转发。起形的快照校验、分段日志、世界写入、最终快照及封存顺序迁回任务模块，会话系统只处理队列、历史交接与提示。
- `LeasedJournaledTask` 统一执行前置准备、维度检查、内存准入、租约获取及批次计量。起形保留异步生成等待和小操作预算；批次异常仍记录实际耗时。终态由任务持有，等待历史发布时不再次运行算法。取消先阻止后续推进，即使停止活动失败，迟到回调也不能写入；恢复取消保留工作集，停服取消执行统一资源释放。
- `WorldTaskQueue` 按对象身份推进任务，拒绝后继任务的旧回调及恢复移交中的继续写入。关闭结束准入后逐个取消，失败汇总；删除直接 clear、无身份 remove 和调用方自组装关闭回调。固定预算测试通过共享测试源码调用实际批次边界，不为生产提供旧调用兼容。
- 新增终态、生成等待、维度未加载、关闸后重开、异常计量、取消失败、内存保留与后继回调测试。`.temp/architecture-unified-task-verified.log` 的完整 check 与独立新目录全量 300 项必需 GameTest 通过；2385 项单元测试、0 失败、0 错误、1 跳过，正常停服。首轮编译修正了三处测试调用点及一个局部变量遮蔽包名的问题，未放宽生产约束。
- 本阶段单独提交并推送。实际试玩存档及正式构建仓库配置未改动。具体任务迁包、剩余业务状态拆分、声明式 Intent／协议和客户端主线仍未完成；长按画线仍需实机手感验收。

## 世界写任务迁入服务端职责包（2026-11-14）

- 任务契约、上下文和预算归入 `server.task`；三类写入算法及计划归入 `server.task.block`；共同租约、日志分段和资源释放归入 `server.task.runtime`。`ClientWorkspacePlacementTask` 更名为 `SubmittedWorkspaceTask`。删除旧包入口，不保留别名或静态兼容转发。
- 单元测试、GameTest 和共享夹具跟随实际职责迁移。固定预算工厂移入 `src/testSupport` 的 `TaskBudgets`，删除生产 `testing*` 方法和旧 GameTest 访问器；构造与辅助预算方法保持包内可见，预算公式与限额不变。
- 新增算法／运行层禁止反向依赖会话编排的检查，并禁止旧任务包和生产预算测试钩子。同步源码布局与产物检查的两个类路径；正式构建文件的仓库配置未改动。
- `.temp/architecture-task-packages-final-verified.log`：最终完整 check 与独立新目录全量 300 项必需 GameTest 通过；2385 项单元测试、0 失败、0 错误、1 跳过，正常停服。首次完整检查发现产物校验仍指向旧路径，修正后重跑通过，未放宽校验。
- 本阶段单独提交并推送。实际试玩存档未清除。剩余服务端业务拆分、Intent／协议、客户端实例化与 Mode 系统等主线继续保留；长按画线仍需实机验收。

## 跨会话协调移出快速起形（2026-11-14）

- 跨会话分类、物理修饰键同步、活动编辑取消、退出、玩家脱离、换维停放／恢复与预览发布统一归入所属 `SessionSystem`。快速起形的内部结束方法仅处理自己的草稿，不再直接调用选区和几何系统；所有旧调用点迁移，不保留转发接口。
- 保留原优先级和顺序：选区 → 几何 → 起形 → 手持物品分类；取消活动编辑保留停放草稿和世界任务；退出时按历史恢复状态决定是否取消任务；换维先停放全部源编辑器，再恢复目标编辑器。修饰键仍由玩家实体作用域持有，迟到实体清理不能影响替换实体。
- 删除起形／选区对历史恢复状态的重复转发，调用方直接查询历史服务。截获丢弃物品的归还逻辑移入 `server.player.TossedItems`，保持物品合并、空槽、背包与掉落回退规则。
- 新增三个会话边界 GameTest，覆盖取消保留任务／停放草稿、三类编辑器往返换维及活动会话优先级；原修饰键、空闲入口、长按画线、预览与生命周期测试改用真实所属入口。新增架构约束，防止快速起形重新承担跨会话协调。
- `.temp/architecture-session-boundary-verified.log`：完整 check 与独立新目录全量 303 项必需 GameTest 通过；2385 项单元测试、0 失败、0 错误、1 跳过，正常停服。本阶段单独提交并推送；任务业务进一步拆分、Intent／协议、客户端主线与实机验收仍未完成。

## 世界写入租约实例化（2026-11-14）

- 删除静态 `WorldWriteCoordinator` 及其服务器键表。`RecoverySystem` 显式拥有 `server.worldwrite.WorldWriteLeases`；启动使用新实例，停服在任务和日志 IO 边界之后关闭。关闭后的实例不能重新授予租约，迟到回调不会重建状态或影响其他运行实例。
- 令牌直接提供当前身份查询、释放及清理后释放。保留同玩家事务排他、跨维度排他、无空窗接管、后继令牌保护与未使用日志删除失败后的锁定／重试。租约模块只依赖清理接口，不依赖具体恢复日志、会话或服务器对象。
- `WorldTaskContext` 显式保存闸门和租约服务身份，`withResume` 保留两者；事务与回滚使用已捕获的服务。`TaskWriteLease` 同时校验服务身份、玩家和维度，不会在替换运行实例中沿用旧令牌。
- 测试迁入实际职责包，删除生产强制重试钩子，使用注入时钟验证重试期限及 nanoTime 回绕。补充关闭拒绝、替代服务隔离、上下文身份、跨运行实例获取、清理抛异常后的重试；没有令牌的取消仍清理准备器，但不释放其他事务。
- `.temp/architecture-write-leases-verified.log`：完整 check 与独立新目录全量 303 项必需 GameTest 通过；2388 项单元测试、0 失败、0 错误、1 跳过，正常停服。新增包容量、旧协调器引用与租约模块依赖约束；产物检查更新为新类路径，未改仓库配置。
- 本阶段单独提交并推送。试玩存档仍未清除。整份手册的任务业务拆分、Intent／协议、客户端主线与长按画线实机验收仍未完成。

## 放置任务执行与起形编辑分离（2026-11-14）

- `server.session.execution.PlacementTasks` 完整拥有放置任务队列、执行／resume、取消、关闸恢复移交、历史发布等待、结果提示与关闭。由 `SessionSystem` 的作用域装配和清理，不回调编辑工作流或网络层。
- 起形保留选点、模式、生成和提交请求；删除其任务队列及任务执行／查询方法，不保留转发。调度器、门禁、撤销、退出、预览和测试直接使用所属 `placementTasks()`。起形文件从 981 行降至 753 行。
- 取消仍先接受恢复数据再移除槽位；旧 resume 校验父作用域、任务对象、移交状态、写入门禁和历史忙碌状态。关闸移交复用同一取消入口；完整保留原进度、失败与成功提示。
- `.temp/architecture-placement-execution-verified.log`：完整 check 与独立新目录全量 303 项必需 GameTest 通过，正常停服。迁移了关闭清理、延迟唤醒、故障注入与取消边界测试；新增禁止起形重新持有执行状态、执行单元反向依赖编辑／网络的架构约束。首次检查要求补齐新包的职责说明，补齐后通过，未放宽规则。
- 本阶段单独提交并推送。选区／工作区执行仍在 `OperationSystem`，后续继续拆分；其他未完成主线不变。

## 选区／工作区任务执行与编辑分离（2026-11-14）

- `OperationTasks` 完整拥有选区和工作区的任务队列、tick／resume、取消、关闸移交、历史发布及回执结算。父作用域注入放置优先约束和结果发布接口；执行模块不依赖编辑工作流或上传实现。
- `OperationSystem` 保留选区编辑和提交准入，删除任务队列、执行及查询转发。调度、门禁、撤销／退出和上传去重／回执查询直接使用 `operationTasks()`；删除无剩余用途的编辑器 `find` 查询。保留先入队 → 账本准入 → 安排 resume → 提示的既有顺序。
- 终态处理拆为推进、完成结算、失败恢复、进度及结果提示。历史尚未发布时保留任务；恢复未接受时保留槽位；已接受取消只结算一次。测试直接构造执行能力并注入结果收集器，不再从编辑器临时传入测试回执端点。
- `.temp/architecture-operation-execution-verified.log`：完整 check 与独立新目录全量 303 项必需 GameTest 通过；2388 项单元测试、0 失败、0 错误、1 跳过，正常停服。工作区取消、重复查询、准入反馈异常、发布故障与资源关闭测试均使用实际执行能力。架构检查同时禁止两类编辑器重新持有执行状态。
- 本阶段单独提交并推送。两类执行中的共同队列仲裁仍可继续收敛；Intent／协议、客户端实例化、Mode、完整操作树及实机验收仍未完成。

## 共同任务执行生命周期（2026-11-14）

- 包内 `TaskExecution` 统一两类执行的队列准入、tick／resume、前置优先约束、关闸移交、取消和关闭。叶类保留各自的完成结算、进度和工作区回执，没有新增旧入口转发。
- 父运行实例关闭后，tick 与关闸移交在创建上下文前返回；已排队的 resume 同时检查原任务身份、恢复闸门、移交状态、历史和前置任务。取消仍先取得恢复接受结果，再移除槽位并发布业务回执。
- 新增前置优先级、父实例关闭、后继任务隔离、移交阻止继续执行及原恢复闸门重开测试。`.temp/architecture-task-execution-common-verified.log`：完整 check 与独立新目录全量 303 项必需 GameTest 通过，正常停服；未改变业务结算顺序或正式构建仓库配置。
- 继续保留整份手册的 Intent／协议、客户端实例化、Mode、完整操作树与长按画线实机验收等未完成项。实际试玩存档未清除。

## 几何参数与旋转意图（2026-11-14）

- 几何模式选择、锥体平面／椭圆、多面体尺寸及四种旋转入口统一为类型明确的 `GeometryIntents`。命令只构造意图，模式选择消息使用 `toIntent`；删除输入分发器的旧模式选择入口，不保留兼容转发。
- 保留模式选择与参数修改不同的准入条件；状态校验、用户提示、设置持久化和预览同步继续由几何工作流负责。矩阵意图复制输入／输出数组，调用者不能改写待处理的旋转值。新增架构约束，防止命令重新直接调用几何系统。
- 新增模式切换保护、活动选区冲突、生存门禁、锥体／多面体参数、无效矩阵与轴、命令／网络旋转一致性及矩阵快照测试。首轮模拟玩家不反映生存权限，改用真实连接的测试玩家后重跑；未放宽生产门禁。
- `.temp/architecture-geometry-intents-final-verified.log`：完整 check 与独立新目录全量 306 项必需 GameTest 通过，正常停服。后续仍需迁移其他业务意图与声明式命令，以及客户端、Mode 和完整操作树等主线；长按画线实机验收未完成。

## 数学轴与 Gizmo 解耦（2026-11-14）

- 新增无游戏依赖的 `core.math.Axis`，删除 `AxisGizmo.Axis`，统一迁移 79 个调用文件及 Gizmo 定义。工作区数学、局部坐标、旋转、镜像、堆叠、手势、协议校验和渲染不再通过 Gizmo 的嵌套类型表达数学轴；不保留旧类型别名。
- X/Y/Z 顺序、网络与磁盘序号、旋转次序、视角轴选择及步长计算保持不变。清除迁移后不再使用的 Gizmo 导入，新增禁止旧轴类型的架构约束。
- `.temp/architecture-core-axis-verified.log`：完整 check 与独立新目录全量 306 项必需 GameTest 通过，正常停服，包含旋转、镜像、堆叠、局部坐标和变换协议测试。`git diff --check` 通过。
- 数学向量／容差／体素原语的统一仍未完成；Intent／声明式命令、客户端实例化、Mode、完整操作树与实机验收继续保留为未完成项。实际试玩存档未清除。

## 向量归一化与容差单位（2026-11-14）

- `core.math.Vectors` 提供无游戏依赖、无中间向量的结果工厂入口；`common.math.MinecraftVectors` 负责 Vec3 适配。移除九处重复归一化方法，输入拖动、几何、射线和指南渲染直接调用共同实现；坐标系的回退轴规则保留。
- `Epsilon.LENGTH` 与 `DIRECTION_LENGTH_SQUARED` 明确区分长度／长度平方，保留原阈值、null 契约、IEEE 非有限值和运算顺序。没有将不同单位的阈值机械统一，也没有增加默认替零。
- 新增跨量级随机对照、阈值边界、零／非有限坐标、单次结果构造、非法阈值和 Vec3 原计算精确对照。定向测试中修正了把浮点单位向量写成精确 1.0 的断言；保留原算法逐位比较。
- `.temp/architecture-core-vectors-verified.log`：完整 check 与独立新目录全量 306 项必需 GameTest 通过，正常停服。体素／轴分量等其余数学提取、Intent／声明式命令、客户端实例化、Mode、完整操作树及实机验收仍未完成。

## 起形编辑与选区动作意图（2026-11-14）

- `server.intent.quickshape` 封装上下文数值、面角度、线／体距离和阶段模式；`server.intent.selection` 封装应用／复制、选区模式与冲突模式。命令构造类型明确的意图，处理器执行既有准入、参数边界及失败反馈，无旧入口兼容转发。
- 网络应用仍先校验请求顺序，再携带原请求 ID 派发；网络只提交已确认选区，命令仍可直接提交。空会话循环模式仍更新偏好并保持原命令返回值，不改变既有行为。
- 新增命令／直接意图阶段一致性、无草稿和生存门禁、数值边界、未确认网络提交拒绝及命令复制入队测试。`.temp/architecture-edit-intents-verified.log`：完整 check 与独立新目录全量 309 项必需 GameTest 通过，正常停服。
- Intent／声明式命令、客户端实例化、Mode、完整操作树及长按画线实机验收等主线继续保留为未完成项。实际试玩存档未清除。

## 命令能力拆分与参数声明（2026-11-14）

- 删除 844 行 `FastPlaceCommandRegistry`，由 `server.command.CommandSystem` 只装配命令根。起形、几何、旋转、选区、历史和设置分别维护自己的命令声明；Reach 及其测试迁至服务端命令包，不保留旧包兼容入口。
- `CommandParameter` 绑定参数名称、Brigadier 类型和读取类型；`IntentCommands` 生成参数链并拒绝重复字段；`CommandChoices` 冻结模式词汇，替换编辑入口的字符串 switch。采用显式类型声明，不引入字段反射。保留命令名称、两个根别名、数值范围、现有面模式词汇及反馈／准入顺序。
- 新增两个根的完整语法树一致性、公开子命令集合、模式词汇、无效参数拒绝和参数声明测试。单元测试不具备游戏语言资源，无效参数异常断言移至 GameTest，仍验证实际 Brigadier 拒绝行为。
- `.temp/architecture-command-schema-verified.log`：完整 check 与独立新目录全量 310 项必需 GameTest 通过；2402 项单元测试，0 失败、0 错误、1 跳过，正常停服。`git diff --check` 通过；新增命令包容量和旧注册类引用守护。
- 设置／管理命令尚未全部改成意图，客户端实例化、Mode、完整操作树和实机验收等主线仍未完成。实际试玩存档未清除。

## 设置命令意图与不可用清理归属（2026-11-14）

- 设置命令不再直接修改玩家配置、查询运行时或组装界面包；复用 `SettingRequest` 的已有绑定，并新增本地 `PlayerSettingsIntents` 负责填充模式、数量上限、强制放置、启停、状态和界面请求。删除起形工作流的设置数量入口，无兼容转发。
- 设置绑定显式接收意图上下文：命令保留原查询反馈和同步条件，网络仍保留原写入／同步行为；未支持的网络查询不转为命令查询。历史限额命令仍要求编辑可用，其余原本允许的设置不新增创造／启用门禁。强制放置的命令源反馈留在适配器。
- `CommandAdmission` 统一编辑命令的准入与拒绝提示；不可用时的编辑／任务／历史取消移到所属 `SessionSystem.stopUnavailable`。删除输入分发器的旧清理入口，事件和设置处理器直接使用会话能力，命令不再依赖输入门面。
- 补充禁用／生存门禁、查询不写入、禁用清理活动编辑器、网络查询差异、数值／模式设置及缺失客户端通道测试。`.temp/architecture-settings-command-verified.log`：完整 check、2402 项单元测试（0 失败、0 错误、1 跳过）及独立新目录全量 311 项必需 GameTest 通过。
- Intent／命令剩余源上下文管理动作、客户端实例化、Mode、完整操作树与实机验收仍未完成。实际试玩存档未清除。

## 选区点编辑意图（2026-11-14）

- 点选、移除、插点、闭合底面、中键扩展、拖点和延伸统一为 `SelectionIntents`，处理器执行活动选区和可编辑门禁。拖动坐标复制为不可变值，延伸保留每包 128 步限制，拒绝范围外轴。
- 网络边界继续持有预览 revision、回调作用域、请求顺序和手势消费校验；finally 仍负责拖动完成 revision，不因派发改变旧请求／迟到事件的判定。保留长方体扩展和棱柱增加顶点的不同动作。
- 新增可变坐标冻结、必填输入、棱柱点选／拖点／移除及生存门禁测试。首轮测试忽略切换棱柱会清空棱柱点集，补齐首点夹具后通过，未修改生产切换行为。
- `.temp/architecture-selection-edit-final-verified.log`：完整 check 与独立新目录全量 312 项必需 GameTest 通过，正常停服。其余输入意图、客户端实例化、Mode、完整操作树和实机验收仍未完成。实际试玩存档未清除。

## 共享主轴与分量数学（2026-11-14）

- `Vectors` 提供无游戏依赖的主轴选择、分量和轴向量构造；`MinecraftVectors` 适配 Vec3，并区分有符号步进与正向基轴。删除四处重复主轴方法和工作区缩放的分量方法，调用方直接复用。
- 保留绝对分量 X／Y／Z 平局优先级、非有限分量的比较结果和 signed zero。起形滚轮方向、体积回退轴、棱柱投影及铅笔线哈希序号不变，没有将原版 Direction 最近方向的判定替换成不同平局规则。
- 新增随机输入对照、平局／非有限值、轴分量及符号零测试。`.temp/architecture-vector-axes-verified.log`：完整 check 与独立新目录全量 312 项必需 GameTest 通过，正常停服。其他数学原语、Intent、客户端和 Mode 等主线仍未完成。

## 格点四边形判定（2026-11-14）

- `common.math.BlockQuads` 统一四边形对角坐标和判定，QuadFaceRasterizer、PlanarFaceRasterizer 和 TranslatedScanFaceRasterizer 直接复用；删除 BresenhamFaceSweep 中无调用者的重复方法。
- 保留整数 long 求和避免溢出、浮点顶点先按 BlockPos.containing 落格、按周边排列顶点以及退化仍可能满足恒等式的原有语义。落格入口不再创建流和中间列表，其他几何／预算校验归生成器。
- 新增整数极值、退化、顺序、负坐标落格和随机公式对照测试。`.temp/architecture-block-quads-verified.log`：完整 check 与独立新目录全量 312 项必需 GameTest 通过，正常停服。体素等其余数学原语及客户端／Mode 等主线仍未完成。

## 世界离开事件迁出渲染（2026-11-14）

- `client.session.lifecycle.WorldSessionEvents` 统一接收客户端登出和世界卸载；删除渲染包的 `PreviewSessionLifecycle` 以及 Core 中的生命周期装配和事件订阅。渲染保留自身连接快照／资源重置，不再反向发起输入和选区断线清理。
- `WorldSessionLifecycle` 复用 `Scope.detach`，保持输入 → 源遮罩 → 预览 → 选区断线 → 交互缓存 → 反馈 → 渲染资源的顺序。一项清理失败仍继续其余参与者，最后汇总；重复离开事件不丢失资源。聚焦状态仅清反馈的逻辑仍归预览 tick。
- 测试迁至实际生命周期包，验证顺序、多处故障后的完整清理和重复边界。架构检查禁止渲染重新订阅世界离开事件或调用选区断线清理；首次检查捕获 CRLF 导致的未命中残留，修正后通过。
- `.temp/architecture-world-session-events-final-verified.log`：完整 check 与独立新目录全量 312 项必需 GameTest 通过，正常停服。完整 ClientScope、编辑器实例化、Mode、PreviewFrame、PointerResolver 和长按实机验收仍未完成。实际试玩存档未清除。

## 客户端作用域身份（2026-11-14）

- 用 `client.session.scope.ClientScope` 和不可变的 `ClientScopeId` 替换仅保存 UUID 的 ClientPlayerSession 及管理器内部 SessionKey。作用域持有服务器、维度、玩家身份，共享所属输入／选区手势、工作区和暂存草稿；删除旧类型和旧工作区访问名称，不保留别名或转发。
- 当前身份由当前作用域派生，删除独立的 currentKey 字段。草稿读取只接收所属作用域，不能把另一身份和状态对象组合传入；保留原文件摘要、跨维度清理、失败重试和安全驱逐条件。
- 测试夹具使用完整身份；补充身份不变、必填玩家、同玩家跨环境隔离和非当前环境读取结果隔离测试。`.temp/architecture-client-scope-verified.log`：完整 check 与独立新目录全量 312 项必需 GameTest 通过。
- Draft／Receipt 仓库拆分、OperationContext 实例化、Mode、PreviewFrame、PointerResolver 和实机验收仍未完成。实际试玩存档未清除。

## 回执仓库归属作用域（2026-11-14）

- `ClientScope` 持有 `ClientReceiptRepository`，后者拥有回执缓存、读取状态、文件位置、只读保护、明确重试和安全驱逐条件。删除管理器的 receiptStores、receiptLoadStates、receiptFiles 三张映射及仅供测试的回执路径安装入口；测试直接配置所属仓库的文件位置。
- 回执改用自己的 `ReceiptReadState`，不再借用含 AWAITING_RECEIPT 的草稿枚举。读取失败只随实际尝试返回一次；环境切换只自动重试暂时失败，版本不匹配等待明确重试，未保存回执仍阻止驱逐。管理器保留错误提示和草稿等待的协调。
- `ClientScopeFiles` 统一草稿／回执路径摘要，保留原服务器、维度、玩家三字段及换行分隔。未引入旧格式读取或迁移，实际试玩存档未清除。
- 新增损坏文件缓存／修复重试、旧格式禁止覆盖、失败保存保留脏状态测试。`.temp/architecture-client-receipt-verified.log`：完整 check 与独立新目录全量 312 项必需 GameTest 通过。Draft 仓库、OperationContext、Mode 与实机验收仍未完成。

## 草稿仓库归属作用域（2026-11-14）

- `ClientScope` 持有 `ClientDraftRepository`，后者负责文件位置、严格解码、读取结果、回执等待、已保存内容身份、提交归属探测、余量结算和删除。删除管理器的 draftLoadStates、draftFiles、durableDrafts 三张映射；草稿读取不再分别传入身份或路径，也不保留测试专用的回执覆盖重载。
- 仓库返回内容、失败和通知，由管理器协调暂存、恢复及玩家提示。读取不会修改实时工作区；未知回执阻止暂存，已应用回执阻止旧草稿返回。明确丢弃后的 READY 不会因回执恢复而重读。双文件持久化和队列准入仍使用原编排。
- 驱逐按不可变草稿对象身份确认；旧保存回调不能把相等但不同对象的新草稿标为已保存。结算余量后清掉旧耐久身份，避免旧内容错误获得驱逐资格。补充迟到确认、实时工作跳过读取、未知回执等待／恢复和旧格式拒绝测试。
- `.temp/architecture-client-draft-verified.log`：完整 check 与独立新目录全量 312 项必需 GameTest 通过；随后删除测试专用回执覆盖入口，测试改读真实回执文件，`.temp/architecture-client-draft-final-check.log` 的完整 check 通过。OperationContext、Mode、PreviewFrame、PointerResolver 和实机验收仍未完成。实际试玩存档未清除。

## 客户端会话包与公开入口（2026-11-14）

- 删除 ClientSessionManager 名称，公开协调入口改为 `ClientSessionSystem`；当前对象查询改为 `currentScope`，调用者全部迁移，无兼容别名。
- 草稿模型／Codec、回执模型／Codec／延迟保存、有界队列／NBT 辅助、提交来源／结算规则分别迁入 draft、receipt、storage、submission；纯能力测试跟随迁包，跨能力回归仍由会话层测试覆盖。共享身份 Codec 方法和队列入口只为真实跨包调用公开。
- 整个 client.session 树纳入包容量和职责说明检查；补齐 reconnect 的包说明。首轮检查捕获该说明缺失，修正后 `.temp/architecture-client-session-layout-final-verified.log` 的完整 check 与独立新目录全量 312 项必需 GameTest 通过。
- 作用域仓库、提交编排、OperationContext、Mode、PreviewFrame、PointerResolver 和实机验收仍需继续。实际试玩存档未清除。

## 作用域仓库与输入边界（2026-11-14）

- `ClientScopeRepository` 拥有作用域集合、当前作用域、激活边界、备用输入／选区和驱逐；总入口仅在共享 IO 队列及延迟回执保存空闲后调用驱逐。不再保留总入口的 playerSessions、独立当前字段、备用状态和 cleanupReplayed 集合。
- 清理重放标记归具体作用域，重新进入时重新设为待处理；重复激活同一作用域不会重复重放。跨维度、跨玩家／服务器的不同清理语义保留。草稿／回执的读取失败和未保存内容仍阻止驱逐。
- `hasLiveWork` 和 `canEvict` 由作用域表达；输入测试直接查询所属对象。三项输入边界测试改用真实仓库，不再反射构造会话总入口或调用其私有激活／草稿门禁方法；输入内部字段继续保持包内可见。
- `.temp/architecture-client-scope-repository-verified.log`：完整 check 与独立新目录全量 312 项必需 GameTest 通过。新增清理重放的每次进入／跨环境隔离测试。提交编排、OperationContext、Mode 与实机验收仍未完成；实际试玩存档未清除。

## 编辑上下文实例化与预览所有权（2026-11-14）

- 删除静态 `ClientOperationController`，每个 `ClientScope` 持有独立 `OperationContext`。输入、渲染、网络消费和世界事件显式取得当前实例，没有旧名称转发。选区与手势绑定构造时的对象，不再在每次查询中寻找全局选区。
- 捕获任务、提交等待、失败高亮、变换临时状态、源遮罩及错误信息归具体实例。捕获和保存回调保留当前选区所有者、连接、世界和提交 ID 校验；离开环境取消临时任务，不继续推进旧捕获。客户端仓库注入共享剪贴板，保留跨世界复制粘贴。
- `OperationPreviewState` 独立管理 live revision、重连等待与待确认快照，只返回接受／丢弃草稿等结果，不直接写草稿。首次作用域激活不制造重连；实际环境切换重置预览身份。保留重复 revision 拒绝、后续快照替代待确认快照、超时不丢草稿和本地结束不推进服务端 revision 的行为。
- 新增作用域编辑隔离、跨服务器保留草稿且清临时交互、首次快照和预览状态转换测试。提交结果测试改为注入真实 tracker，选区测试通过所属会话查询，删除对应私有静态字段／方法反射；其余 Unsafe 夹具仍待继续清理。
- 最终 `.temp/architecture-operation-context-verified3.log`：完整 check 与独立新目录全量 312 项必需 GameTest 通过。前轮新测试错误要求保留草稿的旧作用域变成空闲，现按原契约断言 POINTING；没有为通过测试丢弃草稿。新增禁止退役静态控制器与上下文静态可变字段的架构守护。
- OperationContext 仍约 1700 行；本阶段完成状态所有权和预览切片，尚未完成手册要求的八个能力组件。跨仓库提交协调、其余客户端能力拆分、Mode／PreviewFrame／PointerResolver、全操作树数据化与其他剩余主线继续保留。没有清除实际试玩存档，正式构建仓库配置未改动。

## 独立剪贴板能力（2026-11-14）

- `OperationClipboardActions` 完整接管复制、原位复制、粘贴、容量／内容预算和落点计算，构造时绑定所属工作区、门禁与变更通知。输入直接调用能力，不在 OperationContext 保留旧动作转发。
- `OperationClipboardRepository` 拥有共享缓存和文件位置；成功写盘后发布、读取失败保持重试的规则继续复用。作用域仓库注入同一实例，保持跨世界剪贴板；单独构造作用域仍可使用独立仓库。
- `ClipboardActionResult` 随动作返回成功或明确失败，剪贴板反馈不再使用编辑器“读后即清”的错误字段。其他提交／捕获动作的错误通道仍待后续迁移，不能视为手册 5.2.3 全部完成。
- 新增无内容不访问存储、提交／编辑门禁优先、缺少世界不修改工作区测试；保留原有缓存发布与失败重试测试。增加包容量和禁止反向依赖总入口／输入／控制器的架构约束。
- `.temp/architecture-clipboard-actions-verified.log`：完整 check 与独立新目录全量 312 项必需 GameTest 通过。OperationContext 降至约 1575 行，变换、捕获、提交和其他手册主线仍待继续拆分。实际试玩存档和正式构建仓库配置未改动。

## 变换与调整能力（2026-11-14）

- `OperationTransforms` 绑定所属工作区，接管快捷旋转／翻转、移动、滚轮移动／局部堆叠、点／面几何调整、变换更新与完成／取消。输入和渲染直接调用组件，不在 OperationContext 保留旧动作转发。
- 组件内部拥有 resize 扫描、待调整点、捕获令牌、延迟结束标记及每次编辑的限额提示。环境清理只调用组件的 cancel；旧令牌不能结束或取消其他工作区编辑。构造注入提交／捕获门禁、当前作用域判断、失败反馈及变更通知，不反向查询总入口。
- 保留原始快照调整、滚轮 0.25 秒合并、局部重复步长和旋转／缩放算法。已有输入与渲染回归测试全部迁到实际组件；新增双工作区隔离、取消恢复、外来 token、提交／捕获阻止移动且不增加历史用例。
- `.temp/architecture-operation-transforms-verified.log`：完整 check 与独立新目录全量 312 项必需 GameTest 通过。补齐包容量、职责说明和禁止依赖总入口／输入／编辑协调器的规则。
- OperationContext 仍约 1200 行，提交、选区草稿／智能捕获和生命周期编排仍需继续拆分；其他手册主线与长按画线实机验收保持未完成。实际试玩存档与正式构建仓库配置未改动。

## 独立回执查询与结果处理（2026-11-14）

- `OperationReceipts` 接管当前维度的未完成查询、按协议上限分批、答复匹配、成功清理确认与结果提示。没有编辑工作区依赖，也不触发提交或自动重发；存储、维度、传输、时钟与失败通知通过构造注入。
- `SubmissionReceiptAccess` 描述所需的回执操作，由 ClientSessionSystem 实现。处理器不查询会话总入口；构造在所属上下文中，离开当前作用域后维度供应返回空，不发送旧上下文查询。
- 保留已知 APPLIED 对 UNKNOWN 的优先级、待清理重试、不匹配维度／未知 ID 忽略、查询失败保持未完成、相同结果不重复处理和清理不确定时显示待重试的语义。新增五项无磁盘测试覆盖上述边界及批次容量。
- `.temp/architecture-operation-receipts-verified.log`：完整 check 与独立新目录全量 312 项必需 GameTest 通过。新增包容量、职责说明和依赖约束；编辑协调器降至约 1075 行。
- 提交持久化和在线结果对编辑状态的结算仍在 OperationContext，尚未完成整个提交能力迁移；选区、生命周期与其他手册主线继续保留。实际试玩存档和正式构建仓库配置未修改。

## 实例迁移回归：失败位置记录（2026-11-14）

- 复查发现实例字段改名后，rememberFailedWorkspaceTargets 的同名参数遮蔽了实例集合，可能清空只读回包列表并中断在线失败处理。现改为明确的 positions 参数，只写上下文自己的集合。
- 新增真实失败回包测试，验证冲突位置记录、原始 payload 不变、工作区解锁、提交等待结束及可编辑内容保留。
- `.temp/architecture-conflict-positions-verified.log`：完整 check 与独立新目录全量 312 项必需 GameTest 通过。该修复不改变冲突语义；其余手册工作继续保留。

## 选区编辑能力与回执编码修复（2026-11-14）

- `SelectionEdits` 绑定所属选区会话，接管选点、智能捕获、远端草稿接纳、固定、移除及本地撤回。捕获、取消和逆操作基线由组件管理；提交门禁、当前所有权、变更和失败通知通过构造注入。输入与渲染直接使用该能力，OperationContext 不保留旧动作转发，降至 782 行。
- 新增双会话编辑／历史隔离、撤回选点、基线失效及提交期间拒绝编辑测试。已有指针、滚轮、远端选点、渲染和作用域回归测试迁至实际组件。补充包职责、容量及禁止反向依赖总入口／输入／协调器的架构检查。
- 修复 `OperationWorkspaceReceiptPayload` 写入遗漏维度的问题。读取先取维度、再取回执列表；写入现按同一顺序编码。新增所有可报告结果、空列表、最大列表及后续数据边界的往返测试；修复前两项测试均失败，修复后通过。协议升级为 85，不增加旧协议解码分支。
- `.temp/architecture-selection-receipt-verified.log`：完整 `check` 与独立新目录全量 312 项必需 GameTest 通过；`git diff --check` 通过。复查历史、草稿和恢复入口，继续只支持当前格式；试玩旧数据的直接清除授权保留，本轮无需清除实际存档。
- 提交编排、客户端 Mode／PreviewFrame／PointerResolver、完整操作树迁移与手册其他剩余项仍未完成；长按画线仍需实机手感验收。本轮没有提交或推送 Git，正式构建仓库配置未改动。

## 独立提交能力与显式范围（2026-11-14）

- 选区编辑与回执编码阶段已提交并推送：`4d1f12d`。
- `OperationSubmissions` 接管提交准备、输入请求、等待阶段、超时及在线结果结算；`WorkspaceSubmissionTracker` 随能力迁包并删除仅用于测试的 begin 重载。存储通过 `SubmissionStorage` 注入，保存完成后返回发送命令；连接与输入请求通过 `SubmissionEnvironment` 注入。组件不反向查询输入、编辑协调器或会话总入口。
- 用 sealed `SubmissionScope` 区分整区提交和局部部件提交，局部范围明确 KEEP／REMOVE 选点草稿。范围在准入成功后冻结，修复重复提交在 pending 检查前清空原有局部范围的问题。局部成功保留余量及原选点模式；局部不可重试失败仍保留可编辑内容，整区不可重试失败仍清除草稿。
- 删除 `lastOperationFailureKey` 及其读后即清入口。提交和确认直接返回 `SubmissionActionResult`；异步失败使用注入反馈，不让后一次操作消费前一次失败。删除协调器中的提交、回包与活动观察转发；输入、网络及预览直接调用所属能力。OperationContext 降至约 550 行。
- 准备回调同时检查 transfer、交互所有者和冻结连接；失效回调不能发送、解锁或取消新请求。离开环境使在线工作失效但保留耐久余量，明确取消才清余量。新增九项能力测试，覆盖重复提交、范围冻结、迟到准备／回包、保存拒绝、发送失败、完整／局部失败、超时、编辑器／连接隔离和局部选点模式恢复；现有上下文回包测试通过注入真实端口驱动，不再直接操纵等待器。
- `.temp/architecture-submission-final-verified.log`：最终完整 `check` 与独立新目录全量 312 项必需 GameTest 通过。源码布局检查新增提交能力依赖限制，以及禁止重引入旧错误通道和并行提交字段。`git diff --check` 通过；正式构建仓库配置未改动。
- 跨仓库的双文件持久化编排仍由 ClientSessionSystem 实现，后续还需拆分；删除捕获、Mode／PreviewFrame／PointerResolver、操作树及手册其他剩余项继续保留。长按画线仍需实机手感验收，实际试玩存档未清除。

## 删除捕获能力（2026-11-14）

- 提交生命周期阶段已提交并推送：`7b0c82f`。
- `SelectionDeletions` 自管选中源删除、未完成预览体扫描、取消与完成结果。冻结选中 ID、revision 和选点逆操作基线，继续保护未选中部件的源位置；开始扫描时保留客户端归属，服务端选点消失不会使后续提交失去身份。
- 扫描与世界可用性通过构造注入；测试使用实际 SelectionBlockCapture 和受控世界源，不用 Unsafe 或生产测试钩子。六项新增测试覆盖单点预览、空区域本地完成、取消／世界变化／基线失效、未加载失败、编辑门禁及两个编辑器隔离。
- 输入直接调用 `deletions().delete`；组件复用统一提交范围和本地余量结算，协调器只装配、推进及取消删除能力。原选区编辑依赖约束覆盖新增类，没有新增反向总入口查询。
- `.temp/architecture-deletions-verified.log`：完整 `check` 与独立新目录全量 312 项必需 GameTest 通过；`git diff --check` 通过。正式构建仓库配置及实际试玩存档未修改。
- 手册尚未全部完成：跨仓库提交持久化、Mode／PreviewFrame／PointerResolver、操作树迁移、其余布局与测试清理继续保留；长按画线仍需实机验收。下一阶段继续处理客户端持久化编排及生命周期边界。

## 客户端提交持久化能力（2026-11-14）

- 删除捕获阶段已提交并推送：`db67442`。
- `ClientSubmissionPersistence` 实现 `SubmissionStorage`，接管双文件提交准备、回执读写／重试、已应用结果结算及边界清理重放。总入口不再实现提交存储接口或保留动作转发，操作组件直接使用独立能力；ClientSessionSystem 从 790 行降至约 415 行。删除没有生产调用的单独记录回执入口，以及无调用的暂存 ID／耐久探测公开方法。
- 每次操作冻结作用域；回执、草稿和余量始终属于该对象。切换作用域后的准备结果不发送，而是将原回执记为可重试失败；不访问新作用域的文件。读取失败只通知一次，可读回执只重新开放 AWAITING_RECEIPT 的草稿读取，已丢弃草稿不会复活。
- 修复执行器在准备完成后拒绝任务的异常路径：已记录但未发送的回执不再停留 IN_FLIGHT，交给现有延迟保存队列重试。队列容量拒绝仍不创建回执或预占文件令牌；两个文件保存且令牌有效后才返回发送命令。
- 新增独立能力测试覆盖编码期间切换作用域、准备后执行器拒绝及恢复、第二个文件写入失败；使用独立队列、受控执行器和真实文件，不查询全局会话。原异步提交、局部余量和回执读取／恢复边界测试迁至实际能力；保留既有失败／重试契约。
- `.temp/architecture-client-persistence-verified.log`：完整 `check` 与独立新目录全量 312 项必需 GameTest 通过；`git diff --check` 通过。新增禁止持久化能力反向依赖总入口、输入、渲染与编辑协调器的源码规则。
- Mode／PreviewFrame／PointerResolver、操作树、全包迁移及其他手册剩余项继续保留，长按画线仍需实机验收。客户端 NBT 文件仍有独立原子写实现，下一阶段收拢至共享原语。正式构建仓库配置及实际试玩存档未修改。

## 统一客户端原子文件发布（2026-11-14）

- 客户端提交持久化阶段已提交并推送：`d7361f1`。
- `AtomicFiles.prepare` 拥有编码完成且已强制写入的临时文件；单所有者可在自己的代次锁内提交，未提交关闭只丢弃临时文件。直接 `write` 复用同一生命周期。
- `OperationClipboardStore` 删除独立临时文件／移动实现及普通覆盖回退，复用共享原语。保留压缩前和发布前令牌校验，不在压缩期间持有写入锁；旧代次不能覆盖新内容或恢复已删除草稿。
- 新增延迟发布、重复提交拒绝、取消不创建目标、丢弃旧准备不覆盖新文件测试。源码检查禁止客户端 NBT 存储重引入独立移动或非原子回退。
- `.temp/architecture-shared-atomic-verified.log`：完整 `check` 与独立新目录全量 312 项必需 GameTest 通过；生产持久化入口继续仅支持当前格式。实际试玩存档无需清除，正式构建仓库配置未改动。
- Mode／PreviewFrame／PointerResolver、操作树迁移、其余布局及手册剩余项继续推进；长按画线仍需实机手感验收。

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

## 选区视觉指针帧（2026-11-14）

- 各模式滚轮决策阶段已提交并推送：`9bbd97c`。
- 新增不可变 SelectionPointerState 与 ClientPointerFrameSystem。选区创建候选、Gizmo、面和部件目标在帧开始解析一次，HUD／世界渲染和视觉悬停共用结果；删除输入总入口中的渲染帧订阅。渲染转发不再触发选区射线／模型命中。
- 物理点击仍实时解析；修正输入间接借用视觉 Gizmo 查询的两处路径，不使用上帧结果创建拖动。手柄生成仍依赖原预览模型，完整 InputView／PreviewMode 迁移尚未完成。
- 画面不可交互时捕获空结果，所有者变化拒绝旧帧，清理只影响所属解析器。捕获失败先清空结果，不能遗留旧目标；模型缓存失效与帧清理分离，避免模型读取抹掉刚捕获的目标。
- 新增帧内只解析一次、物理点击重新解析且不覆盖帧、所有者失效、空帧、失败恢复和多会话隔离测试；架构检查禁止渲染反向解析选区目标及输入调用视觉命中转发。
- 最终 `.temp/architecture-pointer-frame-final-verified.log`：完整 `check` 与独立新目录 312 项必需 GameTest 通过，包含最终输入修正和新增测试。实际试玩存档与正式仓库配置未改动。
- 当前只实现选区指针帧，不能视为完整 PreviewFrame；模式按键／指针、服务端模式、统一动画和透明度、完整操作树及布局迁移仍待完成。

## 统一帧动画相位（2026-11-14）

- 选区指针帧阶段已提交并推送：`e1b3b6f`。
- ClientPointerFrameSystem 扩展并改名为 ClientPreviewFrameSystem：每帧一次采样 AnimationPhase，包含单调时间、扩展样式时间和蜡笔绘制帧。虚线位移、虚影呼吸、蜡笔顶点／材质 shader 和 VisualStyleContext 共用同一相位；删除独立 BoilClock。
- 保留现有虚线速度／周期、2.4 秒呼吸、资源定义 boil_fps／boil_sheets、相邻绘制帧不重复和静态 sheet 0。稳定哈希提取到 core.math.StableHash，原蜡笔抖动与长短出头种子计算不变。
- 纯时钟测试覆盖同绘制帧重复采样、下一帧推进、资源设置变化、静态种子、不合法时序拒绝及与原虚线／呼吸公式对照；原绘制帧范围和不重复测试迁至实际时钟。源码检查禁止恢复独立 BoilClock。
- `.temp/architecture-animation-frame-verified.log`：完整 `check` 与独立新目录 312 项必需 GameTest 通过。实际试玩存档未清除，正式构建仓库配置未修改。
- worldPreviewOpacity 的隐式传递、完整 PreviewFrame／PreviewMode、模式按键／指针与服务端 Mode、完整操作树和其余手册项目仍需继续完成。

## 删除全局可变预览透明度（2026-11-14）

- 统一动画阶段已提交并推送：`feccd37`。
- 删除 worldPreviewOpacity 及更新／边界重置。现有可见性规则恒为 1，唯一临时变更来自 Alt 选区；该淡化现显式传入服务端选区的面、线、控制点、法线箭头、参考线与 Gizmo，不再 try/finally 改写共享状态。
- 工作区和草稿继续使用自身显式透明度；缓存网格不烘焙临时全局透明度。增加源码约束，禁止两处渲染总入口恢复共享可变值。底层绘制转发仍需后续移出 Core，不能据此视为渲染管线全部完成。
- `.temp/architecture-explicit-opacity-verified.log`：完整 `check` 与独立新目录 312 项必需 GameTest 通过；实际试玩存档和正式构建仓库配置未修改。
- 完整 PreviewFrame／PreviewMode、模式按键／指针和服务端模式、操作树和其余手册项继续保留。

## 独立绘制组件与反向依赖清理（2026-11-14）

- 显式透明度阶段已提交并推送：`c7584ed`。
- 提取 SelectionVolumeRenderer，保留棱柱／长方体的权威世界边界、膨胀量和主题颜色。选区直接调用独立体／线绘制；盒线框直接调用已有 DashedBoxRenderer，不再通过 Core 转发。
- ShapeShellRenderer、BuildingShellFaceBuffer 直接使用 PencilStroke／PreviewQuads；控制点回退直接创建显式参数的 ControlPointRenderer。删除 Core 的写面／画线公开适配，以及无调用的虚线盒和旧轮廓分支；源码约束禁止 shell／cache 依赖渲染总入口。
- 首轮 `.temp/architecture-render-primitives-verified.log`：check 通过，但 separatePressesAndRepeatedPositionsRetainDistinctUndoBoundaries 超时，停在等待第二次按压撤销；未改生产历史逻辑或测试断言。未改代码的新目录复验 `.temp/architecture-render-primitives-recheck.log`：完整 check 与 312 项必需 GameTest 全部通过。单次超时尚未稳定复现，不将其记为已修复。
- 实际试玩存档和正式构建仓库配置未修改。完整 PreviewFrame／PreviewMode、模式按键／指针和服务端模式、操作树及其余手册项目继续推进。

## 专用工具按键由模式处理（2026-11-14）

- 独立绘制组件阶段已提交并推送：`5d9f3f2`。
- ClientMode 新增 onKey，KeyInput／KeyView 为不可变事实，KeyDecision 表达动作；注册表按所属模式调用，只有 PASS 回落到空闲。QuickReplaceMode 处理 R 与 Ctrl+Z／Y，SmartFillMode 处理 F／Enter，IdleMode 声明工具进入。ClientModeKeyInput 在输入边界执行动作，模式不引用客户端总入口／网络／渲染。
- 删除总输入中的工具按键 if 链及 ClientSmartFillSession 的物理按键判断；填充能力只保留 start／confirm。保留绑定和历史冲突优先、任务快照到达时已有工具优先、松开与重复事件只消费不二次确认、Ctrl+F 不触发填充等规则。物理按压时的首个填充请求仍立即建立，避免快回复将排队副本误当作确认。
- 穷举所有阶段、128 组上下文／修饰状态、8 个按键与三种动作，对照原规则；补充填充确认／取消通过及活动会话不继承空闲入口用例。
- `.temp/architecture-mode-key-verified.log`：完整 `check` 与独立新目录 312 项必需 GameTest 通过；实际试玩存档和正式构建仓库配置未修改。
- 这是专用工具键迁移，通用快捷键／取消／提交和指针处理仍在原输入流程；服务端 Mode、完整 PreviewFrame／PreviewMode、操作树与其余手册项目尚未完成。
