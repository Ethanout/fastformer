# 2026-09-13 源方块遮罩渲染接入审计

## 结论

`SourceBlockRenderMask` 仍只提供集合更新、查询和清理；当前客户端没有 mixin 或方块世界渲染事件读取 `sourceMask()`。`FastPlaceClientPreviewCore.onRenderLevelStage()` 仅在 `AFTER_PARTICLES` 绘制叠加虚影，因此遮罩存在不等于 vanilla 源方块已被隐藏。

本轮未添加未经确认的 NeoForge 渲染钩子。BUG-02 继续保持“已报告，待复现”，必须先在 `.233` 客户端确认可用的世界方块渲染拦截入口，再进行实机验收。
