# 2026-09-14 预览世界会话清理边界

`PreviewSessionLifecycle` 现在负责客户端世界会话结束时的清理顺序。它编排输入会话、源遮罩、预览会话、操作控制器、交互缓存、HUD 反馈和世界渲染状态的清理；每个状态仍由原有组件持有和释放。

`FastPlaceClientPreviewCore` 仅提供状态所有者适配器，并在客户端不再有聚焦世界时委托 HUD 反馈清理。登出和客户端世界卸载继续调用同一个世界会话结束入口，顺序保持为先结束输入和遮罩、再重置会话与操作、最后释放渲染状态。

`PreviewSessionLifecycleTest` 覆盖每个状态所有者的清理顺序，以及菜单、失焦或无世界时只清理 HUD 反馈的边界。`./gradlew.bat test --tests io.github.fastformer.client.render.core.PreviewSessionLifecycleTest` 通过。

仍需完成客户端断线、换维度、世界卸载和重连的实机验收；预览 HUD/反馈、缓存、几何命中和世界渲染分派的其余职责拆分继续保留在 TODO。
