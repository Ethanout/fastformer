package io.github.fastformer.fastplace.geometry.generation;
import io.github.fastformer.common.geometry.raster.BlockGenerationObserver;

import io.github.fastformer.common.geometry.LineTieBias;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.common.geometry.model.FillMode;
import io.github.fastformer.common.geometry.raster.face.FaceRasterResult;
import io.github.fastformer.common.geometry.raster.face.FaceRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class FaceRasterAdaptersTest {
    private static final List<List<Vec3>> FACES = List.of(
            quad(new BlockPos(4, 0, 0), new BlockPos(0, 0, 3)),
            quad(new BlockPos(4, 0, 1), new BlockPos(-1, 0, 3)),
            quad(new BlockPos(4, 0, 0), new BlockPos(1, 2, 3)),
            quad(new BlockPos(4, 1, 1), new BlockPos(1, 3, 2)),
            List.of(new Vec3(0.5, 0.5, 0.5), new Vec3(4.5, 0.5, 0.5), new Vec3(3.5, 2.5, 4.5), new Vec3(0.5, 2.5, 3.5)),
            List.of(new Vec3(0.5, 0.5, 0.5), new Vec3(4.5, 0.5, 0.5), new Vec3(1.5, 0.5, 3.5)));

    @Test void isolatedDispatcherReproducesEachExistingEntryWithoutUnifyingTheirShapes() {
        for (List<Vec3> vertices : FACES) for (LineTieBias tie : LineTieBias.values()) {
            for (FillMode fill : FillMode.values()) {
                var request = new FaceRequest(vertices, fill, tie, 10_000, null);
                Set<BlockPos> polygon = fill == FillMode.OUTLINE
                        ? PlanarFaceRasterizer.outline(vertices, 10_000, BlockGenerationObserver.NONE, tie)
                        : PlanarFaceRasterizer.generate(vertices, 10_000, BlockGenerationObserver.NONE, tie);
                assertSameBlocksAndOrder(polygon, FaceRasterAdapters.currentPolygon().rasterize(request));
                Set<BlockPos> quad = fill == FillMode.OUTLINE
                        ? PlanarFaceRasterizer.interpolatedQuadOutline(vertices, 10_000, BlockGenerationObserver.NONE, tie)
                        : PlanarFaceRasterizer.generateInterpolatedQuad(vertices, 10_000, BlockGenerationObserver.NONE, tie);
                assertSameBlocksAndOrder(quad, FaceRasterAdapters.currentQuad().rasterize(request));
            }
        }
    }

    @Test void typedLimitReplacesWitnessWithoutPublishingSyntheticPositions() {
        List<BlockPos> observed = new ArrayList<>();
        var request = new FaceRequest(FACES.getFirst(), FillMode.SOLID, LineTieBias.DEFAULT, 1, new BlockGenerationObserver() {
            @Override public void onGenerated(BlockPos block) { observed.add(block); }
        });
        assertInstanceOf(FaceRasterResult.LimitExceeded.class, FaceRasterAdapters.currentQuad().rasterize(request));
        assertTrue(observed.isEmpty());
        assertInstanceOf(FaceRasterResult.LimitExceeded.class, FaceRasterAdapters.currentPolygon().rasterize(request));
        assertTrue(observed.isEmpty());
    }

    @Test void onlyFinalDistinctBlocksReachGenerationObserver() {
        List<BlockPos> observed = new ArrayList<>();
        var request = new FaceRequest(FACES.get(3), FillMode.SOLID, LineTieBias.DEFAULT, 1000, new BlockGenerationObserver() {
            @Override public void onGenerated(BlockPos block) { observed.add(block); }
        });
        var filled = assertInstanceOf(FaceRasterResult.Filled.class, FaceRasterAdapters.currentQuad().rasterize(request));
        assertEquals(new ArrayList<>(filled.blocks()), observed);
        assertEquals(filled.blocks().size(), Set.copyOf(observed).size());
    }

    private static void assertSameBlocksAndOrder(Set<BlockPos> expected, FaceRasterResult actual) {
        if (GenerationLimitExceeded.is(expected)) assertInstanceOf(FaceRasterResult.LimitExceeded.class, actual);
        else assertEquals(new ArrayList<>(expected), new ArrayList<>(assertInstanceOf(FaceRasterResult.Filled.class, actual).blocks()));
    }

    private static List<Vec3> quad(BlockPos first, BlockPos second) {
        return List.of(Vec3.atCenterOf(BlockPos.ZERO), Vec3.atCenterOf(first), Vec3.atCenterOf(first.offset(second)), Vec3.atCenterOf(second));
    }
}
