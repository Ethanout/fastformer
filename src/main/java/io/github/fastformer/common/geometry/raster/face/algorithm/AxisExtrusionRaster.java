package io.github.fastformer.common.geometry.raster.face.algorithm;

import io.github.fastformer.common.geometry.raster.line.LinePath;
import io.github.fastformer.common.geometry.raster.face.FaceRasterResult;
import io.github.fastformer.common.geometry.raster.face.FaceRequest;
import io.github.fastformer.common.geometry.raster.face.classify.FaceClass;
import io.github.fastformer.common.geometry.raster.face.classify.FaceShape;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/** Extrudes a perpendicular Bresenham profile; sheared profiles retain the normal-plane fallback. */
public final class AxisExtrusionRaster implements FaceRasterAlgorithm {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("fastformer", "axis_extrusion");
    private final FastFaceFrame.Factory frames;

    public AxisExtrusionRaster(FastFaceFrame.Factory frames) { this.frames = Objects.requireNonNull(frames, "frames"); }
    @Override public ResourceLocation id() { return ID; }
    @Override public boolean accepts(FaceShape shape) { return shape.kind() == FaceClass.AXIS_EXTRUSION; }

    @Override public FaceRasterResult rasterize(FaceShape shape, FaceRequest request) {
        request.observer().checkCancelled();
        if (!accepts(shape)) return new FaceRasterResult.NotApplicable();
        FastFaceFrame frame = frames.create(request);
        if (frame == null) return new FaceRasterResult.NotApplicable();
        var corners = frame.corners();
        BlockPos first = corners.getFirst();
        int axial = axial(corners.get(1).subtract(first)) ? 1 : 3;
        BlockPos delta = corners.get(axial).subtract(first);
        int length = Math.max(Math.abs(delta.getX()), Math.max(Math.abs(delta.getY()), Math.abs(delta.getZ())));
        BlockPos step = new BlockPos(Integer.signum(delta.getX()), Integer.signum(delta.getY()), Integer.signum(delta.getZ()));
        BlockPos other = corners.get(axial == 1 ? 3 : 1);
        BlockPos profile = other.subtract(first);
        if ((long)profile.getX() * step.getX() + (long)profile.getY() * step.getY()
                + (long)profile.getZ() * step.getZ() != 0) return new FaceRasterResult.NotApplicable();
        if ((long)length + 1 > request.maxBlocks() || LinePath.estimateBlocks(first, other) > request.maxBlocks()) {
            return new FaceRasterResult.LimitExceeded();
        }
        HashSet<BlockPos> blocks = new HashSet<>();
        for (BlockPos block : LinePath.path(first, other, request.tieBias(), request.maxBlocks(), request.observer())) {
            for (int offset = 0; offset <= length; offset++) {
                request.observer().checkCancelled();
                request.observer().onScanned(1);
                blocks.add(block.offset(step.getX() * offset, step.getY() * offset, step.getZ() * offset));
                if (blocks.size() > request.maxBlocks()) return new FaceRasterResult.LimitExceeded();
            }
        }
        ArrayList<BlockPos> ordered = new ArrayList<>(blocks);
        ordered.sort(Comparator.comparingInt((BlockPos block) -> frame.project(block).v())
                .thenComparingInt(block -> frame.project(block).u()).thenComparingInt(block -> frame.project(block).height()));
        return new FaceRasterResult.Filled(new LinkedHashSet<>(ordered));
    }

    private static boolean axial(BlockPos delta) {
        return (delta.getX() != 0 ? 1 : 0) + (delta.getY() != 0 ? 1 : 0) + (delta.getZ() != 0 ? 1 : 0) == 1;
    }
}
