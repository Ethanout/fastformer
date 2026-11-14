# 几何生成职责迁移（2026-11-15）

原 `fastplace.geometry.generation` 的纯生成算法迁入 `common.geometry.generation`，原 movement 三类迁入 `common.movement`。生成结果、预览导引、射线可见性和阶段类型分别归入对应的 common 几何包。不保留旧路径转发。

| 包 | 职责 |
|---|---|
| `generation` | 单一公开 API `FastPlaceGeometry`；package-info 声明 API 入口。 |
| `generation.source` | 12 个类型：有界／渐进结果、惰性方块源、观察者包装、限额失败及坐标谓词。 |
| `generation.face` | 12 个类型：投影面算法及共享框架；包含 TranslatedScan 的五个协作文件。 |
| `generation.face.entry` | 四边形与多边形两个生成入口，分别保留原默认算法。 |
| `generation.volume` | 有界体积、柱扫描、棱柱／棱锥等严格体积算法；柱扫描拆分由主任务处理。 |
| `generation.shape` | 锥体、多面体、球、墙、扫掠、放样及其惰性集合。 |

所有新包包含职责说明。`ObservedBlockSet`、`BlockPositionSources`、`LazyBlockSet` 等内部实现继续包私有。跨包生产调用仅通过实际需要的工厂或几何操作访问，不为测试扩大内部类型可见性。跨包测试通过 testSupport 中的 `FaceRasterFixtures` 和 `ColumnVolumeFixtures` 调用。

`TranslatedScanFaceRasterizer` 保留入口、结果选择和共享值类型；`TranslatedScanTiles` 负责周期微瓦片与等高截面；`TranslatedScanConnections` 负责边界连接、六连通支撑和最小割；`TranslatedScanTopology` 负责连通、柱厚与封闭空气检查；`TranslatedScanModel` 负责投影坐标、格点域和偏好高度。按原方法块提取，未修改算法正文、循环顺序、默认面形状或取消检查位置。

两个并查集和残量流图迁入无游戏依赖的 `core.graph`。流图通过 `Runnable` 接收原观察者的取消检查，在原检查位置调用；饱和正数工作量计算归入 `core.math.SaturatedArithmetic`。

打包必需条目和面算法比较入口随实际路径更新。单元测试及 GameTest 的包归属跟随被测能力。最终编译、算法回归、结构和字节码检查由主任务统一执行；本文不单独宣称完整验收通过，也不代表整份架构手册已完成。
