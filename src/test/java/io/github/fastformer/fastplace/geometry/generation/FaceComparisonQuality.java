package io.github.fastformer.fastplace.geometry.generation;

import io.github.fastformer.common.geometry.LineTieBias;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;

/** Uses the same canonical signed-axis projection and four-neighbor hole definition as existing face tests. */
final class FaceComparisonQuality {
    record Quality(int missingBoundaryBlocks, int components, int holeCells, int multiHeightColumns,
                   int interiorMultiHeightColumns, int gappedColumns) {}
    private record Column(int u, int v) {}

    private FaceComparisonQuality() {}

    static Quality measure(List<BlockPos> corners, ProjectedBresenhamFace.Frame frame, Set<BlockPos> blocks) {
        Set<BlockPos> boundary = new HashSet<>();
        for (int index = 0; index < corners.size(); index++) {
            boundary.addAll(LineGenerator.path(corners.get(index), corners.get((index + 1) % corners.size()), LineTieBias.DEFAULT));
        }
        int missing = (int)boundary.stream().filter(block -> !blocks.contains(block)).count();
        if (frame == null) return new Quality(missing, 0, 0, 0, 0, 0);
        Map<Column, Set<Integer>> columns = new HashMap<>();
        for (BlockPos block : blocks) {
            columns.computeIfAbsent(project(frame, block), ignored -> new HashSet<>()).add(height(frame, block));
        }
        Set<Column> edgeColumns = new HashSet<>();
        boundary.forEach(block -> edgeColumns.add(project(frame, block)));
        int multi = 0;
        int interior = 0;
        int gaps = 0;
        for (Map.Entry<Column, Set<Integer>> entry : columns.entrySet()) {
            Set<Integer> heights = entry.getValue();
            if (heights.size() > 1) { multi++; if (!edgeColumns.contains(entry.getKey())) interior++; }
            int minimum = heights.stream().mapToInt(Integer::intValue).min().orElseThrow();
            int maximum = heights.stream().mapToInt(Integer::intValue).max().orElseThrow();
            if (maximum - minimum + 1 != heights.size()) gaps++;
        }
        return new Quality(missing, components(columns.keySet()), holes(columns.keySet()), multi, interior, gaps);
    }

    private static Column project(ProjectedBresenhamFace.Frame frame, BlockPos block) {
        long[] delta = {(long)block.getX() - frame.anchor().getX(), (long)block.getY() - frame.anchor().getY(),
                (long)block.getZ() - frame.anchor().getZ()};
        return new Column(Math.toIntExact(delta[frame.order()[0]] * frame.signs()[0]),
                Math.toIntExact(delta[frame.order()[1]] * frame.signs()[1]));
    }

    private static int height(ProjectedBresenhamFace.Frame frame, BlockPos block) {
        long[] delta = {(long)block.getX() - frame.anchor().getX(), (long)block.getY() - frame.anchor().getY(),
                (long)block.getZ() - frame.anchor().getZ()};
        return Math.toIntExact(delta[frame.order()[2]] * frame.signs()[2]);
    }

    private static int components(Set<Column> domain) {
        Set<Column> unseen = new HashSet<>(domain);
        int count = 0;
        while (!unseen.isEmpty()) {
            count++;
            ArrayDeque<Column> open = new ArrayDeque<>();
            Column first = unseen.iterator().next();
            unseen.remove(first);
            open.add(first);
            while (!open.isEmpty()) for (Column neighbor : neighbors(open.removeFirst())) {
                if (unseen.remove(neighbor)) open.addLast(neighbor);
            }
        }
        return count;
    }

    private static int holes(Set<Column> domain) {
        if (domain.isEmpty()) return 0;
        int minimumU = domain.stream().mapToInt(Column::u).min().orElseThrow() - 1;
        int maximumU = domain.stream().mapToInt(Column::u).max().orElseThrow() + 1;
        int minimumV = domain.stream().mapToInt(Column::v).min().orElseThrow() - 1;
        int maximumV = domain.stream().mapToInt(Column::v).max().orElseThrow() + 1;
        Set<Column> exterior = new HashSet<>();
        ArrayDeque<Column> open = new ArrayDeque<>();
        Column first = new Column(minimumU, minimumV);
        open.add(first);
        exterior.add(first);
        while (!open.isEmpty()) for (Column next : neighbors(open.removeFirst())) {
            if (next.u >= minimumU && next.u <= maximumU && next.v >= minimumV && next.v <= maximumV
                    && !domain.contains(next) && exterior.add(next)) open.addLast(next);
        }
        int holes = 0;
        for (int u = minimumU; u <= maximumU; u++) for (int v = minimumV; v <= maximumV; v++) {
            Column cell = new Column(u, v);
            if (!domain.contains(cell) && !exterior.contains(cell)) holes++;
        }
        return holes;
    }

    private static List<Column> neighbors(Column column) {
        return List.of(new Column(column.u + 1, column.v), new Column(column.u - 1, column.v),
                new Column(column.u, column.v + 1), new Column(column.u, column.v - 1));
    }
}
