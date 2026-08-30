# TODO

只记录未完成且可验收的工作。

- [ ] 游戏内验收倾斜圆柱 / 锥柱台体连续体积修复：`SOLID` 无内部孔洞，`HOLLOW` 与 `SOLID` 外表面一致。
- [ ] 游戏内验收锥柱交互：整体 Gizmo、轴线拖拽、短按左键回退、模式记忆和新版 HUD。
- [ ] 游戏内验收 Alt 反色准星：平滑圆环、嵌入/半格形状，以及后处理失败时的 GUI 回退。
- [ ] 等用户确定复杂形状页面的曲线、截面、扫掠和放样交互后，再替换旧复合原型。
- [ ] 自动楼梯线：根据连续路径和高差生成可预览、可放置的自动楼梯线。
- [ ] 撤回：统一世界操作历史、任务中断、断线/停服收敛和崩溃恢复日志已接入；待游戏内验收大型结构、方块实体、异步冲突、中途退出和崩溃重启恢复。
- [ ] 客户端单装兼容：在 `ClientPlacementRouter` 中实现无 FastFormer 服务端通道时的原版行为映射。工作区按服务端允许权限生成 `/setblock` 或 `/fill` 命令；确认、快速起形和快速替换复用同一入口，并明确命令长度、权限失败和部分执行的反馈。

## 空手选区（仍需游戏内验收）

- [ ] 空手选区会话完整游戏内验收：阶段沿用快速起形的点 / 线 / 面节奏，Q 退出，Enter 确认选区。
- [ ] 长方体路线游戏内验收：AABB 两点选区、左右键点编辑、中键扩展、长按推拉和 Gizmo 变换。
- [ ] 棱柱路线游戏内验收：多边面输入、闭合、控制点调整和 Gizmo 位置调整。
- [ ] 选区预览渲染：棱柱附近的边只显示相邻棱方块的边，其余区域优先显示面；具体视觉方案待验收。

## 用户关注点（仅记录）

以下内容是用户自己的规划索引，不构成实现授权。未经用户明确确认，AI 不得自行拆分需求、选择方案、改变语义或开始实现。

- 倾斜房子墙体/屋顶（用户已接近解决）。
- 道路扫掠/放样。
- 几何体大楼（多面体，用户已解决）。
- AABB 选区误伤其他建筑。

## Handoff（2026-08-30）

这些条目只描述当前工作区。它们不授权新的功能改动。

### 当前状态

- `ClientOperationWorkspace` 保存多个客户端选区。
- `ClientSelectionPart` 保存每个部件的几何、方块、选中状态和变换。
- `ClientSelectionSession` 保存选区草稿。
- 选区状态为 `POINTING`、`FOCUSED`、`UNFOCUSED` 或 `ALT_FOCUSED`。
- `interactionState()` 是控制器提供的唯一状态入口。
- 有草稿点时返回 `POINTING`。
- Alt 模式返回 `ALT_FOCUSED`。
- 有选中部件时返回 `FOCUSED`。
- 没有选中部件时返回 `UNFOCUSED`。
- 输入层统一同步 Alt 状态。清理工作区时重置 Alt 状态。
- `OperationSelectionMode` 决定新建部件是 `CUBOID` 还是 `PRISM`。
- `handleAltCreateClick(...)` 已绕过旧部件、Gizmo 和原版放置路径。
- 当前代码的长方体 Alt 手势为左键设点 1，右键或中键补点 2。
- 当前代码的棱柱 Alt 手势为左右键加点，点击起点闭合，中键无作用。
- `SmoothReticlePostEffect` 已支持 `NONE`、`EMBEDDED` 和 `HALF_GRID`。
- `SmartWoodFrame` 为每条棱计算归一化方向。单棱方块继承棱轴，角点比较关联棱的 XYZ 绝对分量。

### 接手动作

1. 读取 `docs/superpowers/plans/` 中的计划，再修改 TODO 状态。
2. 检查 Alt 按下时的准星反馈和新建状态。
3. 检查两点完成后是否直接进入 `FOCUSED`。
4. 检查 Alt 新建、取消和撤回是否只写入客户端事件栈。
5. 检查 Alt 路径是否禁止旧选区编辑、选择、原版放置和快速起形抢占。

Alt 中键规则：无 `point1`时设 `point1`，有 `point1` 时设 `point2`，两点存在时调用 `expandTo(point)`。
Alt 准星使用嵌入模式的 X 形反馈，并在会话内表示“新建选区”。

### 规则

- 新建成功后只选中新部件，其他部件降低透明度。
- 多选使用公共 Gizmo。单选使用部件 Gizmo。
- `LOCKED` 部件不能编辑。回到变换基线后恢复 `FREE`。
- 客户端预览响应当前世界。Enter 提交不重复读取并否定预览。
- 服务端只处理权限、限制、冲突检查和原子写入。

### 用户设计约束

不要用旧的 `extend`、`initialBounds` 或 `transformBaselineFrozen` 语义替代以下规则。

- `point1` 和 `point2` 只记录用户最近设置的两个点。
- `minPoint` 和 `maxPoint` 是当前 AABB 的唯一权威边界。
- 设置一个点时，更新该点，再根据两个点重算 `minPoint` 和 `maxPoint`。
- 设置一个点时，清除之前的延伸结果。
- `pushPull(face, amount)` 只修改命中的一个面。
- `pushPull` 允许正值和负值。三个轴的尺寸不能小于 1。
- `expandTo(point)` 分别检查 X、Y、Z，并一起扩大所有越界轴。
- `expandTo` 只允许扩大，不能缩小。
- `pushPull` 和 `expandTo` 必须使用两个不同的方法。
- `pushPull`、`expandTo`、`point1` 和 `point2` 只在 `FREE` 状态可用。
- `LOCKED` 状态禁止修改点、边界、推拉和扩展。
- `LOCKED` 状态仍允许移动、旋转、重复、复制、删除、多选和撤回。
- `selected` 属于工作区，不属于几何对象。允许 `FREE/LOCKED` 与 selected/unselected 组合。
- AABB 使用两点模型。棱柱使用独立的多点模型。
- 快速起形只在确认时把 `draftPoints` 转换一次。确认后的工作区不能回写草稿。
- 变换开始后，只有结果不同于基线时才保存快照并进入 `LOCKED`。
- 变换结果回到基线时，删除快照并恢复 `FREE`。
- 一致性检查比较几何、变换和源方块快照。源方块坐标不能随当前变换改写。

### 验证状态

- `SmartWoodFrameTest` 通过。
- 全量 `test` 通过。
- `deployTo233` 通过。
- 工作区包含大量未提交修改和新增文件。不要执行 `reset`、`checkout` 或清理操作。
