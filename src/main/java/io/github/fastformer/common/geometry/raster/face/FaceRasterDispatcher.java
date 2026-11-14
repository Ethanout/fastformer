package io.github.fastformer.common.geometry.raster.face;

import io.github.fastformer.common.geometry.model.FillMode;
import io.github.fastformer.common.geometry.raster.face.algorithm.EdgeOnlyRaster;
import io.github.fastformer.common.geometry.raster.face.algorithm.FaceRasterAlgorithms;
import io.github.fastformer.common.geometry.raster.face.classify.FaceClassifier;
import io.github.fastformer.common.geometry.raster.face.classify.FaceShape;
import io.github.fastformer.common.geometry.raster.face.policy.FaceRasterPolicy;
import java.util.Objects;
import java.util.function.BiConsumer;
import net.minecraft.resources.ResourceLocation;

/** Classifies once and executes one immutable chain without choosing a process-wide policy. */
public final class FaceRasterDispatcher {
    @FunctionalInterface
    public interface Outline { FaceRasterResult rasterize(FaceShape shape, FaceRequest request); }

    private final FaceRasterAlgorithms algorithms;
    private final FaceRasterPolicy policy;
    private final ChainExhaustion exhaustion;
    private final Outline outline;
    private final BiConsumer<ResourceLocation, FaceShape> failedCandidate;
    private final EdgeOnlyRaster fallback = new EdgeOnlyRaster();

    public FaceRasterDispatcher(FaceRasterAlgorithms algorithms, FaceRasterPolicy policy, ChainExhaustion exhaustion, Outline outline,
                                BiConsumer<ResourceLocation, FaceShape> failedCandidate) {
        this.algorithms = Objects.requireNonNull(algorithms, "algorithms");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.exhaustion = Objects.requireNonNull(exhaustion, "exhaustion");
        this.outline = Objects.requireNonNull(outline, "outline");
        this.failedCandidate = Objects.requireNonNull(failedCandidate, "failedCandidate");
        policy.validate(algorithms);
    }

    public FaceRasterResult rasterize(FaceRequest request) {
        request.observer().checkCancelled();
        FaceShape shape = FaceClassifier.classify(request.vertices());
        FaceRasterResult result = request.fill() == FillMode.OUTLINE ? outline.rasterize(shape, request) : fill(shape, request);
        if (!(result instanceof FaceRasterResult.Filled filled)) return result;
        if (filled.blocks().size() > request.maxBlocks()) return new FaceRasterResult.LimitExceeded();
        request.observer().checkCancelled();
        for (var block : filled.blocks()) {
            request.observer().checkCancelled();
            request.observer().onGenerated(block);
        }
        return filled;
    }

    private FaceRasterResult fill(FaceShape shape, FaceRequest request) {
        FaceRasterResult failure = new FaceRasterResult.NotApplicable();
        for (ResourceLocation id : policy.chain(shape.kind())) {
            request.observer().checkCancelled();
            var algorithm = algorithms.require(id);
            if (exhaustion == ChainExhaustion.RETURN_FAILURE && !algorithm.supportsStrictSurface()) continue;
            if (!algorithm.accepts(shape)) continue;
            FaceRasterResult result = algorithm.rasterize(shape, request);
            if (result instanceof FaceRasterResult.Filled || result instanceof FaceRasterResult.LimitExceeded) return result;
            if (result instanceof FaceRasterResult.NoValidCandidate) {
                failure = result;
                failedCandidate.accept(id, shape);
            }
        }
        return exhaustion == ChainExhaustion.EDGE_ONLY ? fallback.rasterize(shape, request) : failure;
    }
}
