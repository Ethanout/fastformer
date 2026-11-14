# 控制点数据与客户端主题归属

- `ControlPoint`、`ControlPointShape`、`ControlPointState`、`ControlPointFeedback` 迁至 `common.geometry.controlpoint`，保留不可变语义；悬停文本只依赖通用 `Component`。角色仍复用现有公共 `ControlPointRole`。
- `GeometryPalette`、`VisualTheme`、`DistanceCurve` 迁至 `client.preview.style`。可变主题选择、资源安装和 revision 仍属于客户端，没有移到公共层。
- 全部 source set 更新明确类型路径，不提供旧包兼容类。
- `AxisGizmo`、`HoverFeedback`、标签模板迁至 `common.geometry.gizmo`，保留共享几何、命中及手柄状态。反馈采用轴色、点悬停和显式 RGB 三种不可变语义，具体颜色与提亮只由客户端 `GizmoColors` 解析。显式 RGB 的 active 仍使用 hover 色，主题切换无需重建共享手柄。
- 共享计划、方块层、状态栏文本迁至 `common.geometry.preview`；目标、手势绑定和命中迁至 `common.geometry.interaction`。`GeometryStatusText` 只组合传入文本和显式样式。共享计划产生无主题状态栏并记录内容来源，客户端在 `GeometryPointer.presentedPlan()` 调用 `client.geometry.presentation` 应用主题，自定义状态栏内容、位置及独立可见性保持不变。方块层缓存仍使用原始计划。
- `ControlPointStyle` 迁至客户端叶包 `preview.style`，使方块模型不再依赖含渲染器的 `controlpoint` 包。轴与射线投影抽至 `common.math.RayAxisProjection`，删除 `OperationGeometry` 原实现并复用同一算法。
- 每个 `GeometryPointer` 独占主题文本缓存，以原始计划 identity 和调色板 revision 为键；清理或计划失效时一起释放。gizmo 的缩放、悬停与捕获状态仍逐帧呈现，不进入文本缓存。
- 新增 `GizmoColorsTest`、`GeometryTextPresentationTest`，覆盖主题切换、显式 RGB、active/hover 等价、无主题共享计划和自定义文本保留；现有凸多面体及状态栏测试同步到语义接口。

静态扫描：新公共 gizmo/preview/interaction 闭包无客户端或旧层依赖；原类型路径、旧 `AxisGizmo.axisColor`、`HoverFeedback.color` 调用均为零。定向 `git diff --check` 退出码为 0。本批未运行 Gradle，由 root 统一编译和回归。
