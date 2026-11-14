package io.github.fastformer.fastplace.geometry.generation.face.algorithm;

import io.github.fastformer.fastplace.geometry.generation.LineGenerator;
import io.github.fastformer.fastplace.geometry.generation.face.FaceRasterResult;
import io.github.fastformer.fastplace.geometry.generation.face.FaceRequest;
import io.github.fastformer.fastplace.geometry.generation.face.classify.FaceShape;
import java.util.LinkedHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/** Bounded total fallback for degenerate inputs and exhausted algorithm chains. */
public final class EdgeOnlyRaster implements FaceRasterAlgorithm {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("fastformer", "edge_only");

    @Override public ResourceLocation id() { return ID; }
    @Override public boolean accepts(FaceShape shape) { return true; }

    @Override public FaceRasterResult rasterize(FaceShape shape, FaceRequest request) {
        LinkedHashSet<BlockPos> blocks = new LinkedHashSet<>();
        var corners = shape.corners();
        for (int index = 0; index < corners.size(); index++) {
            request.observer().checkCancelled();
            BlockPos first = corners.get(index);
            BlockPos second = corners.get((index + 1) % corners.size());
            if (LineGenerator.estimateBlocks(first, second) > request.maxBlocks()) return new FaceRasterResult.LimitExceeded();
            for (BlockPos block : LineGenerator.path(first, second, request.tieBias())) {
                request.observer().checkCancelled();
                blocks.add(block);
                if (blocks.size() > request.maxBlocks()) return new FaceRasterResult.LimitExceeded();
            }
        }
        return new FaceRasterResult.Filled(blocks);
    }
}
