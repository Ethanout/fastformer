# 2026-09-15 运行路径问题证据

[返回审计索引](D:/重要的资料/项目/__mc/fastformer/docs/plan/bugs/2026-09-15-interaction-performance-update.md)

## 第七轮本人审计：快速替换的冻结和预览

### BUG-Q（P2，类别 1/2/3）：全局冻结后，快速替换持续被去重

- 位置：`src/main/java/io/github/fastformer/fastplace/QuickReplaceManager.java:32-34`。
- 原因：去重键使用 `level.getGameTime()`。设置入口 `FastPlaceNetwork.java:178` 调用原版 `setFrozen`，冻结期间世界时间不递增。
- 原版证据：当前构建的 `ServerLevel.java:340-363` 仅在 `runsNormally()` 时执行 `tickTime()`；`:446-447` 在其中递增世界时间。`TickRateManager.java:56-60` 在冻结且未步进时返回不推进状态。
- 触发：冻结期间，玩家已有一次通过前置检查的快速替换请求。其后即便换目标、松开再点击，世界时间仍相同，所有请求都直接返回 `false`。第一次请求即使命中空气，也会先写入去重记录。
- 独立性：预先存在去重记录即可触发，不依赖 BUG-P 的空指针。修复首次拆箱异常并不能修复此问题。
- 影响：模组允许冻结世界，但快速替换在冻结后无法继续。客户端仍可显示候选并发送请求，服务端拒绝分支没有结果提示。解除冻结且世界时间推进后可以恢复。
- 验证：同一表达式探针在固定时间值下连续三次输出 `rejected=true`，时间增加后输出 `rejected=false`。这是表达式执行与原版调用链证据，未做游戏窗口实测。
- 验收：使用冻结期间仍推进的请求时钟去重。冻结时不同服务器 tick 的替换应生效，同服务器 tick 的重复请求仍只执行一次。

### BUG-R（P2，类别 2）：快速替换预览不继承原方块属性，实际放置却继承

- 位置：`src/main/java/io/github/fastformer/client/placement/QuickReplaceMode.java:50-54`；`src/main/java/io/github/fastformer/fastplace/QuickReplaceManager.java:47-48`。
- 原因：客户端直接预览 `PlaceableItems.placementState` 的结果。服务端得到该结果后，还调用 `copySharedProperties`，用原方块属性覆盖目标属性。客户端没有对应步骤。
- 明确案例：手持普通白桦原木，从东侧指向轴向为 Y 的橡木原木。原版 `RotatedPillarBlock.getStateForPlacement:57-58` 依据点击面返回 X 轴，客户端预览 X 轴。服务端继承原橡木的 `AXIS=Y`，实际替换为 Y 轴。
- 渲染链：`FastPlaceClientPreviewCore.renderQuickReplaceGhost:1733-1743` 直接绘制 `preview.state()`，没有在渲染时补做属性继承。
- 影响：用户无法用候选模型判断最终朝向。其他共有状态属性也存在相同差异条件，但本轮不宣称逐种方块实测。
- 验证级别：完整静态调用链，结合当前构建中的原版原木放置逻辑。没有成功启动方块注册环境，因此不标记为游戏内复现。
- 验收：预览和提交共用替换状态规则。覆盖原木轴向、楼梯朝向和半砖状态；目标位置的替换上下文也应一致。

### 本轮边界

- 本轮只新增审计记录。没有更改生产逻辑，没有操作用户存档。
- 快速替换缺少任务状态检查，以及微调异常后的回滚完整性，仍需进一步追踪，暂不列为确认问题。
- 英文写作检查器对中文草稿评分为 15.38/100，仅识别 13 个英文词，未通过英文阈值。该结果不能评价中文可读性，已人工检查证据与措辞。

## 第六轮本人审计：快速替换与历史失败路径

### BUG-P（P1，类别 1/2/3）：玩家首次快速替换触发空指针异常

