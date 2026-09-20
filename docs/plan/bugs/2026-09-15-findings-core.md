# 2026-09-15 选区与草稿问题证据

[返回审计索引](D:/重要的资料/项目/__mc/fastformer/docs/plan/bugs/2026-09-15-interaction-performance-update.md)

## 1. 明确的程序逻辑漏洞

### BUG-A（撤回）：旧的 BuildingPreviewPayload 未经过回调门禁

本轮复核：`FastPlaceNetwork` 未注册 `BuildingPreviewPayload.TYPE`，全源码检索未找到 `applyBuildingPreview` 调用者。下面原结论缺少生产可达证据，撤回。UX-A 及对应原则冲突一并撤回。

- 文件：[ClientPayloadDispatcher.java](D:/重要的资料/项目/__mc/fastformer/src/main/java/io/github/fastformer/network/client/ClientPayloadDispatcher.java)，28-30 行。
- 触发：旧连接或旧会话的 `BuildingPreviewPayload` 迟到到达客户端。
- 证据：`applyBuildingPreview` 直接调用 `FastPlaceClientPreview.applyBuilding`。同类的 session、parameters、effect、operation、geometry 和 activity 回调都先调用 `ClientSessionManager.accepts*Callback`。
- 影响：旧预览可以覆盖当前客户端的起形状态。该路径违反当前 TODO 对 building 回调检查连接、玩家、维度和会话的要求。

### BUG-B（待核实）：提交中断线后的草稿缺少服务端结果对账

状态：待核实。未完成服务端 busy 门禁反证，以下并行写入影响不是已确认事实。

- 文件：[ClientOperationController.java](D:/重要的资料/项目/__mc/fastformer/src/main/java/io/github/fastformer/client/operation/controller/ClientOperationController.java)，849-868 行。
- 触发：工作区分块已经发送，服务端任务仍在执行时断线。
- 证据：`onDisconnected()` 无条件调用 `suspendCurrentDraft(...)`，随后清空 `WORKSPACE_SUBMISSION`。服务端任务没有被客户端回执确认前，草稿仍可在重连后按相同 identity 恢复。
- 待验证影响：再次确认是否会重复执行，仍需验证服务端任务准入和结果时序。当前证据只能确认缺少结果对账，不能确认两个任务并行写入。修复设计见 ARCH-6。

### BUG-C：同连接换维度会销毁旧维度会话和草稿

- 文件：[ClientSessionManager.java](D:/重要的资料/项目/__mc/fastformer/src/main/java/io/github/fastformer/client/session/ClientSessionManager.java)，73-86 行。
- 触发：同一玩家在同一连接内切换维度，旧维度仍有可恢复工作区或选区草稿。
- 证据：`activateScope` 在 connection 和 UUID 相同而 dimension 不同时调用 `current.endInteraction()`，并删除旧 scope 草稿文件。
- 影响：返回旧维度后无法恢复草稿。这与会话按玩家 UUID 持有、换维度不自动清空，以及 TODO 要求按维度隔离恢复的约束冲突。

### BUG-D：已有历史的分页加载失败后持续阻断编辑

- 文件：[WorldHistoryManager.java](D:/重要的资料/项目/__mc/fastformer/src/main/java/io/github/fastformer/fastplace/world/WorldHistoryManager.java)，718-727、937-980、1383-1391 行。
- 触发：已经建立内存 history 后，旧历史分页发生读取异常或 future 失败。初始加载失败不在本条确认范围。
- 证据：失败路径设置 `historyLoadFailed = true`。该标志只在 `trimToSetting` 的 `owner.history == null && owner.historyLoad == null` 分支清除；失败后的 owner 通常已有 history，因此该分支不再执行。`OwnerState.busy()` 将此标志作为永久 busy 条件。
- 影响：`request`、撤回/重做和 `ServerInputDispatcher.interactionBlocked` 持续拒绝请求，直到服务器重启。提示只说明加载失败，没有重试或恢复动作。

## 2. 可能的交互体验问题

### UX-A：旧 building 预览可能短暂覆盖当前操作

状态：随 BUG-A 撤回，不计入有效问题。

即使旧包没有造成持久数据损坏，用户仍会看到形状、线框或准星反馈突然跳回旧状态。该项与 BUG-A 共用触发条件。

