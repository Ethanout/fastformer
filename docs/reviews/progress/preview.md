# 架构重构进度归档：预览与渲染

返回 [进度索引](../2026-11-13-architecture-refactor-progress.md)。按时间顺序追加，不在此处维护当前计划。

## 预览同步的玩家作用域（2026-11-14）

- 删除 `PreviewSync` 的 7 张静态 UUID 表，由 `PreviewSyncState` 保存单玩家的活动、四类 revision、发布参数和回调身份。状态使用已有 `PlayerStateRegistry`，受服务器线程、服务器身份和玩家实例共同约束；查询和清理使用 `find`，断线后不能重新创建状态。
- 同 UUID 玩家替换后，旧实例的迟到清理不能删除新实例的发布。换维先通知瞬时状态失效，再恢复目标维度工作流并发布；保留原有维度停放与世界任务的生命周期。删除两处服务器关闭时的预览全局清理及对应入口，统一由玩家作用域丢弃。
- 新增 4 项 GameTest，覆盖不创建查询、迟到清理、换维／断线、同 UUID 替换，以及真实换维事件中的发布顺序。首次运行暴露测试夹具错误：mock 玩家自动登录，且未连接的替身会改绑在线玩家的进度对象。已使用不加入世界的玩家验证无附着状态和替换，未放宽生产生命周期约束。
- `.temp/architecture-preview-scope-final-verified.log`：完整 `check` 与独立新目录全量 298 项必需 GameTest 通过，2346 项单元测试、0 失败、1 跳过。新增预览包容量与静态状态检查。正式构建仓库配置和实际试玩存档未改动；本阶段单独提交并推送。
- 整份手册仍未完成。其余服务端业务实例化、完整任务运行单元、全部 Intent／客户端 Mode／PreviewFrame／PointerResolver、全操作树数据化、领域迁包和数学原语等继续保留；长按画线仍需实机手感验收。

## 世界离开事件迁出渲染（2026-11-14）

- `client.session.lifecycle.WorldSessionEvents` 统一接收客户端登出和世界卸载；删除渲染包的 `PreviewSessionLifecycle` 以及 Core 中的生命周期装配和事件订阅。渲染保留自身连接快照／资源重置，不再反向发起输入和选区断线清理。
- `WorldSessionLifecycle` 复用 `Scope.detach`，保持输入 → 源遮罩 → 预览 → 选区断线 → 交互缓存 → 反馈 → 渲染资源的顺序。一项清理失败仍继续其余参与者，最后汇总；重复离开事件不丢失资源。聚焦状态仅清反馈的逻辑仍归预览 tick。
- 测试迁至实际生命周期包，验证顺序、多处故障后的完整清理和重复边界。架构检查禁止渲染重新订阅世界离开事件或调用选区断线清理；首次检查捕获 CRLF 导致的未命中残留，修正后通过。
- `.temp/architecture-world-session-events-final-verified.log`：完整 check 与独立新目录全量 312 项必需 GameTest 通过，正常停服。完整 ClientScope、编辑器实例化、Mode、PreviewFrame、PointerResolver 和长按实机验收仍未完成。实际试玩存档未清除。

## 编辑上下文实例化与预览所有权（2026-11-14）

