# 纯面算法最小闭包迁移

本批只处理能独立封闭的公共能力。没有改变生产选路、几何结果、渲染样式或输入行为；未把旧生成包整体改名。

## 包边界

- `common.geometry.model`：`FillMode`、`PlaneAxes`、`GeometryConstraints`、`GuideLine`、`GuidePlane`、`TransformFrame`，共 6 个共享语义与几何值类型。
- `common.geometry.raster`：`BlockGenerationObserver`，定义各算法共享的扫描、最终输出与取消边界。
- `common.geometry.raster.line`：`LinePath`，拥有 eager Bresenham 路径、端点次序和斜率排序的唯一实现。
- `common.geometry.raster.face`：请求、结果、分派；子包分别拥有分类、算法、策略数据。
- `fastplace.geometry.generation.FaceRasterAdapters`：继续作为旧算法与公共接口的组装桥接。旧算法的 package-private 内部类型没有为迁包全部公开。

旧 `LineGenerator` 保留 lazy Set／Source 生成入口，路径相关 API 委托 `LinePath`；公共面算法直接依赖 `LinePath`，不依赖旧生成包。此前属于同包而无需 import 的模型／观察者引用，已补为明确的新类型 import。未保留旧路径兼容类。

## 静态核验与验证安排

已对 `src` 下全部 Java 文件（包括未暂存的新文件）检查：旧 face／observer／上述 6 个模型 FQN 无残留；新公共包没有指向 `fastplace`、`client`、`server`、`protocol` 的 import。新包类型数分别为 6／1／1／3／7／3／3，均不超过 12。定向 `git diff --check` 退出码为 0。

迁移后尚未运行 Gradle，按协作安排交由 root 串行集成验证。已迁测试位于 `io.github.fastformer.common.geometry.raster.face`（含 `classify`、`policy`）；`SpecializedFaceRasterTest` 和 `FaceRasterAdaptersTest` 继续位于旧 generation 测试包，以便访问尚未迁出的 package-private 参考算法。

## 剩余旧生成能力

旧 generation 中的 normal／scan／projected 实现、体积算法、位置存储和业务形状入口仍待分别整理。后续应按实际 package-private 依赖簇形成 face 实现、volume、surface 和生成结果／位置流能力；必须先消除跨职责耦合，再移动。当前批次不代表这约 50 个旧类型已经全部完成公共层迁移。
