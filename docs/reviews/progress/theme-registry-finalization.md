# 主题注册收口（2026-11-15）

对应[重构手册第 7 节](../2026-11-13-architecture-refactor-manual.md)。本阶段完成资源包主题扩展、原子快照与失效边界实现；不代表整份重构手册验收完成。

## 实现

- `client.preview.style.ThemeRegistry` 使用冻结的 `core.registry.Registry<ResourceLocation, ThemeDefinition>`。不可变 Snapshot 一次发布注册项、配置意图、有效主题和单调 revision。
- 删除 `GeometryPalette.Theme` 枚举及 `VisualThemes` 的重复主题表、独立计数器。两份内置 fallback 色板和原有 classic／humanist JSON 保持原值。
- 资源扫描覆盖任意命名空间与嵌套路径。一个 JSON 或读取失败只诊断该条目；坏内置覆盖使用内置 fallback，坏自定义主题不注册，其他主题照常发布。
- 配置保存资源 ID。尚未加载的 ID 保留为选择意图：资源出现时自动激活，移除时暂用 classic，再次出现时自动恢复。
- 设置按钮按 classic、humanist、其他 ID 的稳定顺序循环；显示翻译名，缺少翻译时显示资源 ID。VisualStyleContext 直接携带当前帧的主题 ID。
- RenderFrameEvent.Pre 固定线程内的渲染快照，Post 释放。即使帧内有订阅者切换／重载，颜色、值、曲线、呈现缓存、失效门和 PreviewFrame revision 也继续使用原快照；下一帧统一生效。
- `ThemeResourceInvalidation` 按 revision 依次清理几何计划、预览资源和 GPU 资源。同 revision 不重复清理；清理失败不接受该 revision，下次重试。

## 新增主题示例

在资源包添加 `assets/example/visual/themes/night/blue.json`：

```json
{
  "version": 1,
  "base": "fastformer:humanist",
  "name": "example.theme.night_blue",
  "colors": {
    "ink": "#BBDDEE",
    "gizmoX": "#D0806C"
  },
  "values": {
    "line_width": 0.8,
    "halo_alpha": 0.3
  }
}
```

该主题 ID 为 `example:night/blue`。资源重载后可在设置中选择，配置保存同一 ID。`name` 是可选翻译键；默认键为 `example.settings.theme.night.blue`。`base` 只选择 classic 或 humanist 的内置 fallback 色板与绘制风格，不继承资源包对基础主题 JSON 的覆盖。未提供的值和曲线沿用绘制调用方默认值。颜色格式、数值范围和曲线校验由 VisualTheme 统一负责。

## 验证与边界

本阶段未运行 Gradle、完整 check、GameTest 或真实客户端，未暂存、提交、推送或修改架构 baseline。

使用已安装 Zulu JDK 21 与现有缓存依赖，隔离输出至 `.temp/theme-registry-check/classes`；未改动正式 build 目录。

- 定向 javac 编译通过：注册表、主题定义、解析与加载、配置、VisualStyleContext、帧装配、设置界面，以及下面的 8 个测试类。设置界面编译同时纳入当前迁移后的 PlacementUpdateMode 和 SettingsKeys 源码。
- JUnit Platform 直接运行 **18 项测试，18 成功、0 失败、0 跳过**。
- 新增 ThemeRegistryTest、ThemeFrameSnapshotTest、ThemeResourcesTest、ThemeResourceInvalidationTest；迁移 GeometryPaletteRevisionTest、VisualThemeTest、GeometryTextPresentationTest、GizmoColorsTest。
- 覆盖三主题循环与 revision 唯一性、准备批次不可变、未知 ID、移除和恢复、重载新值、坏 JSON／版本／IO 隔离、失败发布不污染旧快照、帧内变更延后、三类资源只失效一次，以及失效失败重试。
- FastCtx 全 src 搜索旧主题枚举与重复主题状态，旧引用为零。`git diff --check` 通过。

仍需在统一回归中运行当前整批完整检查和严格架构检查，并实测资源包启用／移除／重载、至少三主题切换、坏资源日志、实际 GPU 释放与视觉表现。直接 JUnit 运行与定向编译不能替代这些验收。
