package io.github.fastformer.common.geometry.raster.face.algorithm;

import io.github.fastformer.common.geometry.raster.face.FaceRasterResult;
import io.github.fastformer.common.geometry.raster.face.FaceRequest;
import io.github.fastformer.common.geometry.raster.face.classify.FaceClass;
import io.github.fastformer.common.geometry.raster.face.classify.FaceShape;
import java.util.LinkedHashSet;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/** Two bounded loops; no boundary scan, plane fitting or height-envelope search. */
public final class AxisRectangleRaster implements FaceRasterAlgorithm {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("fastformer", "axis_rectangle");
    private final FastFaceFrame.Factory frames;

    public AxisRectangleRaster(FastFaceFrame.Factory frames) { this.frames = Objects.requireNonNull(frames, "frames"); }
    @Override public ResourceLocation id() { return ID; }
    @Override public boolean accepts(FaceShape shape) { return shape.kind() == FaceClass.AXIS_RECTANGLE; }

    @Override public FaceRasterResult rasterize(FaceShape shape, FaceRequest request) {
        request.observer().checkCancelled();
        if (!accepts(shape)) return new FaceRasterResult.NotApplicable();
        FastFaceFrame frame = frames.create(request);
        if (frame == null) return new FaceRasterResult.NotApplicable();
        var local = frame.corners().stream().map(frame::project).toList();
        int minU = local.stream().mapToInt(FastFaceFrame.Cell::u).min().orElseThrow();
        int maxU = local.stream().mapToInt(FastFaceFrame.Cell::u).max().orElseThrow();
        int minV = local.stream().mapToInt(FastFaceFrame.Cell::v).min().orElseThrow();
        int maxV = local.stream().mapToInt(FastFaceFrame.Cell::v).max().orElseThrow();
        long width = (long)maxU - minU + 1;
        long height = (long)maxV - minV + 1;
        if (width > request.maxBlocks() || height > request.maxBlocks() || width * height > request.maxBlocks()) {
            return new FaceRasterResult.LimitExceeded();
        }
        int level = local.getFirst().height();
        LinkedHashSet<BlockPos> blocks = new LinkedHashSet<>();
        for (long v = minV; v <= maxV; v++) for (long u = minU; u <= maxU; u++) {
            request.observer().checkCancelled();
            request.observer().onScanned(1);
            blocks.add(frame.restore((int)u, (int)v, level));
        }
        return new FaceRasterResult.Filled(blocks);
    }
}
