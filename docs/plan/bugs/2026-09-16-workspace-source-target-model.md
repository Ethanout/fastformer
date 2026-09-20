# 2026-09-16 工作区源引用与目标格网模型

状态：架构决策。未实施。等待实施放行。本轮不改生产、测试或预算代理文件。未运行 Gradle。

实施闸门：等预算代理完成 `WorkspacePreviewComposer`、`ClientOperationController`、`OperationWorkspaceValidator` 后再整体落地。不得在那些接口未定时并行改同一路径，避免两套模型互相覆盖。

`2026-09-16-group-repeat.md` 仍是 G1 证据。其中的固定两层与 repeat-layer 栈只评估过，不是当前推荐。当前推荐是本文的 A + GesturePlan。

## 决定

采用模型 A：源引用与目标格网分离。每次完成的新分组或变换，把当前预览烘焙进目标格网。WORLD 源键永不改写。

不采用模型 B 作为主模型：不保存无界操作序列，也不用“顶层 stride 相同”判断同一分组。

手势作用域单独用 `GesturePlan` 固定。这关闭 G2。它不是变换算术的一部分。

该组合关闭 G1、G2、G3，并拆开“源清除键”与“目标写入格”。它不填回缺失的箱子物品。BUG-AA 在源快照身份协议落地前仍未解决。

保存快照提交契约保持。校验阶段不新增 live 源比较。`doesNotReadLiveWorldWhenSubmittingSavedWorldSnapshot` 继续成立，直到以后用显式快照身份替换该测试。写前 `captureExpected` 仍是任务内并发保护，不是编辑起点检查。

## 为何不选 B 为主模型

当前管线是 scale → 一层 repeat → rotate → translate。repeat 在旋转前。`WorkspaceSelectionBounds.wholeBox` 是旋转后的世界轴包络。该包络不能直接当作旋转前 stride。非 90 度后再做世界轴重复，B 需要每条操作带 LOCAL/WORLD 帧。

B 仍用同一份 `blocks` 既当源清除键又当展开输入。ARCH-10 / BUG-AA 要求拆开预览、源引用、完整内容和事务前像。操作序列不提供该拆分。箱子物品仍走客户端空标签。见 `2026-09-15-architecture-data.md` ARCH-10。

B 若用 stride 合并相邻重复，会把不同部件集当成同一分组。G1 第三次 A+C 会再次移动副本。分组身份必须是目标部件集加本次包络，不是一个轴上的整数。

B 的预算是每层 `cellCount` 的乘积，再加旋转桥接。`WorkspacePreviewComposer.resolveCore` 今天对重复列表传入 `Integer.MAX_VALUE`。无界序列把这条路径留着。见 `2026-09-16-geometry-budget-architecture.md`。

因此 B 的帧概念吸收进 A：烘焙之后，目标格网已经在世界格上。下一次世界轴重复只复制当前包络。不需要第二套展开引擎。

## 为何 A 关闭四个缺口

G1：单部件堆叠后烘焙。A 的目标格是 0..9，B 的目标格是 10..13。公共堆叠复制当前整体 0..13，步距 14。已放置副本不重排。这是 `placedCopiesSurviveACommonSecondStackAfterPerPartFirstStacks` 的目标。

第三次不同分组：选择 A 与 C。复制的是烘焙后的 A 并上 C，不是旧的 A+B 外层。不改已有层 stride，因为没有层。

同一组第二次堆叠（证据 A）：第一次烘焙后整体是 0..23。第二次复制该整体，步距 24。旧副本不动。不需要“同一分组”合并规则。

非直角旋转后的世界轴重复：旋转手势结束时烘焙。目标格已是离散世界体素。下一次 STACK 沿世界轴复制当前世界包络。不把旋转后 AABB 写回旋转前 stride。

