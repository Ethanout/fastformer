# 面光栅化只读对比实验

状态：已完成；样本 5000/5000；固定种子 `202611140106`；总耗时 166612.736 ms。

本实验不改路由、不写世界、不删除算法。四边形边长按欧氏距离检查为 1～128 格，长度分档抽样；倾斜平行四边形与非平行四边形各占一半。后者采用随机整数向量u、v构造0/2u/(u+v)/v共面梯形，覆盖任意朝向。样本不按算法是否成功筛选。

每算法每样本上限 100000 格、250ms、8000000 次扫描；达到实验预算分别记 TIME_BUDGET/SCAN_BUDGET，不算空集合成功。projected 旧接口不支持内部检查，只能前后检查；其250ms不是强制抢占。耗时包含Frame构造，不包含质量统计；P50/P99含失败和预算结果。两算法执行先后交替，暖机12组。本机单进程数据只能用于相对观察。

质量定义：边界为四条原始 `LineGenerator.path(DEFAULT)` 的并集；缺边统计该集合缺失格数。投影使用现有Frame的同一signed-axis坐标，连通为四邻接；外围补一格后空气四邻接洪泛未触达的空格为闭洞。厚度统计同一法向主轴投影列中超过一格的列，另列出排除边界列的内部厚列；它不是对现有算法的自动判错。现有测试允许连续高度区间，因此同时记录高度区间有缺口的列。

## non_parallelogram_quad

样本 2500；双方成功 0；集合完全相同 0（成功配对中 无配对）；遍历顺序也相同 0。

状态计数：`{current_polygon:SUCCESS=2500, projected_polygon:SUCCESS=2500, translated_scan:NOT_APPLICABLE=2500}`。

补充当前 polygon 完整入口与 projected 对照：成功配对 2500，集合相同 2500（100.00%）。这是区分原始 translated 不适用与当前实际回退行为的附加证据。

- current_polygon：P50 0.342 ms；P99 1.844 ms。缺边总格数 70409（2427 样本）；投影不连通 0；有闭洞 0（共0空格）；有多格列 0 样本（0列，内部0列）；有高度缺口 0 样本。
- projected_polygon：P50 0.199 ms；P99 1.302 ms。缺边总格数 70409（2427 样本）；投影不连通 0；有闭洞 0（共0空格）；有多格列 0 样本（0列，内部0列）；有高度缺口 0 样本。
- translated_scan：P50 0.037 ms；P99 0.109 ms。

## tilted_parallelogram

样本 2500；双方成功 2384；集合完全相同 7（成功配对中 0.29%）；遍历顺序也相同 0。

状态计数：`{normal_plane:SUCCESS=2500, translated_scan:SUCCESS=2384, translated_scan:TIME_BUDGET=116}`。

- normal_plane：P50 1.078 ms；P99 7.473 ms。缺边总格数 0（0 样本）；投影不连通 0；有闭洞 0（共0空格）；有多格列 451 样本（20609列，内部13078列）；有高度缺口 0 样本。
- translated_scan：P50 30.934 ms；P99 251.827 ms。缺边总格数 0（0 样本）；投影不连通 19；有闭洞 0（共0空格）；有多格列 2375 样本（926153列，内部878781列）；有高度缺口 0 样本。

### 差异案例 1（样本 0）

角点：`(-41,-96,-253), (-43,-97,-253), (-57,-90,-264), (-55,-89,-264)`。

第一算法/translated_scan 格数：45/56；质量：`Quality[missingBoundaryBlocks=0, components=1, holeCells=0, multiHeightColumns=0, interiorMultiHeightColumns=0, gappedColumns=0]` / `Quality[missingBoundaryBlocks=0, components=1, holeCells=0, multiHeightColumns=11, interiorMultiHeightColumns=7, gappedColumns=0]`。

仅第一算法（2格，最多列32格）：`(-49,-93,-259), (-46,-94,-257)`。

仅 translated_scan（13格，最多列32格）：`(-56,-90,-263), (-54,-90,-262), (-53,-91,-261), (-52,-92,-261), (-50,-93,-258), (-50,-92,-259), (-49,-93,-258), (-47,-94,-256), (-47,-93,-257), (-46,-95,-255), (-46,-94,-256), (-43,-96,-253), (-43,-95,-254)`。

### 差异案例 2（样本 2）

角点：`(-133,-48,44), (-134,-46,44), (-167,-31,130), (-166,-33,130)`。

第一算法/translated_scan 格数：261/295；质量：`Quality[missingBoundaryBlocks=0, components=1, holeCells=0, multiHeightColumns=0, interiorMultiHeightColumns=0, gappedColumns=0]` / `Quality[missingBoundaryBlocks=0, components=1, holeCells=0, multiHeightColumns=34, interiorMultiHeightColumns=30, gappedColumns=0]`。

仅第一算法（14格，最多列32格）：`(-166,-32,127), (-165,-33,125), (-164,-33,122), (-162,-34,117), (-160,-35,112), (-155,-37,98), (-155,-37,99), (-153,-38,93), (-151,-39,88), (-149,-40,83), (-147,-41,78), (-145,-42,73), (-144,-42,70), (-142,-43,65)`。

仅 translated_scan（48格，最多列32格）：`(-167,-32,130), (-165,-32,127), (-165,-32,128), (-164,-33,125), (-164,-33,126), (-163,-33,122), (-163,-33,123), (-162,-34,120), (-161,-34,117), (-161,-34,118), (-160,-35,115), (-159,-35,112), (-159,-35,113), (-158,-36,110), (-157,-36,107), (-156,-37,105), (-155,-37,102), (-154,-37,98), (-154,-37,99), (-154,-37,100), (-153,-38,97), (-152,-38,93), (-152,-38,94), (-151,-39,92), (-150,-39,88), (-150,-39,89), (-149,-40,87), (-148,-41,81), (-148,-40,83), (-148,-40,84), (-146,-41,78), (-146,-41,79)`。

### 差异案例 3（样本 4）

角点：`(-4,-14,240), (-2,-17,240), (17,17,242), (15,20,242)`。

第一算法/translated_scan 格数：163/167；质量：`Quality[missingBoundaryBlocks=0, components=1, holeCells=0, multiHeightColumns=0, interiorMultiHeightColumns=0, gappedColumns=0]` / `Quality[missingBoundaryBlocks=0, components=1, holeCells=0, multiHeightColumns=4, interiorMultiHeightColumns=4, gappedColumns=0]`。

仅第一算法（0格，最多列32格）：``。

仅 translated_scan（4格，最多列32格）：`(1,-6,241), (2,-8,241), (11,11,241), (12,9,241)`。

## 结论边界

本报告提供选择依据，不决定哪种形状更符合作者意图。未达到预算、算法不适用和没有候选均单独计数；不能把它们算成等价。是否统一两条入口、切换非平行四边形或删除 translated_scan，仍由用户依据差异决定。逐样本原始数据在同目录 `face-raster-comparison.csv`。
