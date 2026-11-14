package io.github.fastformer.fastplace.geometry.generation;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import net.minecraft.core.BlockPos;

/** Explicit offline experiment; never runs under check and never writes a world or changes policy. */
public final class FaceRasterComparison {
    private static final int LIMIT = 100_000;
    private static final long SAMPLE_NANOS = 250_000_000L;
    private static final long SCAN_LIMIT = 8_000_000L;
    private record Run(String status, Set<BlockPos> blocks, long nanos, FaceComparisonQuality.Quality quality) {}
    private record Case(FaceComparisonSamples.Sample sample, Run first, Run second, List<BlockPos> onlyFirst, List<BlockPos> onlySecond) {}
    private static final class Totals {
        int samples; int paired; int equal; int sameOrder; int currentRoutePairs; int currentRouteEqual;
        final Map<String, Integer> statuses = new TreeMap<>();
        final Map<String, List<Long>> times = new TreeMap<>();
        final Map<String, long[]> quality = new TreeMap<>();
        final List<Case> examples = new ArrayList<>();
        void add(String algorithm, Run run) {
            statuses.merge(algorithm + ":" + run.status, 1, Integer::sum);
            times.computeIfAbsent(algorithm, ignored -> new ArrayList<>()).add(run.nanos);
            if (run.quality == null) return;
            var q = run.quality;
            long[] sum = quality.computeIfAbsent(algorithm, ignored -> new long[9]);
            sum[0] += q.missingBoundaryBlocks();
            if (q.missingBoundaryBlocks() > 0) sum[1]++;
            if (q.components() != 1) sum[2]++;
            if (q.holeCells() > 0) sum[3]++;
            sum[4] += q.holeCells();
            if (q.multiHeightColumns() > 0) sum[5]++;
            sum[6] += q.multiHeightColumns();
            sum[7] += q.interiorMultiHeightColumns();
            if (q.gappedColumns() > 0) sum[8]++;
        }
    }

    private FaceRasterComparison() {}

    public static void main(String[] arguments) throws Exception {
        if (arguments.length < 1) throw new IllegalArgumentException("Expected absolute output report path");
        Path report = Path.of(arguments[0]).toAbsolutePath();
        int sampleCount = arguments.length > 1 ? Integer.parseInt(arguments[1]) : 5000;
        if (sampleCount < 2) throw new IllegalArgumentException("At least two samples required");
        Files.createDirectories(report.getParent());
        Path csv = report.resolveSibling("face-raster-comparison.csv");
        Map<String, Totals> groups = new TreeMap<>();
        FaceComparisonSamples samples = new FaceComparisonSamples();
        // Warm both paths before timing; generated inputs remain disjoint from measured IDs.
        for (int index = 0; index < 12; index++) {
            var warm = samples.next(index + 100_000, true);
            run("normal_plane", warm); run("translated_scan", warm);
        }
        long started = System.nanoTime();
        try (BufferedWriter output = Files.newBufferedWriter(csv, StandardCharsets.UTF_8)) {
            output.write("id,group,algorithm,status,nanos,blocks,missing_boundary,components,hole_cells,multiheight_columns,interior_multiheight_columns,gapped_columns\n");
            for (int index = 0; index < sampleCount; index++) {
                boolean parallelogram = index % 2 == 0;
                var sample = samples.next(index, parallelogram);
                String firstAlgorithm = parallelogram ? "normal_plane" : "projected_polygon";
                // Alternate execution order to avoid always charging cold caches/GC to one implementation.
                Run first; Run second;
                if (index % 4 < 2) { first = run(firstAlgorithm, sample); second = run("translated_scan", sample); }
                else { second = run("translated_scan", sample); first = run(firstAlgorithm, sample); }
                Totals totals = groups.computeIfAbsent(sample.group(), ignored -> new Totals());
                totals.samples++; totals.add(firstAlgorithm, first); totals.add("translated_scan", second);
                if (!parallelogram) {
                    Run current = run("current_polygon", sample);
                    totals.add("current_polygon", current);
                    row(output, sample, "current_polygon", current);
                    if (first.status.equals("SUCCESS") && current.status.equals("SUCCESS")) {
                        totals.currentRoutePairs++;
                        if (first.blocks.equals(current.blocks)) totals.currentRouteEqual++;
                    }
                }
                if (first.status.equals("SUCCESS") && second.status.equals("SUCCESS")) {
                    totals.paired++;
                    if (first.blocks.equals(second.blocks)) {
                        totals.equal++;
                        if (new ArrayList<>(first.blocks).equals(new ArrayList<>(second.blocks))) totals.sameOrder++;
                    } else if (totals.examples.size() < 3) {
                        totals.examples.add(new Case(sample, first, second, difference(first.blocks, second.blocks), difference(second.blocks, first.blocks)));
                    }
                }
                row(output, sample, firstAlgorithm, first); row(output, sample, "translated_scan", second);
                if ((index + 1) % 100 == 0) {
                    output.flush();
                    writeReport(report, groups, index + 1, sampleCount, System.nanoTime() - started, false);
                    System.out.println("Face comparison " + (index + 1) + "/" + sampleCount);
                }
            }
        }
        writeReport(report, groups, sampleCount, sampleCount, System.nanoTime() - started, true);
        System.out.println("Face raster experiment complete: " + report);
    }