G2 / BUG-AT：`beginWorkspaceGizmoDrag` 今天把 `selectedParts` 整表当作 baseline，且 `updateTransformGesture` 没有部件 ID。单部件手柄因此改写组内每个部件。`GesturePlan` 在手势开始时固定 `targetIds`、作用模式、轴空间和量化单位。控制器只写这些 ID。选择集只负责显示。见 `2026-09-15-selection-capabilities.md` BUG-AT。

G3：烘焙后的目标包络始终带非零世界步距。不再用“stride 为 0 时选择框范围”和“占用宽度”两套回退。

源键与目标格混用：源引用保存捕获时的 WORLD 键。目标格网保存客户端已解析的放置结果。校验器从源引用取清除坐标，从目标格网取写入。禁止把展开结果写回源键。这一拆分不等于 BUG-AA 已修。客户端预览快照仍可以是空箱子 NBT。完整物品要等身份协议与服务端源填充。

## 部件形状

每个 `ClientSelectionPart` 分成三块。旧的单张 `blocks` 加单张 `WorkspaceTransform` 退出主路径。

1. `SourceRef`：`source`（WORLD 或 CLIPBOARD）、捕获坐标、预览快照、快照身份（存档、维度、内容版本、完整性：无实体 / 完整 / 仅预览 / 读取失败）。WORLD 清除只读这里。
2. `TargetLattice`：世界坐标下的已烘焙格、含空气的选择包络、内容完整性标记。预览、Gizmo、下一次 STACK 只读这里。
3. `OpenGesture`：最多一个未烘焙操作（MOVE、ROTATE、SCALE-repeat、棱柱 SCALE）。拖动期间叠在目标格网上。`finishEdit` 烘焙后清空。`cancelEdit` 丢弃它。

`GesturePlan` 不属于部件。它属于一次编辑会话：`targetIds`、`Mode {PART, GROUP}`、轴、量化包络、编辑 token。

LOCKED 只禁止源 AABB 面编辑与源点编辑。它不禁止选择、MOVE、Gizmo、STACK、复制、删除。选中状态属于工作区，不属于几何。见 `docs/principles/pseudocode/architecture.md` 与 BUG-07 / R4。

CLIPBOARD 部件没有 WORLD 清除。源引用可为空键。目标格网就是剪贴板内容。`WorkspaceContentPreparer.clipboardParts` 今天已经调用 `resolve()`。那条路径保持“导出即烘焙”。

## 写入规则

1. 拖动只改 `OpenGesture`。不改 `SourceRef`。不改已烘焙的 `TargetLattice`。
2. 一次手势 = 一次 `beginEdit` 到一次 `finishEdit`。烘焙只在 `finishEdit` 发生一次。
3. 烘焙源是 `beginEdit` 时的 `TargetLattice`，不是上一帧预览。最终 `OpenGesture` 作用到该基线上，得到新的 `TargetLattice`，然后清空 `OpenGesture`。
4. 禁止每个鼠标步烘焙。非 90 度旋转的离散重采样只做这一次。多次最近邻采样会累计漂移。
5. 拖动中的预览可以显示 `TargetLattice ⊕ OpenGesture`。那些中间帧不写入格网，不进撤销。
6. STACK 的一步复制当前目标包络（含空气）沿手势轴平移 `extent(wholeBox)`。量化与写入共用该 extent。
7. 新分组不查找“相同 stride”。每一次完成的 STACK 都复制当时的目标包络。
8. 单部件模式只更新 `targetIds` 里的部件。其他已选部件保持自己的目标格网。
9. 源几何面调整仍只比较 `SourceRef` 与客户端重捕获。该检查不用于 STACK，也不用于提交。

## 提交契约（不新增 live 源比较）

校验与放置继续分两个时间边界：

