# 面栅格专用算法验证

本批只补充可注册的算法，不改变 `CURRENT_QUAD`、`CURRENT_POLYGON` 或生产调用路径。`normal_plane` 和 `translated_scan` 均保留。

## 实现范围

- `axis_rectangle`：按规范坐标系进行双循环，不生成边界或求解高度包络。
- `coordinate_plane`：扫描原始 Bresenham 边的投影行，沿用当前法线算法的对角行补点规则，不运行高度包络。
- `axis_extrusion`：将与挤出轴垂直的 Bresenham 轮廓沿该轴展开，按规范行、列、高度顺序输出。
- 三者通过 `FastFaceFrame` 使用现有规范坐标系。`FaceRasterAdapters` 只负责桥接与注册；算法自行处理预算、取消和候选输出。
- 算法不发布 `onGenerated`；只有调度器发布最终结果，避免候选结果污染观察者。

## 斜切拉伸限制

轴拉伸类中，轮廓可能沿挤出轴有额外位移。直接平移这样的 Bresenham 轮廓，与当前 `normal_plane` 的内部高度并不总相同。

反例顶点（整数方块坐标）：`(57,1,-8), (46,0,-10), (46,0,-5), (57,1,-3)`。直接拉伸时，部分内部方块保持 `y=1`；法线算法会取 `y=0`。

因此，`axis_extrusion` 只接受“轮廓位移与挤出轴点积为零”的正交子集；其余返回 `NotApplicable`，供配置链继续使用 `normal_plane`。没有用不同形状替换当前结果，也没有声称覆盖所有轴拉伸。验证样本中，正交子集 1000/1000 适用；另 100 个斜切样本均明确拒绝。该比例是定向测试覆盖，不是实际玩家输入分布。

## 验证

2026-11-14，定向 Gradle 测试退出码为 `0`：

```text
gradlew.bat --offline test --tests io.github.fastformer.fastplace.geometry.generation.SpecializedFaceRasterTest --tests io.github.fastformer.fastplace.geometry.generation.FaceRasterAdaptersTest
```

日志：`.temp/specialized-face-equivalence.log`。共 9 项测试：

- 每类 1000 个确定性随机面 × 两种 tie bias；逐项比较当前法线结果的方块集合和迭代顺序。
- 覆盖三个世界轴、正负方向、顶点起点与绕序变化、负坐标和平移。
- 世界边界附近约 `±29999000` 的整数边界与接近下一格的坐标归属。
- 恰好预算、不足预算、零预算与中途取消；候选算法不调用 `onGenerated`。
- 100 个斜切拉伸样本拒绝专用路径，保留原算法处理。
- 原有两类调度入口结果、顺序和观察者行为仍通过。

尚未把专用算法接入默认链，也未测量这些专用算法的性能收益；不能据此声称生产渲染或放置速度已经提高。
