# 架构重构进度归档：服务端会话、意图与协议

返回 [进度索引](../2026-11-13-architecture-refactor-progress.md)。按时间顺序追加，不在此处维护当前计划。

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

## 协议方向与共享数据边界（2026-11-14）

- 37 个 C2S、20 个 S2C 消息及预览 Codec 迁入 `protocol/c2s/<feature>` 与 `protocol/s2c/<feature>`。工作区上传和回执另按 workspace 分包；共享回调作用域归 `protocol.callback`。原 `network.payload` 移空，不保留旧路径别名；消息 ID、字段和协议版本 84 不变。
- 删除未注册的合并 `BuildingPreviewPayload` 及其无生产调用的 wire Codec。共享 `BuildingPreviewSnapshot`、会话、参数和效果值归 `common.preview.quickshape`；真实分包的 Codec 往返测试继续覆盖原数据，接收方验证 revision 后复用统一组合入口。
- 设置装配移到服务端 `QuickShapePreviews`；`PlayerPreviewSync` 改为 `server.sync.PreviewSync`。压缩参数、层级和设置坐标移至 `common.history`，上限归参数模型所有；删除客户端与设置载荷对服务端历史类型的引用。冻结命中规则、形状和射线数值校验移至 `common.raycast`。
- 新增协议与共享模型的运行时依赖约束、收发方向隔离、包容量及职责检查。旧 `network.payload`／`network.sync` 引用被禁止；协议仍引用尚未迁移的共享领域类型，不能据此宣称完整四层依赖迁移已完成。
- `.temp/architecture-protocol-verified.log`：完整 `check` 和独立新目录全量 294 项必需 GameTest 通过，2346 项单元测试、0 失败、1 跳过。首次编译暴露迁包后失效的隐式同包引用，已补充明确 import 并完整重跑；没有改动交互规则。
- 用户新增阶段提交／上传要求。本节点将此前尚未提交且已验证的重构一并保存；后续验证通过的阶段分别建立提交。实际试玩存档未清除，正式构建仓库配置未修改。长按画线的实机手感验收及前述其余主线仍未完成。

## 跨会话协调移出快速起形（2026-11-14）

- 跨会话分类、物理修饰键同步、活动编辑取消、退出、玩家脱离、换维停放／恢复与预览发布统一归入所属 `SessionSystem`。快速起形的内部结束方法仅处理自己的草稿，不再直接调用选区和几何系统；所有旧调用点迁移，不保留转发接口。
- 保留原优先级和顺序：选区 → 几何 → 起形 → 手持物品分类；取消活动编辑保留停放草稿和世界任务；退出时按历史恢复状态决定是否取消任务；换维先停放全部源编辑器，再恢复目标编辑器。修饰键仍由玩家实体作用域持有，迟到实体清理不能影响替换实体。
- 删除起形／选区对历史恢复状态的重复转发，调用方直接查询历史服务。截获丢弃物品的归还逻辑移入 `server.player.TossedItems`，保持物品合并、空槽、背包与掉落回退规则。
- 新增三个会话边界 GameTest，覆盖取消保留任务／停放草稿、三类编辑器往返换维及活动会话优先级；原修饰键、空闲入口、长按画线、预览与生命周期测试改用真实所属入口。新增架构约束，防止快速起形重新承担跨会话协调。
- `.temp/architecture-session-boundary-verified.log`：完整 check 与独立新目录全量 303 项必需 GameTest 通过；2385 项单元测试、0 失败、0 错误、1 跳过，正常停服。本阶段单独提交并推送；任务业务进一步拆分、Intent／协议、客户端主线与实机验收仍未完成。

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

## 服务端模式注册与编辑器生命周期（2026-11-14）

- `server.mode` 为每个服务端运行时装配冻结的类型化模式注册表：快速起形、选区、五种几何工作流、特殊物品与原版回退。模式分别拥有草稿查询、射线规则、右键、模式切换、滚动、确认和预览同步。未新增全局会话事实。
- `SessionSystem`、`SessionIntentHandlers` 和 `ServerInputDispatcher` 改为查询注册表，删除重复的模式 if／switch 与三编辑器活动状态组合。网络请求序列、回调作用域、预览版本、候选位置上限和任务门禁继续由原边界校验。
- 三类编辑器实现统一生命周期接口；取消、移除、修饰键同步和跨维度停放／恢复各只登记一次。五种几何模式共享一个草稿仓库，换维保持先停放全部、再恢复全部。普通动作保留选区优先，确认保留几何优先；异常多会话下的命令能力限制仍检查全部活动编辑器。
- 删除快速起形旧结束／按 UUID 移除接口，直接使用统一生命周期方法，不保留兼容转发。新增注册完整性与冻结单测、五种几何模式选点和动作优先级 GameTest；原会话边界用例继续覆盖任务／停放草稿保留和三编辑器往返换维。
- 验证中：首次定向命令被并行客户端适配器签名迁移阻断，尚未执行到测试；需在客户端整合后重跑定向与统一检查。本节不代表整个 Mode 主线或手册验收完成。
- 建议守护只约束协调边界：`SessionSystem` 的分类／取消／换维／当前预览委托模式表；`SessionIntentHandlers` 不再导入三类业务系统；`ServerInputDispatcher` 的普通右键／射线不再按分类 switch，起形准入复用 `ownsInput`。专属载荷仍可访问类型化草稿、校验请求水位和预览版本，不以宽泛禁令破坏业务边界。

## 公共几何领域闭包迁移（2026-11-14）