| 边界 | 现有代码 | 本模型 |
|---|---|---|
| 编辑起点源检查 | 客户端 `sourceState` 比较选区与 `part.blocks()` | 仍只在客户端。比较选区与 `SourceRef` 预览。不用于提交。 |
| 校验 | `LiveBlockLookup` 非空检查后不读。测试禁止读取。 | 保持。清除 = `SourceRef` 键。写入 = 目标格网（加未烘焙手势时先烘焙或在提交前拒绝未结束编辑）。 |
| 写入期保护 | `captureExpected` 记录任务开始后的实时前像。`write` 要求匹配。 | 保持。这是任务内并发保护。 |

WORLD 移动的方块实体完整内容仍按 ARCH-10 后期做：服务端用源快照身份填充目标映射。那是新契约，必须声明读取时点。它不是“提交时把 live 世界与编辑起点比较并拒绝”。本决策不打开校验期 live 比较。

客户端目标格网仍是预览数据。提示不得把它说成完整服务端快照。见 `2026-09-16-source-change-adjust-feedback.md`。

## 不变量

1. WORLD `SourceRef` 键在部件生命周期内只在捕获或显式重新框选时变化。STACK、MOVE、ROTATE、SCALE-repeat 不得写入这些键。

2. 校验器清除集合等于 WORLD `SourceRef` 键。写入集合等于目标格网展开结果。两集合身份不同，除非该部件从未变换且未重复。

3. 烘焙不得把目标格写进 `SourceRef`。

4. 手势作用域等于 `GesturePlan.targetIds`。`selectedIds` 不能代替它。未选中的 LOCKED 部件仍必须可被边框、标签或手柄点回。点回只改选择集，不改 `SourceRef`，不解锁源面。

5. 一个编辑会话最多一个 `OpenGesture`。未结束的编辑不得提交。

6. 未知草稿版本与未知计划版本必须拒绝。不得静默丢掉目标格网或源引用。

7. 预算用目标格网的格数加上 OpenGesture 的投影上界。禁止为未烘焙重复分配 `Integer.MAX_VALUE` 长度的列表。

8. 工作区撤销恢复 `beginEdit` 快照：`SourceRef`、手势前的 `TargetLattice`、空 `OpenGesture`。不重放增量拖动，也不反向重采样。

9. 顶层 stride 相等不是分组相等。本模型不比较 stride 来合并手势。

10. 两张 map 指向同一实例时，共享源只计一次。变换后两图同时存活时，两笔都计入预算。

## 预算

渲染上限与提交上限仍然分开。不得用 100,000 套到服务端。见 `2026-09-16-geometry-budget-architecture.md`。

双映射计费：

1. 拆开后 `SourceRef` 与 `TargetLattice` 可以指向同一不可变实例。工作区历史已按身份为共享 payload 计费。同一实例只算一次。见 `ClientOperationWorkspace.Snapshot.sharedPayloads`。
2. 变换完成并烘焙后，目标格网是新 map。源引用仍持有捕获 map。两图同时存活，必须两笔都计入。不得因为“曾经共享”而只算源。
3. 提交计划里，清除键来自源引用，写入来自目标格网。两份独立 map 不得合成一份配额。

STACK 成本从“源格 × 各层格数乘积”改成“当前目标格数 × 本次复制次数”。数字在手势结束烘焙时已经是展开后的格。`WorkspaceGeometryCost` 必须改为读目标格网，而不是假设单层 `repeats.cellCount()`。该改动留给预算所有者。本文不改那些文件。

交互路径（Ctrl+C、手势求中心）仍需要独立上限。烘焙后复制包络不得走无守卫的 `resolve(part)`。

## BUG-AA 仍未解决

`SourceRef` 拆开本身不补齐缺失的箱子 NBT。ARCH-10 / BUG-AA 的根因是客户端区块同步只有空 `getUpdateTag`，捕获把它当成完整内容。双 map 只分开清除键与目标格。目标格网仍携带这份预览数据。

不得宣称“双 map 已消除 BUG-AA”。完整性标记可以写“仅预览”。它不能填回服务端物品。完整内容要等源快照身份与声明的读取时点。那是后期契约，不是本决策的完成条件。

## 撤销、草稿、协议

