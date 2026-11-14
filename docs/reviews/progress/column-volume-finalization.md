# 紧凑体积列拆分（2026-11-15）

`BresenhamColumnVolume` 负责独立面壳、扫描预算、候选列轴排序和最终发布。原有列跨度、空腔连通性与集合迭代移入 `common.geometry.generation.volume.column`。

- `ColumnVolume` 是体积算法调用的公开能力，拥有跨度、面样本、扫描通知与结果预算。
- `ColumnAirModel` 对有限空气区间执行连通性检查，不展开实体体素。
- `ColumnBlockSet` 与 `BoundaryColumnBlockSet` 按需迭代实体和六邻域边界。
- 列键、跨度和溢出安全的坐标计算保持包内可见。

保留插入顺序、候选数量及轴顺序的裁决规则、面壳样本、限额 witness、取消检查和最终发布时机。删除强制跳过候选／强制面失败的生产测试分支。失败测试改为注入真实 `FaceRasterDispatcher`；独立体素 oracle 通过 testSupport 的列存储 fixture 构造列。

当前批次尚待统一 Gradle 回归；不得以拆分前的测试数字证明本次代码通过。
