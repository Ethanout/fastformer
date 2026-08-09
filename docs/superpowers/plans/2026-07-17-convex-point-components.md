# 凸多面体点编辑与组件化重构实施计划

> **For agentic workers:** 按任务顺序执行；每个单元先写失败测试，再写最小实现。不得借此计划重写全部输入、渲染或生成系统。

**目标：** 为轮盘中的任意凸多面体增加已放置控制点的选择与点级 MOVE Gizmo，同时把重复的点交互、hover 反馈和 Gizmo 文本显示收敛为可组合组件，并精简相关文档。

**架构：** 服务端在凸多面体状态中保存选中点索引，客户端只做控制点/Gizmo 命中测试并发送语义交互。控制点交互使用轻量强类型组件，不引入完整 ECS；Gizmo 几何、hover 样式和文本模板分别负责单一职责。既有工作流继续拥有阶段和生成逻辑。

**技术栈：** Java 21、NeoForge 21.1.233、Minecraft 1.21.1、JUnit 5、Gradle。

## 全局约束

- 不使用 mixin；近距离输入交还原版，远距离才拦截 FastFormer。
- 不回退或清理已有脏 worktree；本计划只处理列出的文件和本轮新增文件。
- 不启动客户端；验证使用 `compileJava test`，通过后执行 `deployTo233`。
- 当前 alpha 阶段不保持旧 Java API；网络 payload 结构变化时递增协议版本。
- 点级 Gizmo 只显示 MOVE 手柄，不绘制初始位置、基准点或幽灵点。
- 命中非 Gizmo 位置时，本次点击只清除选中状态；下一次点击才执行正常加点或回退。
- Gizmo 文本只使用受控的 `GizmoTextContext` 变量，不读取操作系统环境变量。

---

### Task 1: 凸多面体点编辑状态与 MOVE Gizmo

**文件：**
- 修改：`src/main/java/io/github/fastformer/fastplace/GeometryShapeState.java`
- 修改：`src/main/java/io/github/fastformer/fastplace/GeometrySession.java`
- 修改：`src/main/java/io/github/fastformer/fastplace/GeometryWorkflowView.java`
- 修改：`src/main/java/io/github/fastformer/fastplace/ConvexPolyhedronWorkflow.java`
- 修改：`src/main/java/io/github/fastformer/fastplace/geometry/GeometryStage.java`
- 测试：`src/test/java/io/github/fastformer/fastplace/ConvexPolyhedronPointInteractionTest.java`

**接口与行为：**
- `ConvexPolyhedronGeometryState` 增加 `selectedPointIndex`，无选择为 `-1`。
- `GeometrySession` 提供 `selectedControlPoint()`、`selectControlPoint(int)`、`clearSelectedControlPoint()` 和 `moveSelectedPoint(Vec3)`。
- 选择索引必须在当前点集范围内；移动量必须有限，写入时保持 0.5 格吸附。
- 新增点、回退、取消、确认和切换 workflow 时清除选中状态。
- `ConvexPolyhedronWorkflow.stage(...)` 在有选择点时声明 `GIZMO_DRAG`，同时保留正常 `POINT_INPUT` 和 `CONFIRM` 能力。
- `previewPlan(...)` 在有选择点时生成以该点为中心、仅含 `MOVE` handles 的 `AxisGizmo`；无选择时不生成 Gizmo。

**验证：**
- 选择索引 2 后 Gizmo 中心等于第 2 点，所有手柄操作均为 `MOVE`。
- 移动第 1 点不会改变其他点。
- 清除选择后仍可正常进入点集阶段。

---

### Task 2: 控制点交互组件与客户端/服务端路由

**文件：**
- 新建：`src/main/java/io/github/fastformer/fastplace/geometry/PointerGesture.java`
- 新建：`src/main/java/io/github/fastformer/fastplace/geometry/PointerInteraction.java`
- 新建：`src/main/java/io/github/fastformer/fastplace/geometry/GeometryInteractionTarget.java`
- 新建：`src/main/java/io/github/fastformer/fastplace/geometry/GeometryInteractionHit.java`
- 新建：`src/main/java/io/github/fastformer/fastplace/geometry/GeometryRayVisibility.java`
- 新建：`src/main/java/io/github/fastformer/network/GeometryInteractionPayload.java`
- 修改：`src/main/java/io/github/fastformer/fastplace/GeometryPreviewPlan.java`
- 修改：`src/main/java/io/github/fastformer/fastplace/ConvexPolyhedronWorkflow.java`
- 修改：`src/main/java/io/github/fastformer/fastplace/WallWorkflow.java`
- 修改：`src/main/java/io/github/fastformer/fastplace/GeometryManager.java`
- 修改：`src/main/java/io/github/fastformer/fastplace/ServerInputDispatcher.java`
- 修改：`src/main/java/io/github/fastformer/network/FastPlaceNetwork.java`
- 修改：`src/main/java/io/github/fastformer/client/FastPlaceClientInput.java`
- 修改：`src/main/java/io/github/fastformer/client/FastPlaceClientPreview.java`
- 修改：`src/main/java/io/github/fastformer/network/GeometryPreviewPayload.java`
- 测试：新增 `GeometryInteractionTargetTest`，补充 `GeometryStageActionTest`

