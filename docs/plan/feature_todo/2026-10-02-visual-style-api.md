# 视觉风格开放接口

目标：第三方开发者用代码写一套完整风格，而不是在固定字段里填数值。内置的经典和人文也走同一套接口实现，没有特权路径。

## 现状（GPT 已实现的部分）

- 资源包：`visual/themes/*.json` 固定字段（颜色、透明度、线宽、铅笔参数），外加覆盖 `shaders/core` 下的着色器，但必须保持原有 uniform 和顶点格式。
- 代码扩展：
  - `ConfigureVisualShaderEvent`：每个 pass 可以换一个 `ShaderInstance`。
  - `DrawVisualStrokeEvent`：取消后自己往给定的 `VertexConsumer` 写线顶点。
  - `VisualStyleContext`：提供主题、pass、dynamic、时间和视口信息。
  - `VisualStyleHooks.invalidateGeometry()`：通知预览重建几何。

不足：

1. 主题是写死的枚举 `GeometryPalette.Theme { CLASSIC, HUMANIST }`。第三方无法新增主题，设置界面也只能在这两个之间切换。
2. 风格逻辑散落在 `GeometryPalette.humanist()` 分支里，分布在 `PencilStroke`、`FastPlaceClientShaders`、`ControlPointStyle`、`GeometryRadialScreen` 等处。新风格无法改变这些行为。
3. 事件只覆盖"线段"和"换着色器"两件事。确认模型、待定预览、控制点、gizmo、遮挡线、智能选区合成和 HUD 配色都没有入口。
4. 线段事件只有 `from`、`to` 和颜色，没有语义。插件不知道这条线是候选还是确认、是选区外轮廓还是 gizmo 轴、是否处于 hover，只能对所有线做同一种处理。
5. `VertexConsumer` 的格式和 RenderType 已经由模组定好，插件无法换成自己的网格、纹理或多 pass。
6. 线段被缓存，事件只在重建时触发，插件无法逐帧做几何动画。

## 设计

### 1. `VisualStyle` 注册表，取代枚举

```java
public interface VisualStyle {
   ResourceLocation id();
   Component name();
   StylePalette palette();                 // 语义色，见第 3 节
   MarkRenderer marks();                   // 线和点
   PreviewRenderer preview();              // 方块预览
   default void onResourceReload(ResourceManager rm) { }
   default void tick(float partialTick) { }  // 逐帧状态，例如 boil 画稿号
}
```

- 用 NeoForge 自定义事件 `RegisterVisualStylesEvent` 在客户端初始化时注册，id 不能重复。
- 内置注册 `fastformer:classic` 和 `fastformer:humanist`。
- 客户端配置 `visual.theme` 改为保存 id 字符串。找不到对应 id 时回退到 classic，并记录一次警告。
- 设置界面遍历注册表循环切换，名称取 `name()`。
- `GeometryPalette.Theme` 删除。`GeometryPalette.humanist()` 的调用方全部改为向当前风格查询（第 3、4 节），删除后 `grep humanist()` 应该为空。

### 2. 语义化的绘制请求

模组只描述"画什么"，风格决定"怎么画"。

```java
public record Mark(Kind kind, State state, Role role, Vec3 from, Vec3 to, long stableId) {
   enum Kind { EDGE, POINT, AXIS, LABEL_ANCHOR }
   enum State { CANDIDATE, PENDING, CONFIRMED, DRAGGING, HOVER, CONFLICT, OCCLUDED }
   enum Role { SHAPE_OUTLINE, SELECTION, SMART_SELECTION, GEOMETRY, WORKSPACE, GIZMO, CONTROL_POINT, GUIDE }
}

public interface MarkRenderer {
   /** 收到本帧全部 mark，由风格自行建网格和提交。可以缓存，用 stableId 判断变化。 */
   void render(MarkBatch batch, StyleFrame frame);
}
```

- `StyleFrame` 包含：`PoseStack`、`MultiBufferSource`、相机、partialTick、时间、视口和 worldPerPx。
- `dynamic` 由 `State` 推导：CANDIDATE、PENDING、DRAGGING、HOVER 为动态。风格可以自行决定。
- 模组只负责收集 mark 并做增量 diff（第 5 节），不再自己调用 `PencilStroke`。
- 现有的 `PencilStroke`、`pencil_lines` 着色器和 halo 逻辑移到 `HumanistMarkRenderer`。经典主题的线移到 `ClassicMarkRenderer`。
- 风格可以使用自己的 RenderType、着色器、纹理和顶点格式，模组不做限制。