    private static Run run(String algorithm, FaceComparisonSamples.Sample sample) {
        long started = System.nanoTime();
        Budget observer = new Budget(started);
        Set<BlockPos> blocks = Set.of();
        String status;
        ProjectedBresenhamFace.Frame frame = null;
        try {
            frame = ProjectedBresenhamFace.Frame.create(sample.vertices());
            observer.checkCancelled();
            if (algorithm.equals("current_polygon")) {
                blocks = PlanarFaceRasterizer.generate(sample.vertices(), LIMIT, observer, LineTieBias.DEFAULT);
                status = GenerationLimitExceeded.is(blocks) ? "LIMIT_EXCEEDED" : "SUCCESS";
            } else if (algorithm.equals("projected_polygon")) {
                blocks = ProjectedBresenhamFace.raster(sample.vertices()).fill();
                observer.checkCancelled();
                status = blocks.size() > LIMIT ? "LIMIT_EXCEEDED" : "SUCCESS";
            } else {
                var attempt = algorithm.equals("normal_plane")
                        ? QuadFaceRasterizer.attempt(frame, LineTieBias.DEFAULT, LIMIT, observer)
                        : TranslatedScanFaceRasterizer.attempt(frame, LineTieBias.DEFAULT, LIMIT, observer);
                status = attempt.status().name();
                if (attempt.succeeded()) blocks = attempt.result().fill();
            }
        } catch (BudgetExceeded failure) { status = failure.reason; }
        catch (CancellationException failure) { status = "CANCELLED"; }
        catch (RuntimeException failure) { status = "ERROR_" + failure.getClass().getSimpleName(); }
        long nanos = System.nanoTime() - started;
        var quality = status.equals("SUCCESS") ? FaceComparisonQuality.measure(sample.corners(), frame, blocks) : null;
        return new Run(status, blocks, nanos, quality);
    }

    private static final class Budget implements BlockGenerationObserver {
        private final long started; private long scanned;
        Budget(long started) { this.started = started; }
        @Override public void onScanned(long amount) {
            scanned += amount;
            if (scanned > SCAN_LIMIT) throw new BudgetExceeded("SCAN_BUDGET");
            checkCancelled();
        }
        @Override public void checkCancelled() {
            if (System.nanoTime() - started > SAMPLE_NANOS) throw new BudgetExceeded("TIME_BUDGET");
        }
    }
    private static final class BudgetExceeded extends CancellationException {
        final String reason; BudgetExceeded(String reason) { this.reason = reason; }
    }

    private static List<BlockPos> difference(Set<BlockPos> first, Set<BlockPos> second) {
        return first.stream().filter(block -> !second.contains(block)).sorted(java.util.Comparator
                .comparingInt((BlockPos block) -> block.getX()).thenComparingInt(BlockPos::getY).thenComparingInt(BlockPos::getZ)).toList();
    }

    private static void row(BufferedWriter output, FaceComparisonSamples.Sample sample, String algorithm, Run run) throws Exception {
        var q = run.quality;
        output.write(sample.id() + "," + sample.group() + "," + algorithm + "," + run.status + "," + run.nanos + "," + run.blocks.size()
                + (q == null ? ",,,,,," : "," + q.missingBoundaryBlocks() + "," + q.components() + "," + q.holeCells()
                    + "," + q.multiHeightColumns() + "," + q.interiorMultiHeightColumns() + "," + q.gappedColumns()) + "\n");
    }

