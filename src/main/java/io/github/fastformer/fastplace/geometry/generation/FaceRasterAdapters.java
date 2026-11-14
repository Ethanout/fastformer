package io.github.fastformer.fastplace.geometry.generation;

import io.github.fastformer.fastplace.geometry.generation.face.FaceRasterDispatcher;
import io.github.fastformer.fastplace.geometry.generation.face.FaceRasterResult;
import io.github.fastformer.fastplace.geometry.generation.face.FaceRequest;
import io.github.fastformer.fastplace.geometry.generation.face.algorithm.EdgeOnlyRaster;
import io.github.fastformer.fastplace.geometry.generation.face.algorithm.FaceRasterAlgorithm;
import io.github.fastformer.fastplace.geometry.generation.face.algorithm.FaceRasterAlgorithms;
import io.github.fastformer.fastplace.geometry.generation.face.classify.FaceShape;
import io.github.fastformer.fastplace.geometry.generation.face.policy.FaceRasterPolicies;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/** Package bridge to existing non-public algorithms; this adds no call-site or default-shape change. */
public final class FaceRasterAdapters {
    private FaceRasterAdapters() {}

    public static FaceRasterAlgorithms currentAlgorithms() {
        return new FaceRasterAlgorithms()
                .register(adapter(FaceRasterPolicies.LEGACY_QUAD, request -> fromBlocks(
                        PlanarFaceRasterizer.generateInterpolatedQuad(request.vertices(), request.maxBlocks(),
                                scanningObserver(request), request.tieBias()))))
                .register(adapter(FaceRasterPolicies.LEGACY_POLYGON, request -> fromBlocks(
                        PlanarFaceRasterizer.generate(request.vertices(), request.maxBlocks(),
                                scanningObserver(request), request.tieBias()))))
                .register(adapter(FaceRasterPolicies.id("normal_plane"), request -> fromAttempt(QuadFaceRasterizer.attempt(
                        ProjectedBresenhamFace.Frame.create(request.vertices()), request.tieBias(), request.maxBlocks(), scanningObserver(request)))))
                .register(adapter(FaceRasterPolicies.id("translated_scan"), request -> fromAttempt(TranslatedScanFaceRasterizer.attempt(
                        ProjectedBresenhamFace.Frame.create(request.vertices()), request.tieBias(), request.maxBlocks(), scanningObserver(request)))))
                .register(adapter(FaceRasterPolicies.id("projected_polygon"), FaceRasterAdapters::projected))
                .register(new EdgeOnlyRaster()).freeze();
    }

    public static FaceRasterDispatcher currentQuad() {
        return new FaceRasterDispatcher(currentAlgorithms(), FaceRasterPolicies.CURRENT_QUAD,
                (shape, request) -> fromBlocks(PlanarFaceRasterizer.interpolatedQuadOutline(
                        request.vertices(), request.maxBlocks(), scanningObserver(request), request.tieBias())), (id, shape) -> {});
    }

    public static FaceRasterDispatcher currentPolygon() {
        return new FaceRasterDispatcher(currentAlgorithms(), FaceRasterPolicies.CURRENT_POLYGON,
                (shape, request) -> fromBlocks(PlanarFaceRasterizer.outline(
                        request.vertices(), request.maxBlocks(), scanningObserver(request), request.tieBias())), (id, shape) -> {});
    }

    private static FaceRasterResult projected(FaceRequest request) {
        request.observer().checkCancelled();
        var result = ProjectedBresenhamFace.raster(request.vertices());
        request.observer().checkCancelled();
        return result.fill().size() > request.maxBlocks() ? new FaceRasterResult.LimitExceeded()
                : new FaceRasterResult.Filled(result.fill());
    }

    private static FaceRasterAlgorithm adapter(ResourceLocation id, Function<FaceRequest, FaceRasterResult> function) {
        return new FaceRasterAlgorithm() {
            @Override public ResourceLocation id() { return id; }
            @Override public boolean accepts(FaceShape shape) { return true; }
            @Override public FaceRasterResult rasterize(FaceShape shape, FaceRequest request) {
                request.observer().checkCancelled();
                return function.apply(request);
            }
        };
    }

    private static FaceRasterResult fromBlocks(Set<BlockPos> blocks) {
        return GenerationLimitExceeded.is(blocks) ? new FaceRasterResult.LimitExceeded() : new FaceRasterResult.Filled(blocks);
    }

    private static FaceRasterResult fromAttempt(BresenhamFaceSweep.Attempt attempt) {
        return switch (attempt.status()) {
            case SUCCESS -> new FaceRasterResult.Filled(attempt.result().fill());
            case LIMIT_EXCEEDED -> new FaceRasterResult.LimitExceeded();
            case NOT_APPLICABLE -> new FaceRasterResult.NotApplicable();
            case NO_VALID_CANDIDATE -> new FaceRasterResult.NoValidCandidate();
        };
    }

    /** Only final dispatcher output is generated; rejected candidates must not report accepted blocks. */
    private static BlockGenerationObserver scanningObserver(FaceRequest request) {
        return new BlockGenerationObserver() {
            @Override public void onScanned(long amount) { request.observer().onScanned(amount); }
            @Override public void checkCancelled() { request.observer().checkCancelled(); }
        };
    }
}
