# 源方块遮罩改为纯渲染层（BUG-X 架构修复）

## 结论

源方块遮罩不再写入客户端世界。`SourceBlockRenderMask` 现在只发布一份不可变遮罩快照，
区块构建器（`RenderChunkRegion`）在构造时绑定该快照并对整块网格生效，方块实体渲染与
选中框另行过滤。客户端碰撞、射线选取和方块实体数据保持服务端状态，取消与提交由服务端
同步驱动。

本文记录已落地的实现、偏差、已知限制和实机验收项。**本文不声称异步上传时序问题已经
解决**，该项仍是运行验收风险。

## 已实施改动

| 文件 | 作用 |
| --- | --- |
| `client/render/mask/SourceMaskRenderFilter.java` | 新增。进程级遮罩注册表，持有 `volatile Snapshot`，提供 `publish` / `clear` / `hides` / `positions` / `masked` / `maskedBlockState`。不引用 `ClientLevel`、`Minecraft`。 |
| `client/render/mask/SourceMaskRenderHooks.java` | 新增。每客户端 tick 比较遮罩版本，对进入或离开遮罩的坐标调用 `LevelRenderer.setBlocksDirty` 标记脏区。 |
| `client/render/mask/SourceMaskHighlightHandler.java` | 新增。取消被遮蔽坐标的 `RenderHighlightEvent.Block`，避免在隐形方块上画选中框。 |
| `client/mixin/RenderChunkRegionMixin.java` | 新增。构造时绑定快照；`getBlockState` 返回 `VOID_AIR`，`getFluidState` 返回空流体，`getBlockEntity` 返回 `null`。 |
| `client/mixin/BlockEntityRenderDispatcherMixin.java` | 新增。屏蔽被遮蔽坐标的方块实体渲染，覆盖已编译区块列表里的残留实体。 |
| `client/mixin/LevelRendererAccessor.java` | 新增。`@Accessor("viewArea")`，用于在标记脏区前检查区块网格是否存在。 |
| `resources/fastformer.mixins.json` | 新增 mixin 配置，注册 3 个 client mixin。 |
| `resources/META-INF/neoforge.mods.toml` | 启用 `[[mixins]]` 声明。 |
| `client/operation/preview/SourceBlockRenderMask.java` | 改为薄门面。删除 `ClientLevel` 字段、`setBlock` 调用、状态/方块实体快照、`restore`、`overlappingTargets`、`needsReapply`、`isServerUpdate`。 |
| `client/render/PreviewBlockOcclusion.java` | 只改 `PreviewLevel.getBlockState` 的底层兜底分支：被遮蔽坐标返回隐藏占位状态。overlay 幽灵方块分支不变，通用世界碰撞/射线不变。 |
| `src/test/.../preview/SourceBlockRenderMaskTest.java` | 重写。删除失效 API 的测试，新增渲染语义与生命周期断言。 |
| `src/test/.../render/mask/SourceMaskRenderFilterTest.java` | 新增。覆盖发布、去重、版本单调、快照隔离。 |
| `src/test/.../render/PreviewBlockOcclusionMaskTest.java` | 新增。覆盖底层兜底遮蔽、未遮蔽坐标保持原状态、overlay 幽灵方块不被遮蔽。 |

关键路径不再调用 `ClientLevel.setBlock`，也不再读写 `CompoundTag` 形式的方块实体数据。
`complete(Map)` 与 `reapply()` 的签名保留，供当前 controller 与输入模块调用，内部改为
发布空快照与标记脏区。

## 关键决策

1. **必须使用 Mixin。** `AddSectionGeometryEvent` 只能追加几何体，不能抑制原版几何体；
   NeoForge 没有可在区块编译中取消原版输出的公开钩子。这是与 `README.md` “尽量不使用
   Mixin” 的唯一偏差。用户已授权。
2. **按区块绑定快照。** `RenderChunkRegion` 每次编译重新构造，构造点绑定当前 `Snapshot`。
   网格构建在异步线程上读取该实例，因此一次构建只会看到同一版本；发布新遮罩不会改变
   正在进行的构建结果。