- 删除静态 `ClientOperationController`，每个 `ClientScope` 持有独立 `OperationContext`。输入、渲染、网络消费和世界事件显式取得当前实例，没有旧名称转发。选区与手势绑定构造时的对象，不再在每次查询中寻找全局选区。
- 捕获任务、提交等待、失败高亮、变换临时状态、源遮罩及错误信息归具体实例。捕获和保存回调保留当前选区所有者、连接、世界和提交 ID 校验；离开环境取消临时任务，不继续推进旧捕获。客户端仓库注入共享剪贴板，保留跨世界复制粘贴。
- `OperationPreviewState` 独立管理 live revision、重连等待与待确认快照，只返回接受／丢弃草稿等结果，不直接写草稿。首次作用域激活不制造重连；实际环境切换重置预览身份。保留重复 revision 拒绝、后续快照替代待确认快照、超时不丢草稿和本地结束不推进服务端 revision 的行为。
- 新增作用域编辑隔离、跨服务器保留草稿且清临时交互、首次快照和预览状态转换测试。提交结果测试改为注入真实 tracker，选区测试通过所属会话查询，删除对应私有静态字段／方法反射；其余 Unsafe 夹具仍待继续清理。
- 最终 `.temp/architecture-operation-context-verified3.log`：完整 check 与独立新目录全量 312 项必需 GameTest 通过。前轮新测试错误要求保留草稿的旧作用域变成空闲，现按原契约断言 POINTING；没有为通过测试丢弃草稿。新增禁止退役静态控制器与上下文静态可变字段的架构守护。
- OperationContext 仍约 1700 行；本阶段完成状态所有权和预览切片，尚未完成手册要求的八个能力组件。跨仓库提交协调、其余客户端能力拆分、Mode／PreviewFrame／PointerResolver、全操作树数据化与其他剩余主线继续保留。没有清除实际试玩存档，正式构建仓库配置未改动。

## 选区视觉指针帧（2026-11-14）

- 各模式滚轮决策阶段已提交并推送：`9bbd97c`。
- 新增不可变 SelectionPointerState 与 ClientPointerFrameSystem。选区创建候选、Gizmo、面和部件目标在帧开始解析一次，HUD／世界渲染和视觉悬停共用结果；删除输入总入口中的渲染帧订阅。渲染转发不再触发选区射线／模型命中。
- 物理点击仍实时解析；修正输入间接借用视觉 Gizmo 查询的两处路径，不使用上帧结果创建拖动。手柄生成仍依赖原预览模型，完整 InputView／PreviewMode 迁移尚未完成。
- 画面不可交互时捕获空结果，所有者变化拒绝旧帧，清理只影响所属解析器。捕获失败先清空结果，不能遗留旧目标；模型缓存失效与帧清理分离，避免模型读取抹掉刚捕获的目标。
- 新增帧内只解析一次、物理点击重新解析且不覆盖帧、所有者失效、空帧、失败恢复和多会话隔离测试；架构检查禁止渲染反向解析选区目标及输入调用视觉命中转发。
- 最终 `.temp/architecture-pointer-frame-final-verified.log`：完整 `check` 与独立新目录 312 项必需 GameTest 通过，包含最终输入修正和新增测试。实际试玩存档与正式仓库配置未改动。
- 当前只实现选区指针帧，不能视为完整 PreviewFrame；模式按键／指针、服务端模式、统一动画和透明度、完整操作树及布局迁移仍待完成。

## 统一帧动画相位（2026-11-14）

- 选区指针帧阶段已提交并推送：`e1b3b6f`。
- ClientPointerFrameSystem 扩展并改名为 ClientPreviewFrameSystem：每帧一次采样 AnimationPhase，包含单调时间、扩展样式时间和蜡笔绘制帧。虚线位移、虚影呼吸、蜡笔顶点／材质 shader 和 VisualStyleContext 共用同一相位；删除独立 BoilClock。
- 保留现有虚线速度／周期、2.4 秒呼吸、资源定义 boil_fps／boil_sheets、相邻绘制帧不重复和静态 sheet 0。稳定哈希提取到 core.math.StableHash，原蜡笔抖动与长短出头种子计算不变。
- 纯时钟测试覆盖同绘制帧重复采样、下一帧推进、资源设置变化、静态种子、不合法时序拒绝及与原虚线／呼吸公式对照；原绘制帧范围和不重复测试迁至实际时钟。源码检查禁止恢复独立 BoilClock。
- `.temp/architecture-animation-frame-verified.log`：完整 `check` 与独立新目录 312 项必需 GameTest 通过。实际试玩存档未清除，正式构建仓库配置未修改。
- worldPreviewOpacity 的隐式传递、完整 PreviewFrame／PreviewMode、模式按键／指针与服务端 Mode、完整操作树和其余手册项目仍需继续完成。

## 删除全局可变预览透明度（2026-11-14）

