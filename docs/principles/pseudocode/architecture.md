# 架构伪代码

本文描述当前代码的对象边界和主要流程。顶层交互语义以同目录的 `interaction_spec.md` 为准。本文不是新的实现计划。

## 玩家会话树

```text
ClientSessionManager
  playerSessions: UUID -> ClientPlayerSession
  current: ClientPlayerSession

ClientPlayerSession
  root: ClientSessionNode("player")
    operationWorkspace: ClientOperationWorkspace
    selectionSession: ClientSelectionSession
    empty: ClientSessionNode
    quick_shape: ClientSessionNode
    special_shape: ClientSessionNode
    special_item: ClientSessionNode
  currentNode

refresh(snapshot):
  next = first session definition that matches snapshot
  currentNode.exit()
  currentNode = root.child(next.state)
  currentNode.enter()

signal(event):
  currentNode.signalRegistry.emit(event)

inspect():
  return readOnlyPath(root, currentNode)
```

顶层状态互斥。会话对象和工作区由玩家 UUID 持有。玩家实例重建、换维度或重新连接时不清空对象。边界期间先门控 `inactive` 快照，再等待新的活动权威快照。

## 客户端操作流程

```text
input event
  -> semantic SessionSignal
  -> current ClientSessionNode registry
  -> selection or workspace object
  -> local preview and event history

ready preview
  -> ClientOperationWorkspace.createPart()
  -> sourceSnapshot + transform baseline
  -> local MOVE / STACK / ROTATE / SCALE
  -> ShapePlacementPayload on Enter
```

工作区没有固定十部件限制。当前输入层没有 Ctrl+数字槽位。客户端工作区选区只支持 `CUBOID` 和 `PRISM`。凸包工作区不属于当前功能，但旧服务端 `CONVEX_HULL` 路径仍保留兼容代码。

## 选区数据

```text
point1, point2 = latest user points
minPoint, maxPoint = authoritative AABB bounds

setPoint(point):
  update one point
  recompute minPoint and maxPoint
  clear old expansion result

pushPull(face, amount):
  change one face
  keep every axis at least one block

expandTo(point):
  enlarge every axis whose point is outside the current bounds
  never shrink
```

`FREE` 允许点编辑、推拉和扩展。`LOCKED` 禁止这些操作，但允许移动、旋转、重复、复制、删除、多选和撤回。选中状态属于工作区，不属于几何对象。

## 服务端提交边界

```text
client complete preview
  -> resolved ShapePlacementPayload
  -> permission, dimension, source and target validation
  -> block entity and memory checks
  -> recovery journal
  -> server tick world task
```

服务端不重新生成客户端已经解析的形状。验证失败时不写入。任务保存 owner UUID、原始维度、冻结参数、阶段、租约和 journal。玩家死亡、换维度或断线不会停止已开始的世界任务。

## 世界任务和恢复

- recovery/history、placement、MOVE/STACK 按固定顺序推进。
- 每 tick 最多处理 4096 格，通常约 3 ms 后让出主线程，并至少推进 1 格。
- 写入前匹配 expected 状态。外部修改时停止，并只回滚仍匹配的已写位置。
- 写第一格前创建 write-ahead journal。日志写盘完成前不修改世界。
- 未提交 `.dat` 按倒序恢复，已提交 `.done` 按正序补全。摘要、维度、坐标顺序和目标状态必须匹配。
- 日志损坏或状态无法识别时保留 journal，并禁止新的世界写入。

## 生成不变量

- 每种形状使用独立生成器，只共享同义的离散线、数学、数值和限额工具。
- `SOLID` 包含连续目标和 owned 几何约束。`HOLLOW` 是最终 `SOLID` 外露边界与 owned outline 的并集。
- 离散直线按绝对位移动态排序轴，只允许 A、AB、ABC 步。真实 owned edge 点不可删除或用二维边界替代。
- 封闭凸体先采样连续 half-space，再合并 owned 面和边。倾斜平行六面体只使用已确认的底面、顶面和四条顶点挤出线。
- `maxBlocks` 是硬上限。渐进预览不能把 worker prefix 当作完整形状。世界写入使用 `maxPlacement + 1` 哨兵拒绝超限结果。