3. **一次发布只标记差值区。** 未变化的坐标集合保持原版本号，重复发布不触发重建。
   脏区使用 `LevelRenderer.setSectionDirty`，坐标取该位置 ±1 格覆盖到的所有区块，覆盖
   跨区块边界的相邻区块。已标记版本号只在全部脏区提交成功后才推进；提交失败时下一
   tick 重试同一差值，重复标脏是幂等操作。
4. **方块实体单独过滤。** 若区块在遮罩变化前已经编译，其实体列表仍包含被遮蔽坐标的实体。
   `BlockEntityRenderDispatcher` 头部拦截覆盖该时间窗，包括全局方块实体列表。
5. **`viewArea` 空值检查落在生产代码。** `LevelRenderer.setSectionDirty`/`setBlocksDirty`
   在区块网格缺失时抛空指针。`SourceMaskRenderHooks.reconcile` 通过 `LevelRendererAccessor`
   读取该字段并在为空时直接返回，世界切换与进入世界前的调用不会崩溃。

## 已知限制

- 遮罩隐藏整个源方块，包括其方块实体。旧实现保留方块实体是因为它从未从客户端世界移除；
  纯渲染遮罩按整块隐藏，与该语义不同。
- 客户端世界保留真实方块，因此准星仍可命中源方块，碰撞保持服务端状态。这是本修复的目标，
  不是回归；选中框由 `SourceMaskHighlightHandler` 隐藏。
- 客户端可见的延迟重建窗口：标记脏区后，该区块在新网格上传前仍显示旧网格。

## 未验证风险（须实机确认）

- **异步编译与取消时序未实机验证。** 结论只依据静态调用链：`createCompileTask` 先调用
  `cancelTasks()`，被取消任务在 `setCompiled` 前检查 `isCancelled`；`compileSections` 在
  调度后立刻 `setNotDirty()`，因此遮罩变化若落在一次在途编译之后，理论上最多出现短暂旧
  网格。本实现只保证标记脏区后必定发生一次新编译，不保证旧网格从不上传。该项必须在游戏
  内确认。
- 脏区标记发生在客户端 tick，区块编译发生在同线程的渲染阶段，两者不交错。该顺序结论
  来自源码阅读，未实测。
- 全部遮罩坐标位于同一空区块时，`RenderRegionCache.createRegion` 返回 `null`，此时原版
  不会编译该区块。该区块本身没有可见内容，因此该情形不产生可见差异；未实测。
- 实机加载性能未测：遮罩变化会重建受影响区块，遮罩很大时重建次数随之增加。

## 实机验收项

1. 站在被选中的实体方块上，对选区做未提交的移动：源方块消失，玩家不因遮罩下落或抖动。
2. 取消移动：源方块按最新服务端状态重新出现，不出现空气占位残留。
3. 提交移动：源位置显示服务端权威状态，不出现本地预测写入的方块。
4. 源区块重新加载（走远再回来）：遮罩仍然生效，无闪烁残留。
5. 其他玩家更新源方块：客户端显示服务端状态；遮罩不恢复旧状态。
6. 带方块实体的选区（箱子、告示牌、刷怪笼）：实体渲染随方块一起隐藏，区块重建后不残留。
7. 未提交预览期间，碰撞与射线选取保持服务端状态（不出现可穿过或不可穿过被遮蔽方块）。
8. 切换维度与世界：无空指针崩溃，进入新世界后遮罩状态与草稿一致。

## 测试

- `SourceMaskRenderFilterTest`：发布、去重、空集合、版本单调、旧快照在发布后保持自身内容。
- `SourceBlockRenderMaskTest`：无客户端世界时的遮蔽语义（证明不写世界）、提交与取消的
  生命周期、`maskedSourcePositions` 规则。
- `SourceMaskOcclusionGameTests`：真实注册表与真实世界下的遮罩-预览集成（见下文“真实注册表
  验证”一节）。
