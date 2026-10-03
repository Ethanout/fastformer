# 视觉资源包接口

设计依据：[视觉规范](principles/visual-style.md)。客户端配置 `visual.theme` 选择经典或人文主题。

## 主题 JSON

资源包覆盖以下路径：

- `assets/fastformer/visual/themes/classic.json`
- `assets/fastformer/visual/themes/humanist.json`

格式版本为 `1`。`colors` 使用 `#RRGGBB`。`values` 使用有限的非负数，透明度范围为 0～1。
字段可以省略，省略的字段使用内置默认值。格式错误时，日志记录错误，该主题回退到默认值。

```json
{
  "version": 1,
  "colors": { "ink": "#FFFFFF", "start": "#268CFF", "control": "#FFC71F", "hover": "#1FFF4D" },
  "values": { "candidate_alpha": 0.8, "confirmed_alpha": 0.85, "pending_min_alpha": 0.05, "pending_max_alpha": 0.30 }
}
```

完整字段见模组自带的两个主题文件。线宽使用 `line_width`、`gizmo_width`、`gizmo_hover_width`。
铅笔使用 `pencil_grain`。排线使用 `hatch_strength`、`hatch_scale`。
`pencil_long_min/max` 默认 0.15/0.35，`pencil_short_min/max` 默认 0.02/0.15，分别控制长短端的格数范围。每条边一长一短，短线同比缩短。这四项取代早期的 `pencil_overshoot` 和 `pencil_overshoot_variation`。
两套主题共用遮挡字段。`occluded_line_opacity` 相对于同一条未遮挡线的透明度，范围为 0～1。经典默认 0，即隐藏。人文默认 0.3，即淡显。
`occluded_dash_length/gap` 控制遮挡虚线的线段／间隔格数，人文默认 0.25/0.15，经典默认 0/0；线段长度为 0 时使用实线。虚线相位固定在空间中，不随动画流动。
`pencil_near_width` 人文主题默认 1.35，控制彩铅基础线宽倍率；之后再乘以距离曲线倍率。经典主题不受影响。

主题 JSON 顶层的 `curves` 支持两条距离曲线：

```json
"curves": {
  "line_width_by_distance": [[0, 1], [5, 1], [20, 0.65], [64, 0.35]],
  "jitter_by_distance": [[0, 1], [5, 1], [20, 0.5], [64, 0.15]]
}
```

节点为 `[距眼睛的格数, 倍率]`。使用保持单调性的三次 Hermite 插值，经过全部节点且不越过区间端值。两端之外保持端点倍率，默认 5 格内保持倍率 1。支持 1～8 个节点，距离严格递增，倍率为 0～32；缺少曲线时倍率为 1。切线在资源加载时预计算。

线宽曲线同时影响普通线、gizmo 和 halo；抖动曲线控制动态角点偏移、弯曲动画和 overshoot 长度变化。静态 overshoot 保留。

开发客户端修改源码资源后，先运行 `./gradlew.bat processResources`，再按 F3＋T；客户端读取的是 `build/resources/main`。正式资源包直接修改后按 F3＋T。
线宽使用逻辑 GUI 像素，绘制时乘以 Minecraft 当前界面缩放。经典与人文主线默认均为 1.5。在界面缩放为 2 时，对应 3 个帧缓冲像素。人文 gizmo 普通线默认 1.44375，悬停线默认 1.70625。主线、操作轴、描边和笔触横向颗粒共同缩放。
人文笔触保留越过交点的延伸，不绘制角落短辅助线。
`boil_sheets` 默认 3，最少 2。`boil_fps` 默认 8。每次切换不重复上一张，同一渲染帧共用画稿。
`boil_jitter_px` 默认 0.1875，`boil_bow_px` 默认 0.08，单位为逻辑 GUI 像素。角点共享偏移，线条最多只有一个微弯。遮挡虚线沿原始线段固定，不随抖动重新分段。
`boil_overshoot_variation` 默认 0.08，控制动态延伸的长度变化。静态部分使用画稿 0，角点和延伸均不抖动。
`pencil_wobble` 和 `pencil_far_boil` 已移除。
`halo_alpha` 和 `halo_extra_width` 控制描边。描边沿笔画横向按线宽比例淡出，不落硬边。修改后按 F3＋T 重载资源，预览缓存会随之更新。

## 着色器

资源包可以覆盖 `assets/fastformer/shaders/core/` 下的 `.json`、`.vsh` 和 `.fsh`：

- `pencil_lines`：连续笔触与颗粒。
- `shaders/include/pencil_stroke.glsl`、`pencil_pigment.glsl`：普通线与候选线共用的线宽、粗糙边缘和颜料颗粒。资源包可同时覆盖这两个文件。
- `preview_material`：方块模型的局部后处理。输入为预览颜色、深度、逆视图投影矩阵和相机位置。
- `preview_model`：预览模型的材质采样。透明像素跳过深度写入，草皮等叠层使用透明混合。`preview_material` 接收预乘颜色，合成前还原颜色。
- `halo_lines`：主线的对比描边。
- `pending_dashed_lines`、`halo_dashed_lines`：保留现有资源名，内容已改为连续候选线，不再按虚线区分阶段。
- `smart_selection_composite`：智能选区穿透编辑的合成。

覆盖着色器时保留声明的顶点格式、采样器和 uniform 接口。
`preview_material` 通过世界位置与表面法线固定排线。`BoilFrame` 是画稿编号，动态预览为 1～N，静态预览为零。

## 开发者接口

资源包可以直接覆盖 GLSL。Java 扩展由客户端附属模组实现。当前接口覆盖线段几何、线条／描边着色器、模型采样和预览材质着色器。

包名：`io.github.fastformer.client.render.style`。以下事件发布在 `NeoForge.EVENT_BUS`：

- `ConfigureVisualShaderEvent`：设置自定义 uniform，或通过 `setShader` 替换着色器。替换实例通过 NeoForge 的 `RegisterShadersEvent` 注册，并保持该通道的顶点格式及必需接口。
- `DrawVisualStrokeEvent`：通过 `vertices()` 写入替代线段，随后调用 `setCanceled(true)`。端点属于调用方的局部坐标，变换通过 `pose()` 提供。缓存线段只在重建时触发该事件。

`context()` 提供主题、绘制通道、动态标记、时间、界面缩放和帧缓冲尺寸。几何规则变更后调用 `VisualStyleHooks.invalidateGeometry()`。仅改变着色器动画时，无需重建几何。

自定义着色器可以在 JSON 和 GLSL 中声明以下可选 uniform：

- `FFTime`（float）：运行秒数，每 3600 秒循环。
- `FFDynamic`（float）：动态通道为 1，静态为 0。静态笔触动画须由开发者据此关闭。
- `FFOccluded`（float）：遮挡线通道为 1，其余为 0。
- `FFGuiScale`（float）：逻辑 GUI 像素到帧缓冲像素的倍率。
- `FFViewport`（vec2）：帧缓冲宽度和高度。

例如，在客户端初始化时注册监听器，为自定义着色器设置颗粒强度：

```java
NeoForge.EVENT_BUS.addListener((ConfigureVisualShaderEvent event) -> {
    if (event.context().pass() == VisualStyleContext.Pass.LINE) {
        event.shader().safeGetUniform("CustomGrain").set(0.7F);
    }
});
```

`CustomGrain` 须由自定义着色器声明并使用。接口不要求开发者使用内置铅笔算法。

## 检查范围

切换主题或重载资源后，检查起形首点、候选面、确认模型、长方体选区、智能选区、几何模式和工作区。
确认后保持静止，再移动相机，检查静态笔触和排线是否稳定。
