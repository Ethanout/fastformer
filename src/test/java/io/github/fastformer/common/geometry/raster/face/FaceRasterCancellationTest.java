package io.github.fastformer.common.geometry.raster.face;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.common.geometry.model.FillMode;
import io.github.fastformer.common.geometry.raster.BlockGenerationObserver;
import io.github.fastformer.common.geometry.LineTieBias;
import io.github.fastformer.common.geometry.raster.face.algorithm.FaceRasterAlgorithm;
import io.github.fastformer.common.geometry.raster.face.algorithm.FaceRasterAlgorithms;
import io.github.fastformer.common.geometry.raster.face.classify.FaceClass;
import io.github.fastformer.common.geometry.raster.face.classify.FaceShape;
import io.github.fastformer.common.geometry.raster.face.policy.FaceRasterPolicies;
import io.github.fastformer.common.geometry.raster.face.policy.FaceRasterPolicy;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class FaceRasterCancellationTest {
    @Test void cancellationRaisedAfterCandidatePreventsNextCandidateAndFallback() {
        AtomicBoolean cancelled = new AtomicBoolean();
        List<String> calls = new ArrayList<>();
        FaceRasterAlgorithms algorithms = new FaceRasterAlgorithms()
                .register(algorithm("first", (shape, request) -> {
                    calls.add("first"); cancelled.set(true); return new FaceRasterResult.NoValidCandidate();
                }))
                .register(algorithm("second", (shape, request) -> { calls.add("second"); return new FaceRasterResult.Filled(Set.of()); }));
        assertThrows(CancellationException.class, () -> dispatcher(algorithms, "first", "second").rasterize(request(cancelled)));
        assertEquals(List.of("first"), calls);
    }

    @Test void cancelledCandidateCannotPublishItsFilledResult() {
        AtomicBoolean cancelled = new AtomicBoolean();
        FaceRasterAlgorithms algorithms = new FaceRasterAlgorithms().register(algorithm("filled", (shape, request) -> {
            cancelled.set(true); return new FaceRasterResult.Filled(Set.of(BlockPos.ZERO));
        }));
        assertThrows(CancellationException.class, () -> dispatcher(algorithms, "filled").rasterize(request(cancelled)));
    }

    @Test void cancellationBeforeOutlinePreventsItsCallback() {
        AtomicBoolean cancelled = new AtomicBoolean(true);
        FaceRasterAlgorithms algorithms = new FaceRasterAlgorithms().register(algorithm("unused", (shape, request) -> fail()));
        var dispatcher = new FaceRasterDispatcher(algorithms, policy("unused"), ChainExhaustion.EDGE_ONLY,
                (shape, request) -> fail("outline must not run"), (id, shape) -> fail());
        var fill = request(cancelled);
        assertThrows(CancellationException.class, () -> dispatcher.rasterize(new FaceRequest(fill.vertices(), FillMode.OUTLINE,
                fill.tieBias(), fill.maxBlocks(), fill.observer())));
    }

    private static FaceRasterDispatcher dispatcher(FaceRasterAlgorithms algorithms, String... chain) {
        return new FaceRasterDispatcher(algorithms, policy(chain), ChainExhaustion.EDGE_ONLY,
                (shape, request) -> fail(), (id, shape) -> {});
    }

    private static FaceRasterPolicy policy(String... names) {
        EnumMap<FaceClass, List<ResourceLocation>> chains = new EnumMap<>(FaceClass.class);
        var ids = java.util.Arrays.stream(names).map(FaceRasterPolicies::id).toList();
        for (FaceClass kind : FaceClass.values()) chains.put(kind, ids);
        return new FaceRasterPolicy(chains);
    }

    private static FaceRasterAlgorithm algorithm(String name, FaceRasterDispatcher.Outline action) {
        return new FaceRasterAlgorithm() {
            @Override public ResourceLocation id() { return FaceRasterPolicies.id(name); }
            @Override public boolean accepts(FaceShape shape) { return true; }
            @Override public FaceRasterResult rasterize(FaceShape shape, FaceRequest request) { return action.rasterize(shape, request); }
        };
    }

    private static FaceRequest request(AtomicBoolean cancelled) {
        return new FaceRequest(List.of(new Vec3(0.5, 0.5, 0.5), new Vec3(2.5, 0.5, 0.5),
                new Vec3(2.5, 0.5, 2.5), new Vec3(0.5, 0.5, 2.5)), FillMode.SOLID, LineTieBias.DEFAULT, 100,
                new BlockGenerationObserver() {
                    @Override public void checkCancelled() { if (cancelled.get()) throw new CancellationException("cancelled"); }
                    @Override public void onGenerated(BlockPos position) { fail("cancelled work must not publish blocks"); }
                });
    }
}