工作区历史已经按不可变部件快照计费。`beginEdit` 保存手势前快照。`finishEdit` 烘焙一次后记录该基线为撤销节点。不要为 STACK 另做命令日志。不要为每个拖动步另做快照。

草稿 `ClientOperationDraft.CURRENT_VERSION` 现为 1。计划 `OperationWorkspacePlanCodec.VERSION` 现为 1。拆开源键与目标格后必须升版本。

旧读者：

- 草稿未知版本抛 `VersionMismatchException`。
- 计划未知版本抛 `IOException("Unsupported workspace plan version")`。

v1 读入：`TargetLattice = blocks`，`SourceRef` 键 = 同一 `blocks` 键，`OpenGesture` 为空，transform 若非 identity 则视为未烘焙手势。行为与今天一致。UI 写出“目标格 ≠ 源键”之前必须先升计划版本。旧服务端拒绝新版本。

## 迁移步骤

现在做（仅在实施放行之后，且预算代理已完成 composer/controller/validator）：

1. 记录拆分。`ClientSelectionPart` 增加 `SourceRef` 与 `TargetLattice`，两者先指向同一张不可变 map。共享实例只计一次预算。`WorkspaceTransform` 仍存在，当作 `OpenGesture`。现有测试保持绿。不改预算文件。
2. 提交准备改读两张 map：清除用 `SourceRef`，计划里的放置内容用目标格网。校验器语义不变。live 参数仍不读。
3. 落地 `GesturePlan`。`FastPlaceClientInput.beginWorkspaceGizmoDrag` 写入目标 ID 与模式。`updateTransformGesture` 停止用整份选择集猜测。这关闭 G2。取消路径继续用现有编辑 token。
4. `finishEdit` 相对 `beginEdit` 基线烘焙一次 STACK、ROTATE、非棱柱 SCALE。`OpenGesture` 清空。拖动增量不烘焙。G1 用例去掉 `@Disabled`。增加 A，然后 A+B，然后 A+C。增加旋转后世界轴 STACK。增加非 90 度旋转一次完成后再 STACK，断言无累计漂移。
5. 升草稿与计划版本。未知版本拒绝。v1 仍按步骤 1 读取。

以后做：

1. 删除把 stride 改写成当前整体范围的写入路径。G3 的双回退只保留给 v1 旧草稿，新手势不再写出 0 stride。
2. ARCH-10 方块实体映射与完整性枚举。替换“不读取 live”测试为“读取已声明的快照身份”。不把校验改成编辑起点 live 比较。这一步才处理 BUG-AA。双 map 落地后 BUG-AA 仍在。

## 文件范围（实施时，本轮不改）

新模型所有者：

- `ClientSelectionPart.java`、`SelectionBaseline.java`
- `ClientOperationWorkspace.java`（快照已按部件复制，字段进入快照即可）
- `ClientOperationController.java`
- `FastPlaceClientInput.java`（仅 GesturePlan）
- `WorkspaceContentPreparer.java`（读两张 map，不把目标写回源）
- `ClientOperationDraft.java`、`ClientOperationDraftCodec.java`
- `OperationWorkspacePlan.java`、`OperationWorkspacePlanCodec.java`

以后由预算与校验所有者改，本轮不碰：

- `WorkspaceGeometryCost.java`、`WorkspaceGeometryBudget.java`
- `OperationWorkspaceValidator.java`
- `WorkspacePreviewComposer.java` 的无守卫展开入口

测试（落地后）：启用 G1 用例。再加 A，然后 A+B，然后 A+C。再加非 90 度旋转后沿世界 X 重复。同一次旋转手势内多次拖动不得累计漂移。单部件手柄只改命中部件。草稿与计划拒绝未知版本。校验器仍不在保存快照提交时读 live。共享实例只计一次，烘焙后两图都计。LOCKED 重选用例见下一节。

## LOCKED 能力边界（R4 / BUG-AP）