### UX-B：提交中断线后重连提示不足

当前断线流程清空本地提交 tracker，但没有区分“服务端任务可能仍在执行”和“可以安全重试”。用户只能看到可恢复草稿，无法判断再次确认是否会重复写入。

## 3. 提示文字缺失

- 历史加载失败只有 `fastformer.message.history_load_failed`。失败状态会持续阻断操作，但没有“重试历史加载”“等待服务端恢复”或“联系管理员”的明确后续动作。
- 断线恢复草稿没有提示服务端任务状态。恢复确认前应至少说明“旧提交状态未知，重复确认可能重复写入”，或提供查询/等待状态。

## 4. 设计原则冲突

- 已撤回：旧 `applyBuildingPreview` 路径未找到生产调用，不作为有效的原则冲突。
- `ClientSessionManager.activateScope` 在换维度时清空会话，违反会话生命周期中“换维度不自动清空玩家会话”的约束。
- 待验证：断线草稿缺少服务端结果对账。与提交身份连续性的设计目标有差距，但重复执行尚未确认，见 ARCH-6。

## 5. 待验证风险

### RISK-A：跨维度撤回任务可能丢失世界租约

`WorldHistoryManager.acquireLease` 在尝试获取新维度租约前释放旧的 `leasedDimension`。跨维度任务若新租约获取失败，代码路径会留下无租约状态；需要实际多维度卸载/重载测试确认是否造成写锁泄漏或错误释放。

### RISK-B：持久化索引写入失败后的历史顺序偏差

磁盘写入失败会设置 `persistenceDirty` 并重试。当前静态检查尚未证明内存 undo/redo 顺序与索引重试之间一定发生偏差，因此暂不列为确定 bug。需要模拟磁盘满并比较内存顺序、索引文件和重启后的历史。

## 验证状态

- 本次排除项：结果包的失败部件数上限与工作区部件数上限不同，但当前验证器失败返回空 ID 列表，目标失败位置也在首次失败后停止收集。没有生产可达的超长失败列表证据，因此未把结果包构造上限单独登记为 bug。
- 测试证据限制：`OperationWorkspaceValidatorTest.rejectsMoreThanTenPartsBeforeReadingAnyWorldState` 使用空方块部件。当前 `MAX_PARTS` 已为 `Integer.MAX_VALUE`，测试会因空部件被拒绝而通过，不能证明仍有十部件上限。应按当前产品规则改名并拆开非空部件数量与空内容校验，不能拿此测试的绿色结果解释容量行为。
- 再次续审新增 ARCH-10 / BUG-AA，证据包括当前构建的 Minecraft 箱子同步源码、客户端捕获与协议、服务端目标写入及现有验证器测试。未执行游戏内箱子移动，保留此验证边界。新增草稿样式检查为 0.00/100，不能替代内容审计。
- 后续架构审计新增 ARCH-9 / BUG-Y 与 BUG-Z。使用当前生产撤回栈编译并运行独立探针，结果为 `CLIENT_HISTORY retained=801 executed=801`。维度事件链与现有维度可用性测试已逐段读取，BUG-Z 仍标注为静态确认，未执行游戏内传送。
- 补充 BUG-X 的选区读取影响，按共同根因合并，没有重复计数。本轮新增方案的英文样式检查均为 0.00/100，英文词数很少，不代表中文或程序正确性认证。
- 本次续审新增 BUG-V、BUG-W、BUG-X，均明确标注静态验证边界。ARCH-8 补充设置一致性的修复方案，BUG-W 补充 ARCH-1 的恢复契约，BUG-X 补充渲染与世界状态的职责边界。
- 重新执行 `runHistoryAuditProbe`，观察结果与已有记录一致：首次替换异常、冻结去重、租约提前释放、候选网格不重试、历史分页循环。未保存历史缺失仍保留为 RISK-C，不升级为完整生产复现。
- 本次只修改审计文档。构建沿用当前工作区类，并重新编译、运行临时探针。没有执行完整游戏验收，也没有把构建成功当作应用逻辑通过。
- 修正 BUG-B、BUG-D 及原则冲突段落的旧表述，避免撤回或缩小范围后的结论仍以确定标题出现。修正原有源码链接的目录层级。
- 新增中文草稿的英文样式检查为 0.00/100。英文词数很少，此分数不代表中文文风认证或代码正确性。
- FastCtx 本次仍返回 `Transport closed`，读取改用只读命令。当前工作区仍有并行修改，本文证据对应本次读取状态，不构成项目不存在其他问题的保证。

