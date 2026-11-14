package io.github.fastformer.fastplace.geometry.generation;
import io.github.fastformer.common.geometry.raster.BlockGenerationObserver;

import io.github.fastformer.common.geometry.raster.face.FaceRasterDispatcher;
import io.github.fastformer.common.geometry.raster.face.FaceRasterResult;
import io.github.fastformer.common.geometry.raster.face.FaceRequest;
import io.github.fastformer.common.geometry.raster.face.algorithm.EdgeOnlyRaster;
import io.github.fastformer.common.geometry.raster.face.algorithm.AxisRectangleRaster;
import io.github.fastformer.common.geometry.raster.face.algorithm.AxisExtrusionRaster;
import io.github.fastformer.common.geometry.raster.face.algorithm.CoordinatePlaneRaster;
import io.github.fastformer.common.geometry.raster.face.algorithm.FastFaceFrame;
import io.github.fastformer.common.geometry.raster.face.algorithm.FaceRasterAlgorithm;
import io.github.fastformer.common.geometry.raster.face.algorithm.FaceRasterAlgorithms;
import io.github.fastformer.common.geometry.raster.face.classify.FaceShape;
import io.github.fastformer.common.geometry.raster.face.policy.FaceRasterPolicies;
import java.util.Set;
import java.util.List;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/** Package bridge to existing non-public algorithms; this adds no call-site or default-shape change. */
public final class FaceRasterAdapters {
    private FaceRasterAdapters() {}

    public static FaceRasterAlgorithms currentAlgorithms() {
        return new FaceRasterAlgorithms()
                .register(new AxisRectangleRaster(FaceRasterAdapters::canonicalFrame))
                .register(new CoordinatePlaneRaster(FaceRasterAdapters::canonicalFrame))
                .register(new AxisExtrusionRaster(FaceRasterAdapters::canonicalFrame))
                .register(adapter(FaceRasterPolicies.LEGACY_QUAD, request -> fromBlocks(
                        PlanarFaceRasterizer.generateInterpolatedQuad(request.vertices(), request.maxBlocks(),
                                scanningObserver(request), request.tieBias()))))
                .register(adapter(FaceRasterPolicies.LEGACY_POLYGON, request -> fromBlocks(
                        PlanarFaceRasterizer.generate(request.vertices(), request.maxBlocks(),
                                scanningObserver(request), request.tieBias()))))
                .register(adapter(FaceRasterPolicies.id("normal_plane"), request -> fromAttempt(QuadFaceRasterizer.attempt(
                        ProjectedBresenhamFace.Frame.create(request.vertices(), request.observer()), request.tieBias(), request.maxBlocks(), scanningObserver(request)))))
                .register(adapter(FaceRasterPolicies.id("translated_scan"), request -> fromAttempt(TranslatedScanFaceRasterizer.attempt(
                        ProjectedBresenhamFace.Frame.create(request.vertices(), request.observer()), request.tieBias(), request.maxBlocks(), scanningObserver(request)))))
                .register(adapter(FaceRasterPolicies.id("projected_polygon"), FaceRasterAdapters::projected))
                .register(new EdgeOnlyRaster()).freeze();
    }

    static FastFaceFrame canonicalFrame(FaceRequest request) {
        var frame = ProjectedBresenhamFace.Frame.create(request.vertices(), request.observer());
        if (frame == null) return null;
        List<BlockPos> corners = frame.vertices().stream().map(frame::restore).toList();
        return new FastFaceFrame() {
            @Override public List<BlockPos> corners() { return corners; }
            @Override public Cell project(BlockPos block) {
                long[] delta = {(long)block.getX() - frame.anchor().getX(),
                        (long)block.getY() - frame.anchor().getY(), (long)block.getZ() - frame.anchor().getZ()};
                return new Cell(Math.toIntExact(delta[frame.order()[0]] * frame.signs()[0]),
                        Math.toIntExact(delta[frame.order()[1]] * frame.signs()[1]),
                        Math.toIntExact(delta[frame.order()[2]] * frame.signs()[2]));
            }
            @Override public BlockPos restore(int u, int v, int height) {
                return frame.restore(new ProjectedBresenhamFace.Int3(u, v, height));
            }
        };
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
        var result = ProjectedBresenhamFace.raster(request.vertices(), request.maxBlocks(), request.observer());
        request.observer().checkCancelled();
        return result.limitExceeded() ? new FaceRasterResult.LimitExceeded()
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