- 首批迁移 19 个真实共享领域类型：`common.geometry` 拥有模式、动作、命中、数值、点、点集合、控制点角色和几何阶段；`common.geometry.session` 拥有七种密封几何草稿／状态；交互分类归 `common.interaction`，翻译契约归 `common.text`。保留类型与算法行为，不保留旧包桥接。
- 更新生产、测试和 GameTest 的精确引用，并将四个领域测试迁至对应公共包。删除迁移文件未使用的 `fastplace.world.*`；19 个旧全限定名在全 `src` 已无残留，新公共类型未引用 `fastplace`、客户端、服务端或协议层。
- 首轮编译发现控制点角色存在隐式样式依赖，以及两个长类型名被短类型名前缀误替换。已将角色保留为纯语义枚举，主题颜色映射移至 `client.controlpoint.ControlPointStyle`；反馈仅保存是否响应悬停。修回未迁移的 `GeometryActionContext`、`GeometryPointerSequence`，并按完整类型边界重扫全部 Java 导入，未保留颜色／旧包兼容方法。
- 生产编译随后通过；测试编译的 42 项错误均来自 `GeometryShapeStateUndoTest` 仍留在旧包，现已迁至状态所属的公共包，未公开任何内部字段或构造器。交互分类、翻译契约与玩家消息三个小型 API 包补充职责说明；保留共享语义与服务端消息投递的边界，不为凑包容量混放类型，也不新增规则豁免。
- 尚未迁移的起形闭包需要先区分 `QuickShapeCandidateContext` 对生成算法的调用、`QuickShapePointerSequence` 的协议水位，以及包含 `BlockPlaceContext` 构造行为的放置快照。选区闭包还依赖设置常量、Gizmo 的反馈文字和 `SelectionPrism` 的离散面算法，不能仅为消除依赖边而整体移入公共层。
- 未修改架构债务基线；`server.mode` 仍引用尚未迁移的起形／选区草稿、射线、物品解析、设置和特殊物品处理器，需分批消除。编译与运行验证由主任务串行调度，本批不自行启动 Gradle。

## 起形模式词汇与历史上限归属（2026-11-14）

- 九个纯模式／阶段／规则类型迁至 `common.quickshape.mode`；直线离散平局规则 `LineTieBias` 迁至 `common.geometry`，保留原有纯函数行为。精确按完整类型边界更新引用，未移动起形草稿、放置上下文或指针水位。
- `SessionHistoryLimits` 在公共历史域集中拥有默认会话撤销上限 100 和最大撤销上限 800。选区草稿、玩家设置与命令参数范围直接引用该所有者，删除设置类中的常量别名；未改变存储值、默认值或合法范围。
- 全 `src` 静态复核：十个旧类型全限定名和旧设置常量引用均无残留；新增公共类型未引用旧领域层或客户端／服务端／协议类型。编译与运行验证仍由主任务统一执行，本批未启动 Gradle。


## 纯起形闭包与放置快照分层（2026-11-14）

- `PlacementContextSnapshot` 迁至 `common.placement`，仅保存不可变命中、方向和首击状态；运行时捕获与原版 `BlockPlaceContext` 构造归 `PlacementContexts`。原替换规则、冻结方向、首击状态及候选锚定不变，生产／测试／GameTest 的工厂与链式适配调用均迁移，不保留旧类或兼容方法。
- 六个起形草稿／工作流／纯规则及其私有 `SessionValue` 生命周期值迁至 `common.quickshape`，四组对应单元测试随所属模型迁包。新公共闭包不依赖旧领域包、客户端、服务端、协议或游戏运行时对象。候选几何上下文仍依赖生成器，指针序列仍包含协议水位；本批未为机械迁包扩大到这些边界。
- `QuickReplaceTarget` 只保留结果值与共享属性复制，射线和玩家放置状态预测抽至运行时 `QuickReplaceTargets`；客户端预览与服务端笔画继续调用同一实现，删除公共模型对玩家／旧放置适配器的依赖。
- 服务端模式改用实际所属系统的 `active` 查询，删除无人消费的泛型草稿查询契约；选区模式不再导入 `OperationSession`。起形候选校验收回真实 `QuickShapeSystem.cycleStageMode` 边界，物品准入查询同属该系统。特殊物品的真实注册和执行实现迁至 `server.specialitem`，模式包已无旧领域层导入，未增加基线或空转发。
- 全 `src` 静态复核：迁移旧全限定名、旧工厂、未解析导入和新公共闭包的运行时反向引用均为零；`git diff --check` 通过。没有启动 Gradle；编译、架构检查和运行回归由主任务统一串行执行。本节只记录本批改动，不表示全手册验收完成。

## 双端共享放置预测能力（2026-11-14）

- 八个放置上下文／预测类型整体迁入 `common.placement.context`：物品准入、冻结上下文适配、虚拟支撑、数据化回退和快速替换预测由同一能力拥有。客户端、服务端、协议外围和 Mixin 使用精确新路径，纯 `PlacementContextSnapshot` 仍独立保存在 `common.placement`；不复制 `isPlaceable`，不保留旧包转发。
- 逐类确认只依赖通用 `Player`／`Level`，不包含专用客户端、服务端或协议对象。数据包注册事件的装配移回现有 `FastFormer` 初始化根，规则能力只保留真实注册键、Codec、排序和状态求值；注册 ID 与行为不变。
- 规则值单测随类型迁包，全部 sourceSets 与虚拟支撑 Mixin 同步更新。全源码静态检查旧包路径、专端／旧领域反向依赖和未解析 import 均为零；统一编译、架构与运行测试仍由主任务执行，本批未运行 Gradle。