## 本轮亲自复核补充

### BUG-E（P2，逻辑/反馈）：剪贴板首次读取失败后不再重试

- 证据：`ClientOperationController.java:913-931` 在读取前设置 `clipboardLoaded = true`。`OperationClipboardStore.load` 将 IOException 转为空，controller 又将解码异常转为空。
- 触发：启动客户端后首次粘贴时发生临时文件读取错误；文件随后恢复可读。
- 结果：后续 Ctrl+V 直接返回缓存的 null，不再读取文件，持续提示剪贴板无效。只有重新复制或重启客户端才能改变该状态。
- 验证建议：已有有效剪贴板文件，首次读取注入 IOException，恢复读取后再次粘贴，应重新加载成功。目前两个 early return 及标志写入顺序证明不会重读。未做游戏内故障注入。

### BUG-F（P1，交互/性能）：确认选区同步扫描完整包围盒

- 调用链：`FastPlaceClientInput.java:743` → `handleCreateClick` → `finishDraft` → `capture`。服务端预览同步入口也调用同一 `capture`。
- 证据：`ClientOperationController.java:938-977` 三重循环逐格访问整个包围盒，读取方块实体 NBT 并构造 Map。`finishDraft:1048` 在输入处理内直接调用，没有扫描预算、分帧或取消检查。`OperationSelectionVolume.create` 不限制扫描体积。
- 触发：完成跨度较大的选区，即使其中大部分为空气仍执行全体积扫描。例如边长 256 的包围盒需要 16,777,216 次格点检查。
- 结果：客户端线程同步阻塞；预览显示上限无法保护此前的扫描，具体停顿时间尚未测量。
- 验证建议：备份测试世界内逐步扩大选区，记录确认帧耗时和 capture 访问次数。优化需要处理扫描和快照生成，不能仅降低显示上限。

### 上轮结论可信度修正

- BUG-B 的“旧任务与新提交同时写入”尚未完成服务端 busy 门禁反证，降为待核实，不能作为已确认数据漏洞。
- BUG-D 应限于已建立 history 后的分页失败。初始加载失败时 history 仍可能为 null，不能一概声称重连也无法恢复。
- 本轮 FastCtx 返回 Transport closed，改用本地只读命令。没有委派子 agent，也没有修改程序源码。

## 第二轮本人审计

## 第三轮本人审计：方块状态与提交估算

### BUG-K（P1，类别 1/2）：工作区旋转只改坐标，不改方块朝向

- 位置：`src/main/java/io/github/fastformer/client/operation/preview/WorkspacePreviewComposer.java:52`；`client/operation/transform/VoxelRotation.java:32-39`。
- 调用链：工作区预览、剪贴板复制和服务端 `OperationWorkspaceValidator.validate:74` 均通过 `resolveValues` 使用 `VoxelRotation.rotateValues`，后者传入 `Function.identity()`，原样保留方块快照。
- 触发：含有楼梯、熔炉等方向状态的结构绕 Y 轴旋转 90 度。
- 影响：方块位置旋转，BlockState 的 facing 等属性仍是旋转前的值，预览和提交后的朝向均错误。服务器没有后续状态旋转步骤，`ClientWorkspacePlacementTask.composeDesiredSnapshots:258` 直接采用验证结果中的快照。
- 反证检查：`VoxelRotation.rotate` 确有 `rotateSnapshot` 专用处理，但全生产源码检索未发现该重载的调用者，不能用未接入的方法证明当前流程已修复。
- 验证：实际项目类探针输出 `ROTATION sameSnapshot=true`，证明通用路径保留同一快照对象。楼梯朝向的完整运行探针因缺少 NeoForge LoadingModList 初始化失败，未声称已在游戏中复现。验收应至少覆盖 Y 轴 90/180/270 度及定向方块。

