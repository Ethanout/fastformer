# 架构重构进度归档：客户端会话、选区与提交

返回 [进度索引](../2026-11-13-architecture-refactor-progress.md)。按时间顺序追加，不在此处维护当前计划。

## 起形编辑与选区动作意图（2026-11-14）

- `server.intent.quickshape` 封装上下文数值、面角度、线／体距离和阶段模式；`server.intent.selection` 封装应用／复制、选区模式与冲突模式。命令构造类型明确的意图，处理器执行既有准入、参数边界及失败反馈，无旧入口兼容转发。
- 网络应用仍先校验请求顺序，再携带原请求 ID 派发；网络只提交已确认选区，命令仍可直接提交。空会话循环模式仍更新偏好并保持原命令返回值，不改变既有行为。
- 新增命令／直接意图阶段一致性、无草稿和生存门禁、数值边界、未确认网络提交拒绝及命令复制入队测试。`.temp/architecture-edit-intents-verified.log`：完整 check 与独立新目录全量 309 项必需 GameTest 通过，正常停服。
- Intent／声明式命令、客户端实例化、Mode、完整操作树及长按画线实机验收等主线继续保留为未完成项。实际试玩存档未清除。

## 选区点编辑意图（2026-11-14）

- 点选、移除、插点、闭合底面、中键扩展、拖点和延伸统一为 `SelectionIntents`，处理器执行活动选区和可编辑门禁。拖动坐标复制为不可变值，延伸保留每包 128 步限制，拒绝范围外轴。
- 网络边界继续持有预览 revision、回调作用域、请求顺序和手势消费校验；finally 仍负责拖动完成 revision，不因派发改变旧请求／迟到事件的判定。保留长方体扩展和棱柱增加顶点的不同动作。
- 新增可变坐标冻结、必填输入、棱柱点选／拖点／移除及生存门禁测试。首轮测试忽略切换棱柱会清空棱柱点集，补齐首点夹具后通过，未修改生产切换行为。
- `.temp/architecture-selection-edit-final-verified.log`：完整 check 与独立新目录全量 312 项必需 GameTest 通过，正常停服。其余输入意图、客户端实例化、Mode、完整操作树和实机验收仍未完成。实际试玩存档未清除。

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

## 统一客户端原子文件发布（2026-11-14）

- 客户端提交持久化阶段已提交并推送：`d7361f1`。
- `AtomicFiles.prepare` 拥有编码完成且已强制写入的临时文件；单所有者可在自己的代次锁内提交，未提交关闭只丢弃临时文件。直接 `write` 复用同一生命周期。
- `OperationClipboardStore` 删除独立临时文件／移动实现及普通覆盖回退，复用共享原语。保留压缩前和发布前令牌校验，不在压缩期间持有写入锁；旧代次不能覆盖新内容或恢复已删除草稿。
- 新增延迟发布、重复提交拒绝、取消不创建目标、丢弃旧准备不覆盖新文件测试。源码检查禁止客户端 NBT 存储重引入独立移动或非原子回退。
- `.temp/architecture-shared-atomic-verified.log`：完整 `check` 与独立新目录全量 312 项必需 GameTest 通过；生产持久化入口继续仅支持当前格式。实际试玩存档无需清除，正式构建仓库配置未改动。
- Mode／PreviewFrame／PointerResolver、操作树迁移、其余布局及手册剩余项继续推进；长按画线仍需实机手感验收。

## 选区命中查询归属与射线策略复核（2026-11-14）

- 几何预览阶段已提交并推送：`9a81048`。本阶段由 OperationContext 持有 RemoteSelectionPointer，使用绑定的已接收预览、交互上下文、方块射线和捕获手柄；选点、棱柱插点／平面／高度约束、候选点、选区体积、面目标及远端手柄查询不再放在渲染总入口。
- 输入和选区绘制直接使用该能力；OperationPointerKind／Target 归选区命中包。共享 PointRaycast 保留点格 AABB 最近命中、距离上限与同距离首项优先；选区点命中仍穿透世界方块，其他控制点仍受世界遮挡限制。
- 迁移差异复核补齐 PointerRayPolicy：选区编辑优先，其次按原几何／起形／工作区／空闲关系选择射线用途，保留水中及修饰键穿透规则。输入与绘制复用同一策略；没有把视觉归属和输入模式优先级混为一套。
- 新增所属预览隔离、世界方块／选区面最近目标、缺失上下文、点格距离限制及射线策略组合等价测试；扩展源码守护，禁止输入重新通过渲染总入口查询这些选区目标。
- `.temp/architecture-remote-pointer-review.log` 定向测试和结构检查通过；最终 `.temp/architecture-remote-pointer-final.log` 完整 check 及独立新目录 312 项必需 GameTest 全部通过，进程退出码为 0。实际试玩数据未清除，正式构建仓库配置未改。
- 本阶段只完成远端选区查询能力。剩余几何／起形输入查询、完整手势归属、HUD／PreviewFrame、服务端模式、完整操作树和全项目守护仍按前文第 2～7 项推进；尚未做本阶段客户端交互实测。
