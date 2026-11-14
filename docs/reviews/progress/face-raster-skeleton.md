# 面光栅化：分类与调度骨架（2026-11-14）

对应手册第 10.7 节第 1 步。新增代码尚未接入现有生成入口，因此当前游戏的算法路由和形状结果不变。

## 已实现

- `face/FaceRequest` 冻结顶点、填充模式、tie bias、上限和观察者；`FaceRasterResult` 区分成功、超限、不适用和没有候选。成功集合保持插入顺序且不可变。
- `face/classify` 先按现有 `BlockPos.containing` 取整，再只用整数比较；平行四边形判定复用 `BlockQuads`。按简单度区分轴矩形、坐标平面、轴向拉伸、倾斜平行四边形、多边形与退化图形。跨乘和面积累加使用 `BigInteger`，避免极值坐标溢出。
- `face/algorithm` 提供实例所有者持有的冻结注册表、稳定 `ResourceLocation` ID 和有界边线兜底。
- `face/policy` 验证每类都有非空算法链，调度器构造时拒绝未注册 ID。
- 调度器只分类一次；不适用和无候选继续，超限立即停止。轮廓入口绕过填充链。最终不同方块才触发 `onGenerated`，被拒绝的候选不发布已生成事件。
- 原算法均保持原可见性。唯一同包桥接 `generation/FaceRasterAdapters` 包装当前两条完整调用路径，以及 normal、translated 和 projected 的独立尝试。

## 当前仍保留的分歧

`CURRENT_QUAD` 与 `CURRENT_POLYGON` 是两份明确的旧入口策略。它们调用各自原逻辑，不能视为已经统一。调度骨架没有新增全局默认策略，也没有改动任何现有调用方。

旧 `ProjectedBresenhamFace.raster` 内部尚无观察者与上限参数；当前桥接只能在调用前后检查取消、在得到结果后检查大小。该适配器尚不满足第 10.4 节“内部提前取消／超限”的最终契约。

## 验证与后续

新增 `FaceClassifierTest`、`FaceRasterDispatcherTest`、`FaceRasterAdaptersTest`。覆盖分类、负坐标取整、退化与整数极值、算法链顺序、超限不回退、取消、只发布最终方块，以及与两条旧入口的集合和遍历顺序对照。主代理统一完整 `check` 与新世界 312 项必需 GameTest 通过，退出码 0，日志 `.temp/architecture-modes-feedback-verified.log`。

5000 样本只读实验已完成，命令 `gradlew.bat --offline compareFaceRasters`，退出码 0，日志 `.temp/face-raster-comparison-5000.log`。结果见 [对比报告](face-raster-comparison.md)：倾斜面双方成功的 2384 组仅 7 组集合相同；非平行四边形的原始 translated 全部不适用，而当前完整 polygon 入口与 projected 的 2500 组集合完全相同。不能把原始算法不适用算为等价成功。

下一阶段：实现专用算法及与 normal 的严格等价测试；依据实验报告决定改变形状的路由；最后接入服务端策略加载、同步和预览哈希。`TranslatedScanFaceRasterizer` 的去留需要用户依据实验结果决定。