### BUG-L（P2，类别 1/2/4）：未缩放的稀疏结构按整个包围盒体积拒绝提交

- 位置：`src/main/java/io/github/fastformer/fastplace/OperationWorkspaceValidator.java:45-56`。
- 触发：两个相距较远的方块作为一个部件，变换为 IDENTITY，方块数远低于 maxBlocks，但包围盒体积大于 maxBlocks。
- 故障：验证器无条件按 scaledX * scaledY * scaledZ 计算 planned；实际 `WorkspacePreviewComposer.resolveValues:30-31` 在 IDENTITY 下直接返回源 Map，根本不会生成空隙中的方块。纯平移且尺寸未改变时也无需全体积扫描。
- 执行证据：两个快照放在 `(0,64,0)` 与 `(100,64,0)`，maxBlocks=100，输出 `SPARSE supplied=2 limit=100 accepted=false`。该拒绝发生在快照内容读取和世界访问之前。
- 影响：实际只有两个目标方块的合法稀疏操作被拒绝；显示端 `canResolveForRendering` 已对尺寸不变采用 source.size()，服务端估算与客户端显示策略不一致。
- 验收：不缩放或缩放后维度不变的稀疏部件，以实际源方块数量及重复次数估算；需要扫描体积的缩放路径仍应保留资源约束。覆盖纯移动、旋转、堆叠及放置上限边界。

### 本轮反证和验证范围

- `OperationWorkspaceValidator` 未调用 live lookup 是当前测试明确要求的行为（`doesNotReadLiveWorldWhenSubmittingSavedWorldSnapshot`），暂不另登记为无条件漏洞；是否应保护提交前的外部修改需要与当前保存快照语义一起判断。
- 探针复用了测试中现有的 Unsafe 惰性快照方式，不依赖伪造 Minecraft 方块注册表。它证明引用保留及预检拒绝，不证明游戏内渲染结果。
- 同一探针再次触发 BUG-G 的 `NoSuchElementException`。没有新增生产代码修改。

### BUG-G（P1，类别 1/2）：稀疏棱柱缩小后抛出异常

- 位置：`src/main/java/io/github/fastformer/client/operation/preview/WorkspacePreviewComposer.java:34`。
- 最小输入：仅在 `(0,64,0)` 和 `(2,64,0)` 有方块，X 方向缩放为 `1/3`。`scaleValues` 将目标宽度设为 1，唯一采样点是源 X=1，此处为空气，返回空 Map。
- 故障：`resolveValues` 随即对空结果调用 `OccupiedBlockBounds.from(...).orElseThrow()`，抛出 `NoSuchElementException`。
- 生产路径：棱柱缩放允许把宽度缩到 1（`ClientOperationController.java:676-683`）。`WorkspaceInteractionResolver.java:308` 通过 `resolveForRendering` 使用同一函数。渲染预算检查不会阻止此输入。
- 执行证据：临时 Java 探针使用当前 Gradle test runtime 的实际项目类，输出 `renderAllowed=true`，随后异常栈定位 `WorkspacePreviewComposer.resolveValues:34`。未启动游戏，因此记录为算法执行已复现，游戏窗口表现未实测。
- 验收：空采样结果不得抛异常；缩放策略需明确是否保留孤立方块，并覆盖细线、稀疏结构、各轴和连续缩小。

### BUG-H（P1，类别 1/3/4）：重连未收到快照超过 40 tick 会静默删除草稿

- 位置：`src/main/java/io/github/fastformer/client/operation/controller/ClientOperationController.java:897-900`。
- 触发：同进程断线后重连，玩家及连接已可用，但 operation 快照因服务端延迟等原因超过 40 个客户端 tick 才到达。计时不在离线主菜单推进（`FastPlaceClientInput.java:1222-1242`），问题发生于重连后的等待期。
- 调用链：`onDisconnected` 设置 `reconnectBoundaryArmed`；`onClientTick` 在没有快照时递增计数；40 tick 后调用 `discardCurrentDraft`，同时清空内存草稿并删除磁盘文件。
- 影响：即使迟到快照仍代表原选择，用户的变换、部件和编辑草稿已经丢失。代码将“还没收到服务端状态”当成“应删除草稿”，没有服务端无活动选择的证据。
- 提示缺失：删除成功不显示超时或草稿丢弃提示，只有删除文件失败才提示。
- 验证级别：静态完整调用链。建议延迟重连后的 operation 快照 41 tick，再检查草稿文件和恢复内容；未做网络延迟实测。

