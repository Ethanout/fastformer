package io.github.fastformer.fastplace.geometry.generation;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.common.geometry.LineTieBias;
import io.github.fastformer.common.geometry.model.FillMode;
import io.github.fastformer.common.geometry.raster.BlockGenerationObserver;
import io.github.fastformer.common.geometry.raster.face.classify.FaceClass;
import io.github.fastformer.common.geometry.raster.face.classify.FaceClassifier;
import io.github.fastformer.common.geometry.raster.face.policy.FaceRasterPolicies;
import io.github.fastformer.common.geometry.raster.face.policy.FaceRasterPolicy;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/** The oracle deliberately calls the pre-dispatch algorithms, including each entrance's own fallback. */
class ProductionFaceRasterTest {
    private static final int LIMIT = 10_000;
    private static final List<List<Vec3>> FACES = List.of(
            quad(new BlockPos(4, 0, 0), new BlockPos(0, 0, 3)),
            quad(new BlockPos(4, 0, 1), new BlockPos(-1, 0, 3)),
            quad(new BlockPos(4, 0, 0), new BlockPos(1, 2, 3)),
            quad(new BlockPos(4, 1, 1), new BlockPos(1, 3, 2)),
            centers(new BlockPos(0, 0, 0), new BlockPos(4, 0, 0), new BlockPos(3, 0, 4), new BlockPos(0, 0, 3)),
            centers(new BlockPos(0, 0, 0), new BlockPos(4, 0, 0), new BlockPos(1, 0, 3)),
            centers(new BlockPos(0, 0, 0), new BlockPos(4, 0, 0), new BlockPos(4, 0, 4),
                    new BlockPos(2, 0, 2), new BlockPos(0, 0, 4)),
            centers(new BlockPos(0, 0, 0), new BlockPos(1, 0, 0), new BlockPos(2, 0, 0), new BlockPos(3, 0, 0)),
            centers(BlockPos.ZERO, BlockPos.ZERO, new BlockPos(3, 0, 2), new BlockPos(0, 0, 2)),
            List.of(new Vec3(-2.1, -0.1, -3.2), new Vec3(1.9, -0.1, -2.2),
                    new Vec3(0.9, -0.1, 0.8), new Vec3(-3.1, -0.1, -0.2)),
            centers(new BlockPos(-41, -96, -253), new BlockPos(-43, -97, -253),
                    new BlockPos(-57, -90, -264), new BlockPos(-55, -89, -264)));

    @Test void productionQuadMatchesOriginalCoordinatesOrderOutlineAndLimits() {
        for (List<Vec3> face : FACES) {
            if (face.size() != 4) continue;
            for (LineTieBias tie : LineTieBias.values()) for (FillMode fill : FillMode.values()) {
                for (int limit : List.of(1, 4, LIMIT)) {
                    assertSameBlocksAndOrder(legacyQuad(face, fill, limit, tie),
                            QuadFaceGenerator.generate(face, fill, limit, null, tie),
                            face + " / " + fill + " / " + tie + " / " + limit);
                }
            }
        }
    }

    @Test void productionPolygonMatchesOriginalCoordinatesOrderOutlineFallbackAndLimits() {
        for (List<Vec3> face : FACES) for (FillMode fill : FillMode.values()) {
            for (int limit : List.of(1, 4, LIMIT)) {
                assertSameBlocksAndOrder(legacyPolygon(face, fill, limit),
                        PolygonFaceGenerator.generate(face, fill, limit, null), face + " / " + fill + " / " + limit);
            }
        }
    }

    @Test void sampleCoversEveryClassifierWithoutChangingEntranceIdentity() {
        Set<FaceClass> kinds = new java.util.HashSet<>();
        for (List<Vec3> face : FACES) kinds.add(FaceClassifier.classify(face).kind());
        assertEquals(Set.of(FaceClass.values()), kinds);
        List<Vec3> tilted = FACES.getLast();
        assertNotEquals(new ArrayList<>(legacyQuad(tilted, FillMode.SOLID, LIMIT, LineTieBias.DEFAULT)),
                new ArrayList<>(legacyPolygon(tilted, FillMode.SOLID, LIMIT)));
    }

