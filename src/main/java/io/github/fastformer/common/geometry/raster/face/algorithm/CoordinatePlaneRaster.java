package io.github.fastformer.common.geometry.raster.face.algorithm;

import io.github.fastformer.common.geometry.raster.line.LinePath;
import io.github.fastformer.common.geometry.raster.face.FaceRasterResult;
import io.github.fastformer.common.geometry.raster.face.FaceRequest;
import io.github.fastformer.common.geometry.raster.face.classify.FaceClass;
import io.github.fastformer.common.geometry.raster.face.classify.FaceShape;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/** Integer-plane boundary scan preserving the existing normal raster's row ownership and diagonal bridges. */
public final class CoordinatePlaneRaster implements FaceRasterAlgorithm {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("fastformer", "coordinate_plane");
    private final FastFaceFrame.Factory frames;

    public CoordinatePlaneRaster(FastFaceFrame.Factory frames) { this.frames = Objects.requireNonNull(frames, "frames"); }
    @Override public ResourceLocation id() { return ID; }
    @Override public boolean accepts(FaceShape shape) { return shape.kind() == FaceClass.COORDINATE_PLANE; }

    @Override public FaceRasterResult rasterize(FaceShape shape, FaceRequest request) {
        request.observer().checkCancelled();
        if (!accepts(shape)) return new FaceRasterResult.NotApplicable();
        FastFaceFrame frame = frames.create(request);
        if (frame == null) return new FaceRasterResult.NotApplicable();
        var corners = frame.corners();
        TreeMap<Integer, int[]> rows = new TreeMap<>();
        for (int index : new int[]{1, 3}) {
            if (LinePath.estimateBlocks(corners.getFirst(), corners.get(index)) > request.maxBlocks()) {
                return new FaceRasterResult.LimitExceeded();
            }
            BlockPos offset = corners.get(index == 1 ? 3 : 1).subtract(corners.getFirst());
            for (BlockPos block : LinePath.path(corners.getFirst(), corners.get(index), request.tieBias(), request.maxBlocks(), request.observer())) {
                request.observer().checkCancelled();
                include(rows, frame.project(block));
                include(rows, frame.project(block.offset(offset)));
            }
        }
        connectDiagonalRows(frame, rows, request);
        int level = frame.project(corners.getFirst()).height();
        LinkedHashSet<BlockPos> blocks = new LinkedHashSet<>();
        for (Map.Entry<Integer, int[]> entry : rows.entrySet()) {
            for (long u = entry.getValue()[0]; u <= entry.getValue()[1]; u++) {
                request.observer().checkCancelled();
                request.observer().onScanned(1);
                if (blocks.size() >= request.maxBlocks()) return new FaceRasterResult.LimitExceeded();
                blocks.add(frame.restore((int)u, entry.getKey(), level));
            }
        }
        return new FaceRasterResult.Filled(blocks);
    }

    private static void include(Map<Integer, int[]> rows, FastFaceFrame.Cell cell) {
        rows.compute(cell.v(), (ignored, span) -> {
            if (span == null) return new int[]{cell.u(), cell.u()};
            span[0] = Math.min(span[0], cell.u()); span[1] = Math.max(span[1], cell.u());
            return span;
        });
    }

    private static void connectDiagonalRows(FastFaceFrame frame, NavigableMap<Integer, int[]> rows, FaceRequest request) {
        Map.Entry<Integer, int[]> previous = null;
        for (Map.Entry<Integer, int[]> current : rows.entrySet()) {
            request.observer().checkCancelled();
            if (previous != null && (long)previous.getKey() + 1 == current.getKey()) {
                int[] a = previous.getValue(); int[] b = current.getValue();
                if ((long)a[1] + 1 == b[0]) bridge(frame, previous.getKey(), a, current.getKey(), b, a[1], b[0]);
                else if ((long)b[1] + 1 == a[0]) bridge(frame, previous.getKey(), a, current.getKey(), b, a[0], b[1]);
            }
            previous = current;
        }
    }

    private static void bridge(FastFaceFrame frame, int av, int[] a, int bv, int[] b, int au, int bu) {
        boolean first = distance(frame, bu, av) <= distance(frame, au, bv);
        int u = first ? bu : au; int[] row = first ? a : b;
        row[0] = Math.min(row[0], u); row[1] = Math.max(row[1], u);
    }

    private static double distance(FastFaceFrame frame, int u, int v) {
        long sumU = 0, sumV = 0;
        for (BlockPos corner : frame.corners()) {
            FastFaceFrame.Cell cell = frame.project(corner); sumU += cell.u(); sumV += cell.v();
        }
        long du = 2L * u * frame.corners().size() - 2L * sumU;
        long dv = 2L * v * frame.corners().size() - 2L * sumV;
        return (double)du * du + (double)dv * dv;
    }
}