`LOCKED` 的产品含义：禁止源 AABB 面推拉和源点编辑。允许选择、MOVE、ROTATE、STACK、复制、删除、多选、撤回。见 `architecture.md`：“选中状态属于工作区，不属于几何对象。”

当前代码冲突：

- `ClientSelectionPart.withTransform` 在首次有效变换后设 `LOCKED`。用途是保留变换基线，不是删除部件。
- `WorkspacePartInteractionCapabilities.canSelect` 不看 `LOCKED`。`canEditSource` 才要求 `FREE`。
- `WorkspaceInteractionResolver.resolvePart` 已走 `canSelect`。
- `resolveFace` 已走 `canEditSource`。LOCKED 面跳过是对的。
- `resolveGizmo` 第 84 至 86 行：`LOCKED` 且未选中则 `continue`。变换后的 A 被 `selectOnly(B)` 丢掉后，手柄不能点回 A。这是 R4。

能力表：

| 能力 | FREE | LOCKED 未选中 | LOCKED 已选中 |
|---|---|---|---|
| 边框 / 标签点选 | 是 | 是 | 是 |
| 单部件 MOVE / ROTATE / STACK 手柄 | 是 | 是 | 是 |
| 源 AABB 面推拉 | 是 | 否 | 否 |
| 源点编辑 | 是 | 否 | 否 |
| GesturePlan 目标 | 可选 | 点回后才可进 `targetIds` | 可进 `targetIds` |

规则：

1. 选择入口不得因 `LOCKED` 关闭。未选中的变换部件必须能从边框、标签或手柄重新成为选择目标。
2. 点回只改 `selectedIds` / `activeId`。不改 `SourceRef`。不把 `LOCKED` 改回 `FREE`。不重采样源。
3. 源面仍只走 `canEditSource`。LOCKED 面不得高亮为可拖。
4. 已选中的 LOCKED 部件显示 MOVE / ROTATE / STACK。`GesturePlan` 只写这些 ID。这与 G2 同一计划。
5. 本轮不改 `WorkspaceInteractionResolver`。预算代理占用该文件。验收用例先写入本文。

验收用例（落地 resolver 时，本轮不加测试代码）：

1. 移动 A，再点 B。A 为 LOCKED 且未选中。点 A 的边框。A 成为选择目标。几何不变。面仍不可推。
2. 同上，点 A 的标签。结果相同。
3. 同上，点 A 的 MOVE 手柄。A 被选中，随后 `GesturePlan` 目标只有 A。B 不移动。
4. 新建部件 C 后点回 A。A 可选。C 的选择由交互规则单独决定。
5. 两个 LOCKED 部件。Ctrl 点 A 再点 B。两者都在选择集。公共 Gizmo 出现。
6. 提交等待期间不得开始新选择手势。现有锁定会话规则保持。
7. `WorkspacePartInteractionCapabilities.canSelect` 对 LOCKED 为真。`canEditSource` 为假。`lockedPartRemainsSelectableFromItsSelectionBounds` 已覆盖能力层。命中层仍缺未选中 Gizmo 用例。

## 明确拒绝

- 固定两层 `groupRepeats` / `groupStride`。
- 无界 repeat-layer 栈作为主模型。
- 用顶层 stride 判断同一分组。
- 把展开结果写进 WORLD `blocks` 而不拆源引用。
- 为关闭 G1 而在校验期恢复 live 源比较。
- 把渲染预算与提交预算合成一个数字。
- 每个鼠标步烘焙，或把中间预览帧当作撤销基线。
- 宣称双 map 已消除 BUG-AA。
- 用 `LOCKED` 同时关掉选择与 Gizmo。锁定只禁源 AABB 面编辑。

## 未实施声明

本文是决策，等待实施放行。工作区仍是单张 `blocks` 加单层 `(repeats, repeatStride)`。G1 用例仍是 `@Disabled`。G2 仍把整份选择集当作 baseline。BUG-AA 的服务端源填充未做。不得把本文写成已完成。