    @Test void compoundOutlinesKeepEachOriginalFaceRouteAndTraversalOrder() {
        Vec3 extrusion = new Vec3(1, 3, -2);
        for (List<Vec3> face : FACES.subList(0, 5)) {
            assertSameBlocksAndOrder(legacyPrismOutline(face, extrusion),
                    PrismGenerator.generatePolygon(face, extrusion, FillMode.OUTLINE, LIMIT), "polygon prism " + face);
            for (LineTieBias tie : LineTieBias.values()) {
                assertSameBlocksAndOrder(legacyTiltedBoxOutline(face, extrusion, tie),
                        TiltedBoxGenerator.generate(face, extrusion, FillMode.OUTLINE, LIMIT, null, tie),
                        "quad prism " + face + " / " + tie);
            }
        }
    }

    @Test void taskPolicyChangesOnlyThatInvocationAndNeverSelectsAnotherOutlineRoute() {
        List<Vec3> face = FACES.getFirst();
        FaceRasterPolicy edges = edgePolicy();
        Set<BlockPos> defaultQuad = QuadFaceGenerator.generate(face, FillMode.SOLID, LIMIT);
        Set<BlockPos> customQuad = QuadFaceGenerator.generate(face, FillMode.SOLID, LIMIT, null, LineTieBias.DEFAULT, edges);
        Set<BlockPos> defaultPolygon = PolygonFaceGenerator.generate(face, FillMode.SOLID, LIMIT);
        Set<BlockPos> customPolygon = PolygonFaceGenerator.generate(face, FillMode.SOLID, LIMIT, null, edges);
        assertTrue(customQuad.size() < defaultQuad.size());
        assertTrue(customPolygon.size() < defaultPolygon.size());
        assertSameBlocksAndOrder(defaultQuad, QuadFaceGenerator.generate(face, FillMode.SOLID, LIMIT), "quad default remains");
        assertSameBlocksAndOrder(defaultPolygon, PolygonFaceGenerator.generate(face, FillMode.SOLID, LIMIT), "polygon default remains");
        assertSameBlocksAndOrder(legacyQuad(face, FillMode.OUTLINE, LIMIT, LineTieBias.DEFAULT),
                QuadFaceGenerator.generate(face, FillMode.OUTLINE, LIMIT, null, LineTieBias.DEFAULT, edges), "quad outline");
        assertSameBlocksAndOrder(legacyPolygon(face, FillMode.OUTLINE, LIMIT),
                PolygonFaceGenerator.generate(face, FillMode.OUTLINE, LIMIT, null, edges), "polygon outline");
    }

    @Test void productionObserversSeeFinalCoordinatesOnlyAndCancellationStillPropagates() {
        List<BlockPos> observed = new ArrayList<>();
        BlockGenerationObserver observer = new BlockGenerationObserver() {
            @Override public void onGenerated(BlockPos block) { observed.add(block); }
        };
        Set<BlockPos> quad = QuadFaceGenerator.generate(FACES.get(3), FillMode.SOLID, LIMIT, observer);
        assertEquals(new ArrayList<>(quad), observed);
        observed.clear();
        Set<BlockPos> polygon = PolygonFaceGenerator.generate(FACES.get(3), FillMode.SOLID, LIMIT, observer);
        assertEquals(new ArrayList<>(polygon), observed);
        observed.clear();
        assertTrue(GenerationLimitExceeded.is(QuadFaceGenerator.generate(FACES.getFirst(), FillMode.SOLID, 1, observer)));
        assertTrue(observed.isEmpty());
        assertTrue(GenerationLimitExceeded.is(PolygonFaceGenerator.generate(FACES.getFirst(), FillMode.SOLID, 1, observer)));
        assertTrue(observed.isEmpty());
        BlockGenerationObserver cancelled = new BlockGenerationObserver() {
            @Override public void checkCancelled() { throw new CancellationException(); }
        };
        assertThrows(CancellationException.class, () -> QuadFaceGenerator.generate(FACES.getFirst(), FillMode.SOLID, LIMIT, cancelled));
        assertThrows(CancellationException.class, () -> PolygonFaceGenerator.generate(FACES.getFirst(), FillMode.OUTLINE, LIMIT, cancelled));
    }

