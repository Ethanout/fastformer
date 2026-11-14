# 架构重构进度归档：缺陷修复、历史与恢复持久化

返回 [进度索引](../2026-11-13-architecture-refactor-progress.md)。按时间顺序追加，不在此处维护当前计划。

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

## 取消追加日志的清理边界（2026-11-14）

- 复查任务共同生命周期时，发现 `releaseAfterCancellation` 只等待日志创建，没有等待已启动的追加；可能在追加仍使用目录时删除日志并释放租约。现先冻结本次日志、创建 Future 与追加 Future，等追加结束后再清理，成功和失败都执行清理。
- 清理闭包不读取重置后的准备器状态，仍使用原租约身份。新增受控执行器测试，验证追加期间目录与租约保留，以及旧追加失败后不删除新日志、不释放后继租约。
- `.temp/architecture-cancel-append-verified.log`：完整 check 与独立新目录全量 301 项必需 GameTest 通过；2350 项单元测试、0 失败、1 跳过，正常停服。本修复独立提交并推送；完整任务运行单元与其余手册主线继续保留。

## 恢复系统的实例所有权（2026-11-14）

- `RecoverySystem` 按服务器显式创建和关闭，持有恢复执行器、写入闸门、日志序号及已提交日志的保留状态。删除 `PersistentRecoveryJournal` 的全局 blocked 标志、提交表、待清理集合和静态运行入口；世界存盘使用非创建查询，启动前或关闭后的事件不重建系统。
- `RecoveryWriteGate` 不依赖游戏或存储。恢复期间关闭写入，仅当前版本的成功恢复可开放；后续故障、较早恢复回调和永久关闭不能被覆盖。调度失败只关闭所属服务器的闸门。
- `WorldTaskContext` 保存原闸门身份，withResume 保持同一身份，日志准备不能改用替换后的恢复系统。两类调度、选区写入、工作区写入和历史重放复用上下文查询；无服务器上下文使用独立或显式注入的闸门，没有全局备用状态。
- 日志构造时绑定所属执行器和保留状态。清理任务以登记对象身份核对路径，耐久 IO 屏障后才删除；重登记、启动恢复与关闭会使旧清理失效，旧回调不能释放新登记的 pending 标记。清理排队／耐久检查失败保留文件并允许后续存盘重试。等待历史发布的封存不再短暂登记为可清理，发布成功后才登记。
- 新增实例隔离、永久关闭、恢复版本、队列拒绝、耐久失败、同路径重登记、旧实例关闭、上下文派生和历史发布边界测试；删除生产全局测试复位开关。测试经包内构造注入依赖，既有恢复 GameTest 继续使用当前分段格式，未放宽格式或业务准入。
- `.temp/architecture-recovery-scope-final3-verified.log`：最终完整 check 与独立新目录全量 300 项必需 GameTest 通过；2376 项单元测试、0 失败、1 跳过，正常停服。此前修正两处无服务器调度夹具的直接运行实例查询；新增封存测试改用元数据夹具，并显式检测游戏版本，消除其对其他测试执行顺序的依赖。
- 本阶段单独提交并推送；正式构建仓库配置与实际试玩存档未改动。统一任务 tick、任务迁包、剩余业务状态拆分、声明式 Intent／协议、ClientScope／Mode／PreviewFrame／PointerResolver 和完整操作树等主线仍未完成；长按画线的实机手感验收仍需进行。

## 客户端提交持久化能力（2026-11-14）

- 删除捕获阶段已提交并推送：`db67442`。
- `ClientSubmissionPersistence` 实现 `SubmissionStorage`，接管双文件提交准备、回执读写／重试、已应用结果结算及边界清理重放。总入口不再实现提交存储接口或保留动作转发，操作组件直接使用独立能力；ClientSessionSystem 从 790 行降至约 415 行。删除没有生产调用的单独记录回执入口，以及无调用的暂存 ID／耐久探测公开方法。
- 每次操作冻结作用域；回执、草稿和余量始终属于该对象。切换作用域后的准备结果不发送，而是将原回执记为可重试失败；不访问新作用域的文件。读取失败只通知一次，可读回执只重新开放 AWAITING_RECEIPT 的草稿读取，已丢弃草稿不会复活。
- 修复执行器在准备完成后拒绝任务的异常路径：已记录但未发送的回执不再停留 IN_FLIGHT，交给现有延迟保存队列重试。队列容量拒绝仍不创建回执或预占文件令牌；两个文件保存且令牌有效后才返回发送命令。
- 新增独立能力测试覆盖编码期间切换作用域、准备后执行器拒绝及恢复、第二个文件写入失败；使用独立队列、受控执行器和真实文件，不查询全局会话。原异步提交、局部余量和回执读取／恢复边界测试迁至实际能力；保留既有失败／重试契约。
- `.temp/architecture-client-persistence-verified.log`：完整 `check` 与独立新目录全量 312 项必需 GameTest 通过；`git diff --check` 通过。新增禁止持久化能力反向依赖总入口、输入、渲染与编辑协调器的源码规则。
- Mode／PreviewFrame／PointerResolver、操作树、全包迁移及其他手册剩余项继续保留，长按画线仍需实机验收。客户端 NBT 文件仍有独立原子写实现，下一阶段收拢至共享原语。正式构建仓库配置及实际试玩存档未修改。
