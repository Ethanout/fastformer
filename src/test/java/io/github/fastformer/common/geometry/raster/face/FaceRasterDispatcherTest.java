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
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class FaceRasterDispatcherTest {
    @Test void skipsInapplicableAndFailedCandidatesButStopsAtFirstFilledResult() {
        List<String> calls = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        var registry = new FaceRasterAlgorithms()
                .register(algorithm("skip", false, new FaceRasterResult.Filled(Set.of()), calls))
                .register(algorithm("reject", true, new FaceRasterResult.NotApplicable(), calls))
                .register(algorithm("failed", true, new FaceRasterResult.NoValidCandidate(), calls))
                .register(algorithm("ok", true, new FaceRasterResult.Filled(Set.of(BlockPos.ZERO)), calls))
                .register(algorithm("after", true, new FaceRasterResult.NoValidCandidate(), calls)).freeze();
        var dispatcher = new FaceRasterDispatcher(registry, policy("skip", "reject", "failed", "ok", "after"),
                ChainExhaustion.EDGE_ONLY,
                (shape, request) -> fail("Fill must not query outline"), (id, shape) -> failures.add(id.getPath()));
        assertEquals(new FaceRasterResult.Filled(Set.of(BlockPos.ZERO)), dispatcher.rasterize(request(FillMode.SOLID, 10, null)));
        assertEquals(List.of("reject", "failed", "ok"), calls);
        assertEquals(List.of("failed"), failures);
    }

    @Test void limitsNeverFallThroughToAnotherAlgorithm() {
        List<String> calls = new ArrayList<>();
        var registry = new FaceRasterAlgorithms().register(algorithm("limit", true, new FaceRasterResult.LimitExceeded(), calls))
                .register(algorithm("after", true, new FaceRasterResult.Filled(Set.of()), calls));
        var dispatcher = new FaceRasterDispatcher(registry, policy("limit", "after"),
                ChainExhaustion.EDGE_ONLY,
                (shape, request) -> fail(), (id, shape) -> fail());
        assertInstanceOf(FaceRasterResult.LimitExceeded.class, dispatcher.rasterize(request(FillMode.HOLLOW, 1, null)));
        assertEquals(List.of("limit"), calls);
    }

    @Test void outlineBypassesFillChainAndReportsGeneratedPositionsOnlyOnce() {
        List<String> calls = new ArrayList<>();
        AtomicInteger generated = new AtomicInteger();
        var registry = new FaceRasterAlgorithms().register(algorithm("unused", true, new FaceRasterResult.NoValidCandidate(), calls));
        var dispatcher = new FaceRasterDispatcher(registry, policy("unused"),
                ChainExhaustion.EDGE_ONLY,
                (shape, request) -> new FaceRasterResult.Filled(Set.of(BlockPos.ZERO)), (id, shape) -> fail());
        dispatcher.rasterize(request(FillMode.OUTLINE, 1, new BlockGenerationObserver() {
            @Override public void onGenerated(BlockPos block) { generated.incrementAndGet(); }
        }));
        assertTrue(calls.isEmpty());
        assertEquals(1, generated.get());
    }

    @Test void fallbackIsBoundedAndCancellationPropagates() {
        List<String> calls = new ArrayList<>();
        var registry = new FaceRasterAlgorithms().register(algorithm("reject", true, new FaceRasterResult.NotApplicable(), calls));
        var dispatcher = new FaceRasterDispatcher(registry, policy("reject"), ChainExhaustion.EDGE_ONLY,
                (shape, request) -> fail(), (id, shape) -> fail());
        assertInstanceOf(FaceRasterResult.Filled.class, dispatcher.rasterize(request(FillMode.SOLID, 100, null)));
        assertInstanceOf(FaceRasterResult.LimitExceeded.class, dispatcher.rasterize(request(FillMode.SOLID, 1, null)));
        assertThrows(CancellationException.class, () -> dispatcher.rasterize(request(FillMode.SOLID, 100, new BlockGenerationObserver() {
            @Override public void checkCancelled() { throw new CancellationException(); }
        })));
    }

    @Test void invalidPoliciesFailBeforeAnyRasterWork() {
        assertThrows(IllegalArgumentException.class, () -> new FaceRasterPolicy(java.util.Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new FaceRasterDispatcher(new FaceRasterAlgorithms(),
                policy("missing"), ChainExhaustion.EDGE_ONLY, (shape, request) -> fail(), (id, shape) -> fail()));
    }

    @Test void strictExhaustionReturnsNotApplicableWithoutPublishingSafetyEdges() {
        List<String> calls = new ArrayList<>();
        var registry = new FaceRasterAlgorithms()
                .register(algorithm("skip", false, new FaceRasterResult.Filled(Set.of()), calls))
                .register(algorithm("reject", true, new FaceRasterResult.NotApplicable(), calls)).freeze();
        var dispatcher = new FaceRasterDispatcher(registry, policy("skip", "reject"), ChainExhaustion.RETURN_FAILURE,
                (shape, request) -> fail(), (id, shape) -> fail());
        AtomicInteger generated = new AtomicInteger();
        var result = dispatcher.rasterize(request(FillMode.SOLID, 100, new BlockGenerationObserver() {
            @Override public void onGenerated(BlockPos position) { generated.incrementAndGet(); }
        }));
        assertInstanceOf(FaceRasterResult.NotApplicable.class, result);
        assertEquals(List.of("reject"), calls);
        assertEquals(0, generated.get());
    }

    @Test void strictExhaustionKeepsFailedCandidateEvenWhenLaterAlgorithmsAreInapplicable() {
        List<String> calls = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        var registry = new FaceRasterAlgorithms()
                .register(algorithm("failed", true, new FaceRasterResult.NoValidCandidate(), calls))
                .register(algorithm("reject", true, new FaceRasterResult.NotApplicable(), calls)).freeze();
        var dispatcher = new FaceRasterDispatcher(registry, policy("failed", "reject"), ChainExhaustion.RETURN_FAILURE,
                (shape, request) -> fail(), (id, shape) -> failures.add(id.getPath()));
        assertInstanceOf(FaceRasterResult.NoValidCandidate.class, dispatcher.rasterize(request(FillMode.SOLID, 100, null)));
        assertEquals(List.of("failed", "reject"), calls);
        assertEquals(List.of("failed"), failures);
    }

    @Test void strictLimitStopsTheChainBeforeAValidLaterCandidate() {
        List<String> calls = new ArrayList<>();
        var registry = new FaceRasterAlgorithms()
                .register(algorithm("limit", true, new FaceRasterResult.LimitExceeded(), calls))
                .register(algorithm("after", true, new FaceRasterResult.Filled(Set.of(BlockPos.ZERO)), calls)).freeze();
        var dispatcher = new FaceRasterDispatcher(registry, policy("limit", "after"), ChainExhaustion.RETURN_FAILURE,
                (shape, request) -> fail(), (id, shape) -> fail());
        assertInstanceOf(FaceRasterResult.LimitExceeded.class, dispatcher.rasterize(request(FillMode.SOLID, 1, null)));
        assertEquals(List.of("limit"), calls);
    }

    private static FaceRasterAlgorithm algorithm(String name, boolean accepts, FaceRasterResult result, List<String> calls) {
        return new FaceRasterAlgorithm() {
            @Override public ResourceLocation id() { return FaceRasterPolicies.id(name); }
            @Override public boolean accepts(FaceShape shape) { return accepts; }
            @Override public FaceRasterResult rasterize(FaceShape shape, FaceRequest request) { calls.add(name); return result; }
        };
    }

    private static FaceRasterPolicy policy(String... names) {
        EnumMap<FaceClass, List<ResourceLocation>> chains = new EnumMap<>(FaceClass.class);
        List<ResourceLocation> ids = java.util.Arrays.stream(names).map(FaceRasterPolicies::id).toList();
        for (FaceClass kind : FaceClass.values()) chains.put(kind, ids);
        return new FaceRasterPolicy(chains);
    }

    private static FaceRequest request(FillMode fill, int limit, BlockGenerationObserver observer) {
        return new FaceRequest(List.of(Vec3.atCenterOf(BlockPos.ZERO), new Vec3(3.5, 0.5, 0.5),
                new Vec3(3.5, 0.5, 3.5), new Vec3(0.5, 0.5, 3.5)), fill, LineTieBias.DEFAULT, limit, observer);
    }
}
