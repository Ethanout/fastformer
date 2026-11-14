# 空闲与工具预览帧（2026-11-14）

- 空闲预览能力拥有模型缓存；填充和替换各持有独立 BlockPreviewMode，复用捕获的数据和方块模型绘制。删除原 Core 中两个直接读取输入状态的工具绘制器。
- 帧装配在 HUD／世界绘制前冻结 idle／fill／replace 模型；WorldPreviewFrame 显式携带绘制所需客户端与缓冲信息。资源失效与世界离开清理分开，切主题不会清空本帧刚捕获的模型。
- 选点拖动导引抽成 SelectionPointGuides；绘制原语接收 VertexConsumer，不反向选择渲染类型。主题和控制点纯语义边界见 controlpoint-theme-domain.md。
- 阶段验证：architecture-capability-closed-tests.log 的编译、2591 单测（0 失败、1 跳过）、GameTest 编译、源码布局与架构工具通过；架构报告 1246 项旧债务、新增 0、严格边界 0。后续线阶段回调和有界光栅修复须以最终整批回归为准。
- 完整选区 PreviewMode、HUD／world 同一完整帧、删除 PreviewRenderOwner、两个总入口收口尚未完成。