- 统一动画阶段已提交并推送：`feccd37`。
- 删除 worldPreviewOpacity 及更新／边界重置。现有可见性规则恒为 1，唯一临时变更来自 Alt 选区；该淡化现显式传入服务端选区的面、线、控制点、法线箭头、参考线与 Gizmo，不再 try/finally 改写共享状态。
- 工作区和草稿继续使用自身显式透明度；缓存网格不烘焙临时全局透明度。增加源码约束，禁止两处渲染总入口恢复共享可变值。底层绘制转发仍需后续移出 Core，不能据此视为渲染管线全部完成。
- `.temp/architecture-explicit-opacity-verified.log`：完整 `check` 与独立新目录 312 项必需 GameTest 通过；实际试玩存档和正式构建仓库配置未修改。
- 完整 PreviewFrame／PreviewMode、模式按键／指针和服务端模式、操作树和其余手册项继续保留。

## 独立绘制组件与反向依赖清理（2026-11-14）

- 显式透明度阶段已提交并推送：`c7584ed`。
- 提取 SelectionVolumeRenderer，保留棱柱／长方体的权威世界边界、膨胀量和主题颜色。选区直接调用独立体／线绘制；盒线框直接调用已有 DashedBoxRenderer，不再通过 Core 转发。
- ShapeShellRenderer、BuildingShellFaceBuffer 直接使用 PencilStroke／PreviewQuads；控制点回退直接创建显式参数的 ControlPointRenderer。删除 Core 的写面／画线公开适配，以及无调用的虚线盒和旧轮廓分支；源码约束禁止 shell／cache 依赖渲染总入口。
- 首轮 `.temp/architecture-render-primitives-verified.log`：check 通过，但 separatePressesAndRepeatedPositionsRetainDistinctUndoBoundaries 超时，停在等待第二次按压撤销；未改生产历史逻辑或测试断言。未改代码的新目录复验 `.temp/architecture-render-primitives-recheck.log`：完整 check 与 312 项必需 GameTest 全部通过。单次超时尚未稳定复现，不将其记为已修复。
- 实际试玩存档和正式构建仓库配置未修改。完整 PreviewFrame／PreviewMode、模式按键／指针和服务端模式、操作树及其余手册项目继续推进。

## 会话拥有预览快照与统一观察（2026-11-14）

- 专用工具按键阶段已提交并推送：`1e204e8`。
- `ClientPreviewState` 移入 `client.session.preview`，由 `ClientSessionSystem` 实例持有，渲染器不再创建服务端快照容器。世界边界直接清所属会话快照；缓存与绘制读取同一对象，原 revision、分包一致性及重连确认规则保留。状态及提交快照测试迁至实际包。
- `ClientModeView` 同时返回观察阶段、活动交互和空闲准入。装配层从真实能力读取事实，不再从渲染总入口拼接观察；删除输入中的 vanillaSession／activeSession／observedInputState／observedSessions。扳手、强制放置、HUD 和指针上下文统一读取模式视图。待发送起形、提交／取消路由及任务遮盖交互的既有语义不变。
- 穷举 512 组工作流、全部路由阶段及待发送起形开关，对照迁移前空闲规则；补充独立系统快照隔离、同 tick 更新、世界重置及旧视图不可变测试。源码约束禁止渲染重新拥有快照、快照反向读取渲染／实时客户端及恢复旧查询入口。
- `.temp/architecture-session-observation-tests.log` 定向测试通过；`.temp/architecture-session-observation-verified.log` 完整 check 与新目录 312 项必需 GameTest 通过。没有清除实际试玩存档，没有修改正式仓库配置。
- 网络载荷应用和渲染缓存失效仍在渲染总入口协调，后续需继续移交；完整 PreviewFrame／PreviewMode、指针及通用按键路由、服务端模式、操作树和其余手册项目尚未完成。

## 网络预览应用移出渲染（2026-11-14）