**接口与行为：**
- `PointerInteraction` 只保存手势到语义动作的绑定；支持左/右单击、双击和长按的命名槽位，但本单元只启用左右单击。
- `GeometryInteractionTarget` 保存目标类型、点索引、命中 AABB 和组件绑定；角色颜色不参与命中判断。
- `GeometryPreviewPlan` 输出交互目标列表。凸多面体已确认点挂载“选择控制点”组件；墙体闭合起点复用同一命中路径，不改变原有闭合语义。
- 客户端命中顺序为 Gizmo、交互目标、普通会话输入。命中交互目标时消费当前点击并发送 payload；选中状态下命中其他位置只发送清除选择动作。
- 服务端验证目标类型、索引、当前 workflow 和动作，不接受越界或不适用的目标。
- `GeometryPreviewPayload` 同步选中点索引；当前 payload 注册协议版本为 `37`。

**验证：**
- 控制点 AABB 命中优先于普通右键加点。
- 左右键命中已确认点都只选择，不新增/回退。
- 选中后点击非 Gizmo 位置只清除选择；下一次左右键恢复正常行为。
- 墙体闭合和双击右键行为保持现有测试结果。

---

### Task 3: Gizmo hover、上下文模板与文本块显隐

**文件：**
- 新建：`src/main/java/io/github/fastformer/fastplace/geometry/HoverFeedback.java`
- 新建：`src/main/java/io/github/fastformer/fastplace/geometry/GizmoTextContext.java`
- 新建：`src/main/java/io/github/fastformer/fastplace/geometry/GizmoTextTemplate.java`
- 新建：`src/main/java/io/github/fastformer/fastplace/geometry/GizmoTextComponent.java`
- 新建：`src/main/java/io/github/fastformer/fastplace/geometry/GeometryTextBlock.java`
- 新建：`src/main/java/io/github/fastformer/fastplace/geometry/ControlPointState.java`
- 新建：`src/main/java/io/github/fastformer/fastplace/geometry/ControlPointFeedback.java`
- 修改：`src/main/java/io/github/fastformer/fastplace/geometry/AxisGizmo.java`
- 修改：`src/main/java/io/github/fastformer/fastplace/geometry/GeometryPreviewPlan.java`
- 修改：`src/main/java/io/github/fastformer/client/FastPlaceClientPreview.java`
- 修改：`src/main/java/io/github/fastformer/fastplace/ConvexPolyhedronWorkflow.java`
- 测试：`src/test/java/io/github/fastformer/fastplace/geometry/GizmoTextTemplateTest.java`

**接口与行为：**
- `HoverFeedback` 只描述 normal/hover/active 三种显示状态；active 沿用 hover 颜色。点级 Gizmo 使用全局 `GIZMO_HOVER_COLOR`，初值为高亮白色。
- `GizmoTextContext` 提供 `axis`、`operation`、`base`、`delta`、`current`、`direction` 等有限变量。
- `GizmoTextTemplate` 使用 `${name}` 替换；未知变量保留原文，便于发现文案配置错误。
- `GizmoTextComponent` 独立提供 hover/drag 文本模板和显隐开关；Gizmo 几何不负责文本内容。
- `GeometryTextBlock` 为阶段、模式、提示等块提供独立 `visible` 开关，渲染器按稳定位置消费，不再由 workflow 通过空文本隐式关闭。
- 点级 Gizmo 默认 hover 模板为 `${axis} ${operation}`，拖动模板为 `${axis} ${base}${delta}`；不输出初始位置块。

**验证：**
- 模板渲染 `X 位移`、`X 0+3` 和未知变量保留行为。
- hover 与 active 使用同一高亮颜色，松开后恢复轴色。
- 隐藏任意一个文本块不会影响其他块或阶段状态。

---

### Task 4: 原则、文档、记忆与最终验证

**文件：**
- 修改：`SKILL.md`
- 修改：`docs/design_principles.md`
- 修改：`docs/session_design.md`
- 修改：`TODO.md`
- 修改：`issues.md`
- 修改：`docs/worktree_inventory.md`
- 新建：`C:/Users/26297/.codex/memories/extensions/ad_hoc/notes/` 下本次记忆更新文件
- 保留：`docs/input_compatibility.md` 只记录输入兼容边界
- 保留：`docs/generator_baselines.md` 只记录生成器验证边界

**文档规则：**
- 删除旧的魔法式架构描述，改用“核心概念/组件”。
- 在设计原则中加入用户确认的“打断原则”“不要重复原则”“组件原则和组件互斥原则”。
- `docs/session_design.md` 只保留概念定义和状态树；组件原则、生成原则和输入兼容不在其中重复展开。
- `TODO.md` 只保留未完成且可验收事项；`issues.md` 只保留未决事项；不把已完成的点编辑功能留在 TODO。
- 记忆只通过新增 ad-hoc note 更新，不直接修改 `MEMORY.md`。

**验证：**

```powershell
.\gradlew.bat --no-daemon compileJava test --console=plain
.\gradlew.bat --no-daemon deployTo233 --console=plain
git diff --check
```

不启动客户端；最终汇报需明确说明实际游戏内交互仍待验收。