    @Test void invalidVertexCountsAndNonpositiveBudgetsKeepTheOriginalEmptyResult() {
        assertTrue(QuadFaceGenerator.generate(FACES.get(5), FillMode.SOLID, LIMIT).isEmpty());
        assertTrue(PolygonFaceGenerator.generate(List.of(Vec3.ZERO, new Vec3(1, 0, 0)), FillMode.SOLID, LIMIT).isEmpty());
        for (int limit : List.of(0, -1)) {
            assertTrue(QuadFaceGenerator.generate(FACES.getFirst(), FillMode.OUTLINE, limit).isEmpty());
            assertTrue(PolygonFaceGenerator.generate(FACES.getFirst(), FillMode.SOLID, limit).isEmpty());
        }
    }

    private static Set<BlockPos> legacyQuad(List<Vec3> face, FillMode fill, int limit, LineTieBias tie) {
        if (fill == FillMode.OUTLINE) return PlanarFaceRasterizer.interpolatedQuadOutline(face, limit, null, tie);
        Set<BlockPos> result = PlanarFaceRasterizer.generateInterpolatedQuad(face, limit, null, tie);
        if (!result.isEmpty()) return result;
        return new LinkedHashSet<>(PlanarFaceGeometry.outline(face, limit, tie));
    }

    private static Set<BlockPos> legacyPolygon(List<Vec3> face, FillMode fill, int limit) {
        if (fill == FillMode.OUTLINE) return PlanarFaceRasterizer.outline(face, limit, null);
        Set<BlockPos> result = PlanarFaceRasterizer.generate(face, limit, null);
        if (!result.isEmpty()) return result;
        Set<BlockPos> outline = PlanarFaceRasterizer.outline(face, limit, null);
        return GenerationLimitExceeded.is(outline) ? outline : new LinkedHashSet<>(outline);
    }

    private static Set<BlockPos> legacyPrismOutline(List<Vec3> base, Vec3 extrusion) {
        List<BlockPos> offsets = LineGenerator.offsets(extrusion, LIMIT + 1);
        Set<BlockPos> outline = PlanarFaceRasterizer.outline(base, LIMIT + 1, null);
        Set<BlockPos> result = new LinkedHashSet<>();
        for (BlockPos block : outline) result.add(block.offset(offsets.getFirst()));
        for (BlockPos block : outline) result.add(block.offset(offsets.getLast()));
        for (Vec3 vertex : base) LineGenerator.add(result, vertex, vertex.add(extrusion), LIMIT + 1);
        return result;
    }

    private static Set<BlockPos> legacyTiltedBoxOutline(List<Vec3> base, Vec3 extrusion, LineTieBias tie) {
        Set<BlockPos> result = new LinkedHashSet<>();
        Set<BlockPos> outline = PlanarFaceRasterizer.interpolatedQuadOutline(base, LIMIT + 1, null, tie);
        BlockPos top = BlockPos.containing(extrusion);
        for (BlockPos block : outline) {
            result.add(block);
            result.add(block.offset(top));
        }
        List<BlockPos> offsets = LineGenerator.offsets(extrusion, LIMIT + 1, tie);
        for (Vec3 vertex : base) for (BlockPos offset : offsets) result.add(BlockPos.containing(vertex).offset(offset));
        return result;
    }

    private static FaceRasterPolicy edgePolicy() {
        EnumMap<FaceClass, List<net.minecraft.resources.ResourceLocation>> chains = new EnumMap<>(FaceClass.class);
        for (FaceClass kind : FaceClass.values()) chains.put(kind, List.of(FaceRasterPolicies.id("edge_only")));
        return new FaceRasterPolicy(chains);
    }

    private static void assertSameBlocksAndOrder(Set<BlockPos> expected, Set<BlockPos> actual, String context) {
        assertEquals(GenerationLimitExceeded.is(expected), GenerationLimitExceeded.is(actual), context + " / limit status");
        if (GenerationLimitExceeded.is(expected)) {
            assertEquals(expected.size(), actual.size(), context + " / witness size");
        } else {
            assertEquals(new ArrayList<>(expected), new ArrayList<>(actual), context);
        }
    }

    private static List<Vec3> quad(BlockPos first, BlockPos second) {
        return centers(BlockPos.ZERO, first, first.offset(second), second);
    }

    private static List<Vec3> centers(BlockPos... corners) {
        return java.util.Arrays.stream(corners).map(Vec3::atCenterOf).toList();
    }
}