    private static void writeReport(Path report, Map<String, Totals> groups, int completed, int requested, long nanos, boolean complete) throws Exception {
        StringBuilder text = new StringBuilder("# 面光栅化只读对比实验\n\n");
        text.append("状态：").append(complete ? "已完成" : "进行中").append("；样本 ").append(completed).append('/').append(requested)
                .append("；固定种子 `").append(FaceComparisonSamples.SEED).append("`；总耗时 ").append(milliseconds(nanos)).append(" ms。\n\n");
        text.append("本实验不改路由、不写世界、不删除算法。四边形边长按欧氏距离检查为 1～128 格，长度分档抽样；倾斜平行四边形与非平行四边形各占一半。后者采用随机整数向量u、v构造0/2u/(u+v)/v共面梯形，覆盖任意朝向。样本不按算法是否成功筛选。\n\n");
        text.append("每算法每样本上限 100000 格、250ms、8000000 次扫描；达到实验预算分别记 TIME_BUDGET/SCAN_BUDGET，不算空集合成功。projected 旧接口不支持内部检查，只能前后检查；其250ms不是强制抢占。耗时包含Frame构造，不包含质量统计；P50/P99含失败和预算结果。两算法执行先后交替，暖机12组。本机单进程数据只能用于相对观察。\n\n");
        text.append("质量定义：边界为四条原始 `LineGenerator.path(DEFAULT)` 的并集；缺边统计该集合缺失格数。投影使用现有Frame的同一signed-axis坐标，连通为四邻接；外围补一格后空气四邻接洪泛未触达的空格为闭洞。厚度统计同一法向主轴投影列中超过一格的列，另列出排除边界列的内部厚列；它不是对现有算法的自动判错。现有测试允许连续高度区间，因此同时记录高度区间有缺口的列。\n\n");
        for (var entry : groups.entrySet()) {
            Totals total = entry.getValue();
            text.append("## ").append(entry.getKey()).append("\n\n样本 ").append(total.samples).append("；双方成功 ").append(total.paired)
                    .append("；集合完全相同 ").append(total.equal).append("（成功配对中 ").append(percent(total.equal, total.paired)).append("）；遍历顺序也相同 ").append(total.sameOrder).append("。\n\n");
            text.append("状态计数：`").append(total.statuses).append("`。\n\n");
            if (total.currentRoutePairs > 0) text.append("补充当前 polygon 完整入口与 projected 对照：成功配对 ")
                    .append(total.currentRoutePairs).append("，集合相同 ").append(total.currentRouteEqual).append("（")
                    .append(percent(total.currentRouteEqual, total.currentRoutePairs)).append("）。这是区分原始 translated 不适用与当前实际回退行为的附加证据。\n\n");
            for (var timing : total.times.entrySet()) {
                List<Long> sorted = timing.getValue().stream().sorted().toList();
                text.append("- ").append(timing.getKey()).append("：P50 ").append(milliseconds(percentile(sorted, .50))).append(" ms；P99 ")
                        .append(milliseconds(percentile(sorted, .99))).append(" ms。");
                long[] q = total.quality.get(timing.getKey());
                if (q != null) text.append("缺边总格数 ").append(q[0]).append("（").append(q[1]).append(" 样本）；投影不连通 ").append(q[2])
                        .append("；有闭洞 ").append(q[3]).append("（共").append(q[4]).append("空格）；有多格列 ").append(q[5]).append(" 样本（")
                        .append(q[6]).append("列，内部").append(q[7]).append("列）；有高度缺口 ").append(q[8]).append(" 样本。");
                text.append("\n");
            }
            text.append("\n");
            int example = 0;
            for (Case sample : total.examples) {
                text.append("### 差异案例 ").append(++example).append("（样本 ").append(sample.sample.id()).append("）\n\n角点：`").append(positions(sample.sample.corners())).append("`。\n\n")
                        .append("第一算法/translated_scan 格数：").append(sample.first.blocks.size()).append('/').append(sample.second.blocks.size()).append("；质量：`")
                        .append(sample.first.quality).append("` / `").append(sample.second.quality).append("`。\n\n")
                        .append("仅第一算法（").append(sample.onlyFirst.size()).append("格，最多列32格）：`").append(positions(sample.onlyFirst.stream().limit(32).toList())).append("`。\n\n")
                        .append("仅 translated_scan（").append(sample.onlySecond.size()).append("格，最多列32格）：`").append(positions(sample.onlySecond.stream().limit(32).toList())).append("`。\n\n");
            }
        }
        text.append("## 结论边界\n\n本报告提供选择依据，不决定哪种形状更符合作者意图。未达到预算、算法不适用和没有候选均单独计数；不能把它们算成等价。是否统一两条入口、切换非平行四边形或删除 translated_scan，仍由用户依据差异决定。逐样本原始数据在同目录 `face-raster-comparison.csv`。\n");
        Files.writeString(report, text, StandardCharsets.UTF_8);
    }

    private static long percentile(List<Long> values, double percentile) {
        return values.get(Math.min(values.size() - 1, Math.max(0, (int)Math.ceil(values.size() * percentile) - 1)));
    }
    private static String milliseconds(long nanos) { return String.format(java.util.Locale.ROOT, "%.3f", nanos / 1_000_000.0); }
    private static String percent(int value, int total) { return total == 0 ? "无配对" : String.format(java.util.Locale.ROOT, "%.2f%%", 100.0 * value / total); }
    private static String positions(List<BlockPos> blocks) {
        return blocks.stream().map(p -> "(" + p.getX() + "," + p.getY() + "," + p.getZ() + ")").collect(java.util.stream.Collectors.joining(", "));
    }
}