- 会话快照与观察阶段已提交并推送：`c6505fc`。
- `ClientPreviewUpdates` 绑定快照容器、编辑器同步与活动通知，接管起形三部分、提交参数、选区、几何和活动更新。重连确认按原顺序复用这些入口；网络注册删除渲染依赖，渲染总入口删除全部 apply 方法。重连等待改由客户端会话 tick 推进。
- 起形缓存主动观察 buildingVersion 后取消旧生成／清理缓存，不依赖网络处理器回调；几何计划仍按已有 geometryVersion 键失效。几何转换连续性规则移出渲染，并用独立计数记录每次已接收的阶段中断，保持多次快照在同一帧之间到达时的基线语义。
- 起形／几何输入、绑定、提交控制、填充、扳手与强制放置的纯状态查询直接读取所属会话能力，删除对应快照转发及不用的渲染依赖；没有改动协议与入口时序。
- 新增编辑器拒绝不发布、发布后活动通知、旧 revision 拒绝、分包恢复顺序／一次确认、不同 revision 取消恢复，以及帧间几何连续性中断测试。首次定向测试编译发现新夹具把 ResourceLocation 直接传给效果快照，已修正夹具后重跑通过。
- `.temp/architecture-preview-updates-tests.log` 定向测试通过；`.temp/architecture-preview-updates-verified.log` 最终完整 check 与新目录 312 项必需 GameTest 全部通过。实际试玩数据未清除，正式仓库配置未改变。
- Mode 指针与通用按键处理、完整 PreviewFrame／PreviewMode、服务端模式、完整操作树和手册其余项目继续待办；本阶段不是整体完成。

## 起形预览能力与资源所有权（2026-11-14）

- 普通鼠标模式决策阶段已提交并推送：`538277f`。
- 新增 PreviewMode、显式 WorldPreviewFrame 和首个 QuickShapePreviewMode。起形预览自行构建方块层、控制点、导引面和大规模边界降级，并拥有生成任务、外壳／模型／面／边缓存及取消。总入口仅捕获输入并调用模型构建与绘制，原主题和世界边界明确清理该能力。
- BuildingPreviewCache、BuildingPreviewGeneration、BuildingShellRenderer 和点绘制迁入独立能力目录；通用 PlacementModelPreview 归模型渲染包。删除共享资源中的重复起形缓存、未使用的可见性结果和待定边缓存。控制点动画使用同一帧相位，透明度显式传入。
- 补充两个缓存实例的 revision／清理隔离测试及大规模边界降级测试；原迟到任务、同键替换、队列取消与 alpha 测试迁至实际包。源码守护禁止起形预览反向依赖输入／渲染总入口和会话装配。
- `.temp/architecture-quickshape-preview-compile.log` 编译通过；`.temp/architecture-quickshape-preview-verified.log` 完整 check 与新目录 312 项必需 GameTest 全部通过。实际试玩存档未清除。
- 这是第一个真实 PreviewMode 实现；完整跨模式 PreviewFrame、几何／选区／空闲预览、HUD、模式生命周期和手册其他项目仍未完成。

## 几何预览模型与绘制能力（2026-11-14）

- 起形预览阶段已提交并推送：`ae72c1a`。
- GeometryPreviewMode 接入真实的 buildModel/drawWorld。独立 GeometryPreviewModels 拥有计划与图层缓存，显式接收快照、revision、修饰键、眼睛位置、视线、候选和悬停点；保留计划失败控制点降级、控制点占格排除及锥体体阶段底面预览。Gizmo 缩放和悬停仍按原规则在该帧呈现。
- GhostBlockPreviewRenderer 拥有已确认／待确认网格、异步生成、GPU 上传与释放，删除总入口约 300 行重复绘制原语和共享缓存。世界绘制帧增加模型视图／投影矩阵，传入统一动画与透明度；智能选区通过客户端生命周期能力取得原共享执行器。
- 新增缓存完整键、同键复用、明确清理、不同所有者隔离、标记占格排除，以及失败后 revision 恢复测试。源码守护禁止几何预览依赖输入／会话／渲染总入口或查询全局客户端。
- 首次编译发现深度偏移导入和智能选区执行器依赖遗漏，已修复；新增测试首次使用不存在的 Builder.pending，已改为既有图层构造。`.temp/architecture-geometry-preview-verified.log` 完整 check 和独立新目录 312 项必需 GameTest 全部通过。
- HUD、几何指针事实采集、其他预览模式、完整模式生命周期和手册其余任务仍待迁移。实际试玩数据未清除。
