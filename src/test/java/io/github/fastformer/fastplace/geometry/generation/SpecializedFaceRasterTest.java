package io.github.fastformer.fastplace.geometry.generation;
import io.github.fastformer.common.geometry.raster.BlockGenerationObserver;

import io.github.fastformer.common.geometry.LineTieBias;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.common.geometry.model.FillMode;
import io.github.fastformer.common.geometry.raster.face.FaceRasterResult;
import io.github.fastformer.common.geometry.raster.face.FaceRequest;
import io.github.fastformer.common.geometry.raster.face.algorithm.AxisExtrusionRaster;
import io.github.fastformer.common.geometry.raster.face.algorithm.AxisRectangleRaster;
import io.github.fastformer.common.geometry.raster.face.algorithm.CoordinatePlaneRaster;
import io.github.fastformer.common.geometry.raster.face.algorithm.FaceRasterAlgorithm;
import io.github.fastformer.common.geometry.raster.face.classify.FaceClass;
import io.github.fastformer.common.geometry.raster.face.classify.FaceClassifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class SpecializedFaceRasterTest {
    private static final int LIMIT = 20_000;
    private static final long SEED = 202611140207L;

    @Test void axisRectanglesMatchNormalIncludingIterationOrder() { assertSamples(FaceClass.AXIS_RECTANGLE); }
    @Test void coordinatePlanesMatchNormalIncludingIterationOrder() { assertSamples(FaceClass.COORDINATE_PLANE); }
    @Test void axisExtrusionsMatchNormalIncludingIterationOrder() { assertSamples(FaceClass.AXIS_EXTRUSION); }

    @Test void shearedExtrusionsDeclineInsteadOfChangingNormalShape() {
        Random random = new Random(SEED);
        for (int index = 0; index < 100; index++) {
            var vertices = new ArrayList<>(sample(FaceClass.AXIS_EXTRUSION, random, 0));
            Vec3 offset = new Vec3(3, 0, 0);
            vertices.set(2, vertices.get(2).add(offset)); vertices.set(3, vertices.get(3).add(offset));
            assertInstanceOf(FaceRasterResult.NotApplicable.class, algorithm(FaceClass.AXIS_EXTRUSION).rasterize(
                    FaceClassifier.classify(vertices), request(vertices, LineTieBias.DEFAULT, LIMIT, null)));
        }
    }

    @Test void exactBudgetAndCancellationApplyToEverySpecialization() {
        for (FaceClass kind : List.of(FaceClass.AXIS_RECTANGLE, FaceClass.COORDINATE_PLANE, FaceClass.AXIS_EXTRUSION)) {
            var vertices = sample(kind, new Random(SEED), 0);
            var algorithm = algorithm(kind);
            var shape = FaceClassifier.classify(vertices);
            int count = filled(algorithm.rasterize(shape, request(vertices, LineTieBias.DEFAULT, LIMIT, null))).size();
            assertEquals(count, filled(algorithm.rasterize(shape, request(vertices, LineTieBias.DEFAULT, count, null))).size());
            assertInstanceOf(FaceRasterResult.LimitExceeded.class,
                    algorithm.rasterize(shape, request(vertices, LineTieBias.DEFAULT, count - 1, null)));
            assertInstanceOf(FaceRasterResult.LimitExceeded.class,
                    algorithm.rasterize(shape, request(vertices, LineTieBias.DEFAULT, 0, null)));
            AtomicInteger checks = new AtomicInteger();
            var observer = new BlockGenerationObserver() {
                @Override public void checkCancelled() {
                    if (checks.incrementAndGet() >= 4) throw new CancellationException("test cancellation");
                }
                @Override public void onGenerated(BlockPos block) { fail("candidate must not publish final output"); }
            };
            assertThrows(CancellationException.class, () -> algorithm.rasterize(shape,
                    request(vertices, LineTieBias.DEFAULT, LIMIT, observer)));
        }
    }

    @Test void worldBoundaryTranslationsAndVertexOwnershipMatchNormal() {
        for (FaceClass kind : List.of(FaceClass.AXIS_RECTANGLE, FaceClass.COORDINATE_PLANE, FaceClass.AXIS_EXTRUSION)) {
            for (int sign : new int[]{-1, 1}) for (double fraction : new double[]{0.0, 0.999999}) {
                var vertices = sample(kind, new Random(SEED + 7), 0).stream()
                        .map(point -> point.add(sign * 29_999_000.0 + fraction - 0.5, -230.0 + fraction - 0.5,
                                -sign * 29_999_000.0 + fraction - 0.5)).toList();
                for (LineTieBias tie : LineTieBias.values()) assertMatch(kind, vertices, tie);
            }
        }
    }

    private static void assertSamples(FaceClass kind) {
        Random random = new Random(SEED + kind.ordinal());
        for (int index = 0; index < 1000; index++) {
            var vertices = sample(kind, random, index);
            for (LineTieBias tie : LineTieBias.values()) assertMatch(kind, vertices, tie);
        }
    }

    private static void assertMatch(FaceClass kind, List<Vec3> vertices, LineTieBias tie) {
        var request = request(vertices, tie, LIMIT, null);
        var shape = FaceClassifier.classify(vertices);
        assertEquals(kind, shape.kind());
        var normal = QuadFaceRasterizer.attempt(ProjectedBresenhamFace.Frame.create(vertices), tie, LIMIT, null);
        assertEquals(BresenhamFaceSweep.Status.SUCCESS, normal.status());
        assertEquals(new ArrayList<>(normal.result().fill()), filled(algorithm(kind).rasterize(shape, request)),
                () -> kind + " " + tie + " vertices=" + vertices);
    }

    private static List<BlockPos> filled(FaceRasterResult result) {
        return new ArrayList<>(assertInstanceOf(FaceRasterResult.Filled.class, result).blocks());
    }

    private static FaceRequest request(List<Vec3> vertices, LineTieBias tie, int limit, BlockGenerationObserver observer) {
        return new FaceRequest(vertices, FillMode.SOLID, tie, limit, observer);
    }

    private static FaceRasterAlgorithm algorithm(FaceClass kind) {
        return switch (kind) {
            case AXIS_RECTANGLE -> new AxisRectangleRaster(FaceRasterAdapters::canonicalFrame);
            case COORDINATE_PLANE -> new CoordinatePlaneRaster(FaceRasterAdapters::canonicalFrame);
            case AXIS_EXTRUSION -> new AxisExtrusionRaster(FaceRasterAdapters::canonicalFrame);
            default -> throw new IllegalArgumentException(kind.toString());
        };
    }

    private static List<Vec3> sample(FaceClass kind, Random random, int index) {
        int axis = index % 3;
        int[] first = new int[3];
        int[] second = new int[3];
        if (kind == FaceClass.AXIS_RECTANGLE) {
            first[axis] = nonzero(random); second[(axis + 1) % 3] = nonzero(random);
        } else if (kind == FaceClass.COORDINATE_PLANE) {
            do {
                first[(axis + 1) % 3] = nonzero(random); first[(axis + 2) % 3] = nonzero(random);
                second[(axis + 1) % 3] = nonzero(random); second[(axis + 2) % 3] = nonzero(random);
            } while (first[(axis + 1) % 3] * second[(axis + 2) % 3] == first[(axis + 2) % 3] * second[(axis + 1) % 3]);
        } else {
            first[axis] = nonzero(random);
            for (int component = 0; component < 3; component++) second[component] = nonzero(random);
            second[axis] = 0;
        }
        BlockPos origin = new BlockPos(random.nextInt(101) - 50, random.nextInt(101) - 50, random.nextInt(101) - 50);
        BlockPos a = new BlockPos(first[0], first[1], first[2]);
        BlockPos b = new BlockPos(second[0], second[1], second[2]);
        ArrayList<Vec3> result = new ArrayList<>(List.of(Vec3.atCenterOf(origin), Vec3.atCenterOf(origin.offset(a)),
                Vec3.atCenterOf(origin.offset(a).offset(b)), Vec3.atCenterOf(origin.offset(b))));
        Collections.rotate(result, index % 4);
        if ((index & 4) != 0) Collections.reverse(result);
        return List.copyOf(result);
    }

    private static int nonzero(Random random) { int magnitude = 1 + random.nextInt(12); return random.nextBoolean() ? magnitude : -magnitude; }
}