- 位置：`src/main/java/io/github/fastformer/fastplace/QuickReplaceManager.java:33`。
- 触发：玩家在当前 JVM 中首次通过快速替换的前置检查。要求创造模式、模组启用、没有活动选区或历史任务，且手持可放置物品。
- 原因：`LAST_REPLACE_TICK` 初始为空。`put(player.getUUID(), gameTick)` 返回旧值 `null`，而右侧 `gameTick` 是基本类型 `long`。`==` 比较对左侧自动拆箱，因此抛出 `NullPointerException`。
- 影响：代码尚未执行射线检测或替换，首次请求已经失败。Map 插入先于异常发生，后续 tick 的请求可能正常，使故障表现不稳定。当前方法和网络处理器没有针对此异常的操作结果提示。未验证框架最终如何处理异常，不声称会导致整个服务器退出。
- 入口：`ClientPlacementRouter.quickReplace:68` 发送请求，`FastPlaceNetwork.handleQuickReplace:215` 在服务器调用该方法。
- 验证：临时 Java 探针执行相同类型和表达式，输出 `QUICK_REPLACE firstAccess=NullPointerException entryRecorded=true`。这是语言表达式复现，不是游戏内完整流程实测。
- 验收：首次请求不抛异常，同 tick 重复请求仍被去重，后续 tick 可以替换。分别覆盖新玩家和同进程后续请求。

### RISK-C（待验证，类别 1/5）：未保存历史被淘汰后，重试可能缺少原数据

- `WorldHistoryManager.addBatch:249-256` 先入缓存并裁剪，再异步保存。缓存淘汰不检查保存状态。`retryPersistence:1233` 仅重发仍在缓存的记录，但索引包含完整历史顺序。
- 底层复现：模拟未保存的旧记录被容量裁剪，仅将剩余记录写入临时 `HistoryBatchStore`，再发布完整索引。实际存储类返回 `History index references an unpublished batch`。所有文件位于新建临时目录，没有操作用户存档。
- 限定：普通大型任务使用 `pollPreparedOperation`，先保存再加入缓存，不能直接认定它受影响。快速替换和方块微调仍调用 `record`。还需证明这些入口能在保存恢复前达到缓存淘汰条件，才能升级为已确认问题。

### 本轮排除项

- 当时未把历史分页缺少取消方法单独登记为 bug。后续 ARCH-11 / BUG-AG 核对了普通历史任务的取消方法、实际调用入口和客户端状态映射，确认普通撤销或重做也被当作不可取消恢复。单纯分页的取消边界仍需单独验收，必要恢复应继续保留。
- `PlayerPreviewSync` 的类似 `put(...) == activity` 使用枚举引用比较，不发生 `Long` 拆箱，不属于 BUG-P。
- 本轮探针实际执行完成，未运行游戏，也未修改生产代码。
- 写作检查器仅识别草稿中的 13 个英文词，评分为 7.69/100，未通过英文阈值。此分数不能评价中文可读性，已人工检查措辞和证据边界。

## 第五轮本人审计：历史分页与缓存容量

### BUG-O（P1，类别 1/2/4）：批量撤销所需记录超过缓存容量时，反复加载同一页

- 位置：`src/main/java/io/github/fastformer/fastplace/world/WorldHistoryManager.java:329`、`:973`、`:982`；`HistoryMemoryCache.java:78`；`HistoryPageLoadPlan.java:36`。
- 触发：持久化历史至少有两条记录，每条估算内存为 150 MiB；缓存保留最新一条，用户请求撤销两步。历史条数上限至少为 2。
- 调用链：`request` 先要求加载缺少的记录，直接返回，不执行已缓存的一步。`attachHistoryPage` 合并加载结果，按 256 MiB 上限淘汰尾部旧记录，再用原请求数量调用 `request`。持久化顺序仍包含被淘汰的记录，所以再次生成相同分页请求。
- 可达性：分页的 64 MiB 是保留预算，不是单条拒绝上限。`WorldHistoryPersistence.java:254` 仅在结果非空时拒绝超预算记录，因此第一页中的首条 150 MiB 记录可以返回。解码预算为 512 MiB，不排除此情形。
- 影响：请求无法进入 `HistoryTask`，反复读取和解码同一条历史，并重复显示加载旧历史提示。该问题无需磁盘读取失败，与 BUG-D 的异常后状态锁定不同。
- 执行证据：临时探针直接调用当前项目的 `HistoryMemoryCache`、`HistoryOrderCatalog`、`HistoryPageLoadPlan`。通过惰性对象设置内存计数，不分配 300 MiB 数据。连续三轮均输出 `retained=1 sameOlderPageAgain=true`。Gradle 的 `runHistoryAuditProbe` 实际执行完成。
- 验证边界：探针证明合并、淘汰和计划之间的无进展循环。服务端自动重入由当前调用链确认；未启动游戏，未构造真实的大型历史文件。
- 验收：批量撤销和重做应逐段执行并继续分页，或在无法满足时明确结束请求。不能要求全部目标记录同时驻留缓存。覆盖缓存容量不足、单条超过分页预算、方向切换和取消。