- 自动测试通过不代表 mixin 注入成功。mixin 只在游戏运行时生效，测试类路径不加载 Mixin
  变换器，因此必须实机确认第 1～8 项。

## 遗留

- `ClientOperationController.applyWorkspaceResult` 的 accepted 分支仍为旧实现逐部件完整
  `resolve` 生成 `committedTargets`。该段现在只被 `complete(Map)` 丢弃，属于 controller
  范围，未在本次修改。
- **幽灵预览的邻面剔除已接入遮罩。** `WorkspacePreviewRenderer.renderBlocks:51-52` 以
  `minecraft.level` 作为 `PreviewBlockOcclusion.PreviewLevel` 的底层视图。旧实现把源方块写成
  空气，因此幽灵方块朝向源方块的面会被绘制；现在底层兜底分支对被遮蔽坐标返回隐藏占位状态，
  该面恢复绘制。只改底层兜底，overlay 幽灵方块保留自身状态，通用世界碰撞与射线未改。
- 未实测（已迁移到 GameTest）：方块注册表相关的覆盖不再依赖单测环境的假设守卫，见下节。

## 真实注册表验证：单测伪覆盖已迁移到 GameTest

原 `PreviewBlockOcclusionMaskTest`（3 个 `assumption` 守卫的单测）已删除。原因：三个用例
首句都是 `assumeBlockRegistryAvailable()`，方块注册表不可用时三项全部 abort，报告为
`tests=3 / skipped=3`、实际执行 0 项；把这种“没有失败”读成验证通过会误导。改为在真实专用
服务器上运行：

`src/main/java/io/github/fastformer/fastplace/world/SourceMaskOcclusionGameTests.java`
（`@GameTestHolder`，与生产 GameTest 同目录）

- `theBaseLookupHidesAMaskedSourcePosition`：真实世界放石头，发布遮罩后断言兜底返回
  `Blocks.VOID_AIR`，且未遮蔽邻居仍是石头。
- `aPreviewBlockKeepsItsOwnStateOnAMaskedPosition`：同一坐标既是遮罩位又在 overlay 中时返回
  overlay 状态，遮罩不得盖掉新目标方块。
- `anUnmaskedPositionKeepsTheBaseState`：未遮蔽坐标返回真实世界状态。

每个用例都在 `try/finally` 里 `SourceMaskRenderFilter.clear()`，防止进程级遮罩串扰。
测试类不引用客户端类：`PreviewBlockOcclusion` 与 `SourceMaskRenderFilter` 只用 common 类型，
专用服务器可以加载（`FastFormerGameTests.previewOcclusionUsesPreviewAndWorldBlockShapes`
已有同路径先例）。

## 世界高度契约核查（只读，未改生产）

`PreviewLevel.getHeight()` / `getMinBuildHeight()` 硬编码 384 / -64。核查结论：**当前没有任何
真实消费者读取这两个值，因此不新增“应代理 base 高度”的 GameTest。**

- 预览路径内（`client/render/**`）唯一出现 `getHeight()`/`getMinBuildHeight()` 的位置就是
  `PreviewBlockOcclusion` 自己的这两个实现，没有调用方。
- 可达的原版消费者都没有高度调用：`Block`、`BlockBehaviour`、`BlockBehaviour$BlockStateBase`、
  `BlockGetter`、`ModelBlockRenderer`、`BlockRenderDispatcher`、`LiquidBlockRenderer` 的字节码
  中都不含 `LevelHeightAccessor.getHeight/getMinBuildHeight/isOutsideBuildHeight`。
- `PreviewLevel` 自己也不把这两个方法委托给 base。

在 overworld 上 384 / -64 恰好等于真实高度，断言会退化成空断言；在自定义高度维度里则会失败，
等于给生产代码没有的行为写测试。该项记为潜在不一致：一旦出现真实调用方，最小修复是在
base != null 时把 `getHeight()`/`getMinBuildHeight()` 委托给 base（`client/render` 生产文件，
本次未改）。