### 3. `StylePalette`：按语义取色

```java
public interface StylePalette {
   int color(ColorRole role);        // ARGB
   float alpha(Mark.State state);
}
enum ColorRole { INK, HALO, HOVER, CONFLICT, VALID, START_POINT, CONTROL_POINT, AXIS_X, AXIS_Y, AXIS_Z,
   UI_TEXT, UI_MUTED, UI_ACCENT, UI_PAPER, UI_PAPER_INK, ... }
```

- 现有 `Swatches` 的 23 个位置参数逐个映射成 `ColorRole`。UI 部分（radial 菜单、HUD）也从这里取色。
- `ControlPointStyle`、`GeometryRadialScreen` 改为调用 `palette()`。

### 4. `PreviewRenderer`：方块预览

```java
public interface PreviewRenderer {
   void renderConfirmed(PreviewBatch blocks, StyleFrame frame);
   void renderPending(PreviewBatch blocks, StyleFrame frame);
}
```

- `PreviewBatch` 提供方块位置、`BlockState`、已烘焙的模型，以及"没有模型、应回退到幽灵方块"的标记。
- 模组提供可复用的工具：`PreviewToolkit.drawModels(...)`、`PreviewToolkit.offscreen(...)`（把模型画进离屏缓冲，返回颜色和深度纹理，用于后处理）、`PreviewToolkit.ghost(...)`。
- 人文的素描后处理（`preview_material`）和经典的 85% 模型预览分别放到各自的 `PreviewRenderer` 实现里。

### 5. 帧与缓存

- 模组维护 mark 的增量集合，每帧把完整集合（不可变视图）交给 `MarkRenderer`。是否缓存网格由风格自行决定。
- 提供 `MarkMeshCache` 工具：按 stableId 和 State 缓存顶点数据，风格可以选择使用。
- 删除"缓存线段只在重建时触发事件"这个限制。逐帧动画由风格在 `tick` 和 `render` 中自行处理。

### 6. 资源包仍然有效

- 内置风格从 `assets/<ns>/visual/styles/<id>/` 读取自己的着色器和配置，由各风格自己解析，模组不规定字段。
- 资源包可以覆盖内置风格的任何资源。第三方风格可以自定义自己的资源格式。
- 删除通用的 `VisualThemes.value("xxx")` 全局键值表，参数归属到具体风格类。人文的默认参数写成 `HumanistStyle` 的一个 record，从 JSON 读取，缺省时使用默认值。

## 迁移步骤

每一步结束时都应该能编译，经典和人文的画面保持不变。

1. 引入 `VisualStyle`、`StylePalette` 和注册事件。把两个内置风格包装成实现，内部暂时仍调用旧代码。配置改为保存 id。
2. 用 `palette()` 替换所有 `humanist()` 分支和 `Swatches` 读取。删除枚举。
3. 引入 `Mark` 和 `MarkRenderer`。把各模式（快速形状、选区、智能选区、几何、工作区、gizmo、控制点、引导线）的绘制改为提交 mark。把 `PencilStroke` 移进人文实现。
4. 引入 `PreviewRenderer` 和 `PreviewToolkit`，迁移确认模型、待定呼吸和素描后处理。
5. 删除 `DrawVisualStrokeEvent`、`ConfigureVisualShaderEvent`、`VisualStyleHooks` 和 `VisualThemes`，它们被新接口取代。顺序上应先完成 boil 和素描计划，再迁移进对应的风格类，也可以在迁移时一并实现。
6. 在 `src/test` 添加一个测试风格 `TestStyle`，记录收到的 mark。用单测检查各模式提交的 Kind、State 和 Role 是否正确。在 `src/gametest` 加一个用例，切换到测试风格后检查不抛异常。

## 文档

用 `docs/visual-style-api.md` 替换 `docs/visual-resources.md`，内容包括：
- 接口签名。
- 一个最小示例风格：约 40 行，所有线画成红色，预览直接调用 `PreviewToolkit.drawModels`。
- 内置风格的资源路径。

## 验收

- `grep -rn "humanist()\|Theme.CLASSIC\|VisualThemes.value" src/main/java` 结果为空。
- 在测试中注册一个第三方风格，可以在设置界面中切换到它，并且能接管全部线、点和预览的绘制。
- 经典和人文在各模式下的画面与迁移前一致：`runClient` 后逐模式检查，日志写入 `.temp/`。
