package io.github.fastformer.common.geometry.generation.volume;

import io.github.fastformer.common.geometry.generation.FastPlaceGeometry;
import io.github.fastformer.common.geometry.generation.source.*;
import io.github.fastformer.common.geometry.generation.face.*;
import io.github.fastformer.common.geometry.generation.face.entry.*;
import io.github.fastformer.common.geometry.generation.shape.*;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.common.geometry.LineTieBias;
import io.github.fastformer.common.geometry.model.FillMode;
import io.github.fastformer.common.geometry.raster.BlockGenerationObserver;
import io.github.fastformer.common.geometry.raster.face.ChainExhaustion;
import io.github.fastformer.common.geometry.raster.face.FaceRasterDispatcher;
import io.github.fastformer.common.geometry.raster.face.FaceRasterResult;
import io.github.fastformer.common.geometry.raster.face.FaceRequest;
import io.github.fastformer.common.geometry.raster.face.algorithm.FaceRasterAlgorithm;
import io.github.fastformer.common.geometry.raster.face.algorithm.FaceRasterAlgorithms;
import io.github.fastformer.common.geometry.raster.face.classify.FaceShape;
import io.github.fastformer.common.geometry.raster.face.policy.FaceRasterPolicies;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class StrictVolumeFaceRasterTest {
    private static final int LIMIT = 100_000;
    private static final List<List<Vec3>> BASES = List.of(
            parallelogram(Vec3.ZERO, new Vec3(4, 0, 0), new Vec3(0, 0, 3)),
            parallelogram(new Vec3(-3, 2, 1), new Vec3(3, 0, 1), new Vec3(-1, 0, 3)),
            parallelogram(new Vec3(1, -2, 3), new Vec3(4, 1, 1), new Vec3(1, 3, 2)));
    private static final Vec3 EXTRUSION = new Vec3(0, 4, -1);

    @Test void strictProductionFaceMatchesRawAlgorithmStatusCoordinatesAndOrder() {
        List<List<Vec3>> faces = new ArrayList<>(BASES);
        faces.add(List.of(Vec3.ZERO, new Vec3(4, 0, 0), new Vec3(3, 0, 4), new Vec3(0, 0, 3)));
        faces.add(List.of(Vec3.ZERO, new Vec3(1, 0, 0), new Vec3(2, 0, 0), new Vec3(3, 0, 0)));
        for (List<Vec3> face : faces) for (LineTieBias tie : LineTieBias.values()) {
            for (int limit : List.of(1, 8, LIMIT)) {
                FaceRequest request = new FaceRequest(face, FillMode.SOLID, tie, limit, null);
                FaceRasterResult expected = rawFace(request);
                FaceRasterResult actual = FaceRasterAdapters.strictQuad().rasterize(request);
                assertEquals(expected.getClass(), actual.getClass(), face + " / " + tie + " / " + limit);
                if (expected instanceof FaceRasterResult.Filled filled) {
                    assertEquals(new ArrayList<>(filled.blocks()),
                            new ArrayList<>(((FaceRasterResult.Filled) actual).blocks()));
                }
            }
        }
    }

    @Test void sixFaceAndVolumeTraversalMatchTheOriginalRawAlgorithmForSolidAndBoundary() {
        for (List<Vec3> base : BASES) for (LineTieBias tie : LineTieBias.values()) {
            List<List<Vec3>> seen = new ArrayList<>();
            FaceRasterDispatcher reference = dispatcher(request -> {
                seen.add(request.vertices());
                return rawFace(request);
            });
            for (boolean boundary : List.of(false, true)) {
                seen.clear();
                var expected = generate(base, EXTRUSION, LIMIT, null, tie, boundary, reference);
                RecordingObserver observer = new RecordingObserver();
                var actual = boundary
                        ? BresenhamColumnVolume.generateBoundary(base, EXTRUSION, LIMIT, observer, tie)
                        : BresenhamColumnVolume.generate(base, EXTRUSION, LIMIT, observer, tie);
                assertVolumeResult(expected, actual);
                assertEquals(boxFaces(base, EXTRUSION), seen);
                assertTrue(actual.complete(), base + " / " + tie + " / boundary=" + boundary);
                assertEquals(new ArrayList<>(actual.blocks()), observer.generated);
                int exactLimit = actual.blocks().size();
                for (int limit : List.of(1, exactLimit - 1, exactLimit)) {
                    var limitedExpected = generate(base, EXTRUSION, limit, null, tie, boundary, reference);
                    var limitedActual = boundary
                            ? BresenhamColumnVolume.generateBoundary(base, EXTRUSION, limit, null, tie)
                            : BresenhamColumnVolume.generate(base, EXTRUSION, limit, null, tie);
                    assertVolumeResult(limitedExpected, limitedActual);
                }
            }
        }
    }

    @Test void laterFaceFailureRetiresTheWholeVolumeWithoutPublishingAnyPrefix() {
        for (FaceRasterResult failure : List.of(new FaceRasterResult.NotApplicable(),
                new FaceRasterResult.NoValidCandidate(), new FaceRasterResult.LimitExceeded())) {
            for (boolean boundary : List.of(false, true)) {
                AtomicInteger calls = new AtomicInteger();
                RecordingObserver observer = new RecordingObserver();
                FaceRasterDispatcher failing = dispatcher(request -> calls.incrementAndGet() == 4 ? failure : rawFace(request));
                var result = generate(BASES.getFirst(), EXTRUSION, LIMIT, observer, LineTieBias.DEFAULT, boundary, failing);
                assertFalse(result.complete());
                assertEquals(4, calls.get(), "later faces must not run after failure");
                assertTrue(result.shell().isEmpty());
                assertTrue(observer.generated.isEmpty(), "staged faces must never publish a partial volume");
                if (failure instanceof FaceRasterResult.LimitExceeded) {
                    assertTrue(GenerationLimitExceeded.is(result.blocks()));
                    assertFalse(GenerationFailed.is(result.blocks()));
                } else {
                    assertTrue(GenerationFailed.is(result.blocks()));
                    assertFalse(GenerationLimitExceeded.is(result.blocks()));
                }
            }
        }
    }

    @Test void workCapsStillStopBeforeDispatchOrPublishingAnyFace() {
        List<List<Vec3>> bases = List.of(
                parallelogram(Vec3.ZERO, new Vec3(1_000, 0, 0), new Vec3(0, 0, 1_000)),
                parallelogram(Vec3.ZERO, new Vec3(1, 0, 0), new Vec3(0, 1, 0)));
        List<Vec3> extrusions = List.of(new Vec3(0, 1, 0), new Vec3(500_000, 500_000, 500_000));
        for (int index = 0; index < bases.size(); index++) for (boolean boundary : List.of(false, true)) {
            RecordingObserver observer = new RecordingObserver();
            FaceRasterDispatcher unused = dispatcher(request -> fail("preflight must stop before face dispatch"));
            var result = generate(bases.get(index), extrusions.get(index), 16, observer,
                    LineTieBias.DEFAULT, boundary, unused);
            assertFalse(result.complete());
            assertTrue(GenerationLimitExceeded.is(result.blocks()));
            assertTrue(observer.generated.isEmpty());
            assertEquals(0L, observer.scanned);
        }
    }

    @Test void cancellationDuringALaterFacePropagatesWithoutPublishingEarlierFaces() {
        RecordingObserver observer = new RecordingObserver();
        AtomicInteger calls = new AtomicInteger();
        FaceRasterDispatcher cancelled = dispatcher(request -> {
            if (calls.incrementAndGet() == 4) throw new CancellationException();
            return rawFace(request);
        });
        assertThrows(CancellationException.class, () -> BresenhamColumnVolume.generate(
                BASES.getFirst(), EXTRUSION, LIMIT, observer, LineTieBias.DEFAULT, cancelled));
        assertEquals(4, calls.get());
        assertTrue(observer.generated.isEmpty());
    }

    private static FaceRasterDispatcher dispatcher(Function<FaceRequest, FaceRasterResult> rasterize) {
        FaceRasterAlgorithms algorithms = new FaceRasterAlgorithms().register(new FaceRasterAlgorithm() {
            @Override public ResourceLocation id() { return FaceRasterPolicies.id("normal_plane"); }
            @Override public boolean accepts(FaceShape shape) { return true; }
            @Override public FaceRasterResult rasterize(FaceShape shape, FaceRequest request) { return rasterize.apply(request); }
        }).freeze();
        return new FaceRasterDispatcher(algorithms, FaceRasterPolicies.CURRENT_VOLUME_FACE, ChainExhaustion.RETURN_FAILURE,
                (shape, request) -> fail("volume faces must not request an outline"), (id, shape) -> {});
    }

    private static FaceRasterResult rawFace(FaceRequest request) {
        var attempt = FaceRasterFixtures.attempt(ProjectedBresenhamFace.Frame.create(request.vertices()),
                request.tieBias(), request.maxBlocks(), request.observer());
        return switch (attempt.status()) {
            case SUCCESS -> new FaceRasterResult.Filled(attempt.result().fill());
            case LIMIT_EXCEEDED -> new FaceRasterResult.LimitExceeded();
            case NOT_APPLICABLE -> new FaceRasterResult.NotApplicable();
            case NO_VALID_CANDIDATE -> new FaceRasterResult.NoValidCandidate();
        };
    }

    private static BresenhamColumnVolume.Result generate(List<Vec3> base, Vec3 extrusion, int limit,
            BlockGenerationObserver observer, LineTieBias tie, boolean boundary, FaceRasterDispatcher dispatcher) {
        return boundary ? BresenhamColumnVolume.generateBoundary(base, extrusion, limit, observer, tie, dispatcher)
                : BresenhamColumnVolume.generate(base, extrusion, limit, observer, tie, dispatcher);
    }

    private static void assertVolumeResult(BresenhamColumnVolume.Result expected, BresenhamColumnVolume.Result actual) {
        assertEquals(expected.complete(), actual.complete());
        assertEquals(GenerationFailed.is(expected.blocks()), GenerationFailed.is(actual.blocks()));
        assertEquals(GenerationLimitExceeded.is(expected.blocks()), GenerationLimitExceeded.is(actual.blocks()));
        if (expected.complete()) {
            assertEquals(new ArrayList<>(expected.blocks()), new ArrayList<>(actual.blocks()));
            assertEquals(new ArrayList<>(expected.shell()), new ArrayList<>(actual.shell()));
        } else {
            assertEquals(expected.blocks().size(), actual.blocks().size());
            assertEquals(new ArrayList<>(expected.shell()), new ArrayList<>(actual.shell()));
        }
    }

    private static List<Vec3> parallelogram(Vec3 origin, Vec3 first, Vec3 second) {
        Vec3 center = origin.add(0.5, 0.5, 0.5);
        return List.of(center, center.add(first), center.add(first).add(second), center.add(second));
    }

    private static List<List<Vec3>> boxFaces(List<Vec3> base, Vec3 extrusion) {
        List<Vec3> top = base.stream().map(vertex -> vertex.add(extrusion)).toList();
        List<List<Vec3>> faces = new ArrayList<>();
        faces.add(base);
        faces.add(top);
        for (int index = 0; index < 4; index++) {
            int next = (index + 1) % 4;
            faces.add(List.of(base.get(index), base.get(next), top.get(next), top.get(index)));
        }
        return faces;
    }

    private static final class RecordingObserver implements BlockGenerationObserver {
        private final List<BlockPos> generated = new ArrayList<>();
        private long scanned;
        @Override public void onScanned(long amount) { scanned += amount; }
        @Override public void onGenerated(BlockPos block) { generated.add(block); }
    }
}