### BUG-I（P1，类别 1/3/5）：草稿读取失败或版本不兼容被当作损坏并删除

- 位置：`src/main/java/io/github/fastformer/client/session/ClientSessionManager.java:198-213`。
- 触发：草稿文件发生临时 I/O 读取故障，或客户端回退版本后遇到不支持的草稿版本。`ClientOperationDraftCodec.decode:54-55` 对版本不支持抛出 IOException。
- 故障：统一的 `catch (IOException | RuntimeException)` 不区分原因，直接调用 `deleteDraftFile(key)`。读取权限和删除权限不同，读取失败并不保证删除也失败；版本不兼容时文件通常仍可删除。
- 影响：原本可以在故障恢复或恢复兼容版本后读取的数据被删除。即使删除失败，`loadedDrafts.add(key)` 已执行，同进程再次进入不会重试加载。
- 提示错误：统一显示“草稿已损坏”或“已损坏且无法删除”，将权限、I/O 和版本问题错误描述为内容损坏。
- 验证级别：静态完整异常链。验收应分别注入临时读取失败和不支持的版本，保留原文件并显示准确原因。

### BUG-J（P1，类别 2/5）：复制堆叠先完整展开，资源上限检查太晚

- 位置：`src/main/java/io/github/fastformer/client/operation/clipboard/OperationClipboard.java:26-30`；`WorkspacePreviewComposer.java:34-62`。
- 调用链：Ctrl+C → `FastPlaceClientInput:326` → `copySelected` → `OperationClipboard.fromWorkspace` → `WorkspacePreviewComposer.resolve`。只有后续 `OperationClipboardCodec.encode` 才检查 2,000,000 方块上限。
- 触发：选中较大堆叠后复制。输入允许每轴端点达到 128，即单侧三轴重复已有 `129^3 = 2,146,689` 个单元，即便源只有一个方块也超过剪贴板上限。
- 故障：复制使用无渲染预算门禁的 `resolve`，先调用 `repetitions(Integer.MAX_VALUE)` 创建完整坐标 List，再构造重复、平移等 Map，最后才可能拒绝编码。
- 影响：仅复制即可阻塞客户端并大量分配内存，显示层的 100,000 方块保护对此路径无效。该问题不同于 BUG-F 的原始选区扫描，发生在已有小源的大变换展开阶段。
- 验证级别：静态调用链及明确上限计算；未故意执行百万级展开，不声称已测得 OOM。验收需要在展开前完成预算判断，避免等待完整生成后才提示失败。

### 本轮覆盖与验证

- 检查了选区体积判定、工作区缩放/堆叠、复制持久化、草稿异常处理、重连确认和 tick 计时、输入外层门禁。
- 排除候选：凸包 `intersects` 使用包围盒，但普通模式切换不进入凸包，服务端 `setSelectionMode` 也拒绝该模式；未将此分支当作普通用户可达漏洞。
- 中英文语言文件的键集合一致。不能据此断言所有失败路径都有提示；BUG-H 和 BUG-I 是控制流及提示含义问题。
- 本轮 `gradlew.bat test --console=plain` 返回 BUILD SUCCESSFUL，test 为 UP-TO-DATE，未重新执行测试。上轮测试编译失败不再代表当前状态。
- 独立 `runAuditProbe` 实际执行了 BUG-G 输入并捕获异常；该探针的 Gradle 成功表示探针执行完成，不表示缩放逻辑通过。
- 新增项没有修改生产代码，也没有委派子 agent。仍需游戏内验收，不能把本记录理解为项目不存在其他 bug 的证明。

- `./gradlew.bat test --console=plain` 未通过。当前并发工作区的 `PreviewGeometrySupportTest.java` 缺少 `assertTrue` 和 `assertFalse` 符号，测试编译在执行测试前失败。
- DeepSeek harness 已完成服务器入口审计。其反证结论：分页重复请求、RETRY 跳过 journal、C2S 工作区缺少外层校验均未被当前调用链证明，未列入本记录。
