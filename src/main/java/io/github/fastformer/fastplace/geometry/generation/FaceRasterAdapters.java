package io.github.fastformer.fastplace.geometry.generation;
import io.github.fastformer.common.geometry.raster.BlockGenerationObserver;

import io.github.fastformer.common.geometry.raster.face.FaceRasterDispatcher;
import io.github.fastformer.common.geometry.raster.face.ChainExhaustion;
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
import io.github.fastformer.common.geometry.raster.face.policy.FaceRasterPolicy;
import io.github.fastformer.common.geometry.raster.face.policy.FacePolicyObserver;
import java.util.Set;
import java.util.List;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/** Bridges each production entrance to its original algorithms and outline ownership. */
public final class FaceRasterAdapters {
    private static final FaceRasterAlgorithms ALGORITHMS = createAlgorithms();
    private static final FaceRasterDispatcher QUAD = currentQuad(FaceRasterPolicies.CURRENT_QUAD);
    private static final FaceRasterDispatcher POLYGON = currentPolygon(FaceRasterPolicies.CURRENT_POLYGON);
    private static final FaceRasterDispatcher STRICT_QUAD = new FaceRasterDispatcher(
            ALGORITHMS, FaceRasterPolicies.CURRENT_VOLUME_FACE, ChainExhaustion.RETURN_FAILURE,
            (shape, request) -> quadOutline(request), (id, shape) -> {});

    private FaceRasterAdapters() {}

    public static FaceRasterAlgorithms currentAlgorithms() {
        return ALGORITHMS;
    }

    private static FaceRasterAlgorithms createAlgorithms() {
        return new FaceRasterAlgorithms()
                .register(new AxisRectangleRaster(FaceRasterAdapters::canonicalFrame))
                .register(new CoordinatePlaneRaster(FaceRasterAdapters::canonicalFrame))
                .register(new AxisExtrusionRaster(FaceRasterAdapters::canonicalFrame))
                .register(adapter(FaceRasterPolicies.QUAD_ALGORITHM, request -> fromBlocks(
                        PlanarFaceRasterizer.generateInterpolatedQuad(request.vertices(), request.maxBlocks(),
                                scanningObserver(request), request.tieBias()))))
                .register(adapter(FaceRasterPolicies.POLYGON_ALGORITHM, request -> fromBlocks(
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
        return QUAD;
    }

    /** The caller owns this immutable policy; no process-wide current policy is changed. */
    public static FaceRasterDispatcher currentQuad(FaceRasterPolicy policy) {
        return new FaceRasterDispatcher(ALGORITHMS, policy, ChainExhaustion.EDGE_ONLY,
                (shape, request) -> fromBlocks(PlanarFaceRasterizer.interpolatedQuadOutline(
                        request.vertices(), request.maxBlocks(), scanningObserver(request), request.tieBias())), (id, shape) -> {});
    }

    public static FaceRasterDispatcher currentPolygon() {
        return POLYGON;
    }

    /** Polygon outlines keep their own route even when a task supplies another fill policy. */
    public static FaceRasterDispatcher currentPolygon(FaceRasterPolicy policy) {
        return new FaceRasterDispatcher(ALGORITHMS, policy, ChainExhaustion.EDGE_ONLY,
                (shape, request) -> fromBlocks(PlanarFaceRasterizer.outline(
                        request.vertices(), request.maxBlocks(), scanningObserver(request), request.tieBias())), (id, shape) -> {});
    }

    static Set<BlockPos> generatedBlocks(FaceRasterResult result, int maxBlocks, BlockGenerationObserver observer) {
        if (result instanceof FaceRasterResult.Filled filled) return filled.blocks();
        if (result instanceof FaceRasterResult.LimitExceeded) return GenerationLimitExceeded.witness(maxBlocks, observer);
        throw new IllegalStateException("Face dispatcher returned an unresolved result: " + result);
    }

    /** A failed volume face is a constraint failure, not permission to fill another shape. */
    public static FaceRasterDispatcher strictQuad() {
        return STRICT_QUAD;
    }

    public static FaceRasterDispatcher strictQuad(FaceRasterPolicy policy) {
        if (policy == FaceRasterPolicies.CURRENT_VOLUME_FACE) return STRICT_QUAD;
        return new FaceRasterDispatcher(ALGORITHMS, policy, ChainExhaustion.RETURN_FAILURE,
                (shape, request) -> quadOutline(request), (id, shape) -> {});
    }

    private static FaceRasterResult quadOutline(FaceRequest request) {
        var attempt = QuadFaceRasterizer.attempt(
                ProjectedBresenhamFace.Frame.create(request.vertices(), request.observer()),
                request.tieBias(), request.maxBlocks(), scanningObserver(request));
        return attempt.succeeded() ? new FaceRasterResult.Filled(attempt.result().outline()) : fromAttempt(attempt);
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
            @Override public boolean supportsStrictSurface() {
                return !id.equals(FaceRasterPolicies.QUAD_ALGORITHM) && !id.equals(FaceRasterPolicies.POLYGON_ALGORITHM);
            }
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
        return FacePolicyObserver.forward(request.observer(), new BlockGenerationObserver() {
            @Override public void onScanned(long amount) { request.observer().onScanned(amount); }
            @Override public void checkCancelled() { request.observer().checkCancelled(); }
        });
    }
}