### 证据修正

- 旧记录中“分页重复请求未被证明”仅代表当时结果。本轮 BUG-O 给出明确容量条件和实际算法复现，应以本轮结论为准。
- 本轮 Gradle 成功仅证明探针执行完成，不代表全量测试重新执行或游戏内验收通过。
- FastCtx 的文件读取仍返回 `Transport closed`，本轮使用只读 shell 检查代码。仓库内仅更新审计文档，不修改程序逻辑。
- 写作技能检查器对本轮中文草稿只识别 22 个英文词，得分 4.55/100，未通过英文阈值；该结果不能评价中文可读性，已人工检查事实和段落。

## 第四轮本人审计：设置入口与界面边界

### BUG-M（P1，类别 1/5）：全局冻结数据包缺少服务端权限校验

- 位置：`src/main/java/io/github/fastformer/network/FastPlaceNetwork.java:164-179`。
- 完整路径：`playToServer(SettingsActionPayload.TYPE, ..., handleSettingsAction)` 注册接收器；payload 允许 `TOGGLE_GLOBAL_FREEZE`；接收器只判断 `context.player() instanceof ServerPlayer`，随后直接执行 `player.getServer().tickRateManager().setFrozen(!manager.isFrozen())`。
- 触发：连接该模组协议的非 OP 玩家发送该设置动作。这里没有 `hasPermission`、创造模式、会话授权或管理员配置校验。
- 影响：普通玩家可以改变整个服务器的 tick 冻结状态，影响其他玩家的世界模拟；也可以解除管理员设置的冻结。该全局副作用不应仅由个人设置消息授权。
- 反证检查：客户端是否显示按钮不构成服务端授权。当前界面也实际创建该按钮；设置命令自身只检查客户端协议支持。
- 验证级别：静态完整注册/解码/执行链，未对运行中的服务器发送冻结动作。验收需要非 OP 与 OP 各测试一次，未授权请求必须保留原冻结状态并给出拒绝反馈。

### BUG-N（P2，类别 2）：设置页在小 GUI 尺寸下控件越界且无法滚动

- 位置：`src/main/java/io/github/fastformer/client/ui/FastFormerSettingsScreen.java:96-155`、`settingsTop` 和 `contentRows`。
- 当前默认 effect 服务文件注册一个 WoodFrame effect，故 `contentRows = max(4, 1+4) = 5`。
- 精确边界：逻辑 GUI 宽度 320 时，左列 x=-46，右列结束于 x=366，两侧均超出窗口。逻辑高度 240 时，top=36，Done 按钮范围 y=212..232；高度 180 时仍为该位置，按钮完全不可见。默认窗口宽度或较大 GUI Scale 可产生这些逻辑尺寸。
- 原因：双列固定总宽 412，top 最小 36；没有按窗口大小改为单列、压缩间距或滚动容器。宽度小于 412 必然裁切。
- 影响：部分设置按钮及标题文字被裁切，小高度下完成按钮不可点击。Esc 仍能退出，不登记为彻底无法关闭。
- 验证级别：按当前布局公式算出的边界，未截图实测。验收覆盖 320x240、427x240、320x180 GUI 逻辑尺寸及 GUI Scale 切换。

### 本轮检查边界

- 读取了工作区任务的写入前匹配、失败后快照记录、方块实体恢复及设置动作全链。
- 当时未将方块实体 NBT 缺失认定为确定问题。后续 ARCH-10 / BUG-AA 核对了原版箱子同步、模组提交和目标加载全链，补充了客户端预览内容不完整的具体证据。仍不把所有空 NBT 一概视为错误。
- 本轮仅改审计记录，未触碰线上冻结状态或修改生产代码。

范围：当前工作区源码、调用链和现有设计约束。只记录审计发现，不修改程序逻辑。
