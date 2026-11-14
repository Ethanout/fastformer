package io.github.fastformer.fastplace.geometry.generation.face.classify;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.core.math.Axis;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class FaceClassifierTest {
    @Test void prunesEveryClassInTheSpecifiedOrder() {
        assertEquals(FaceClass.AXIS_RECTANGLE, quad(new BlockPos(4, 0, 0), new BlockPos(0, 0, 3)).kind());
        assertEquals(FaceClass.COORDINATE_PLANE, quad(new BlockPos(4, 0, 1), new BlockPos(-1, 0, 3)).kind());
        var extrusion = quad(new BlockPos(4, 0, 0), new BlockPos(1, 2, 3));
        assertEquals(FaceClass.AXIS_EXTRUSION, extrusion.kind());
        assertEquals(Axis.X, extrusion.parallelAxis().orElseThrow());
        assertTrue(extrusion.normalAxis().isEmpty());
        assertEquals(FaceClass.TILTED_PARALLELOGRAM, quad(new BlockPos(4, 1, 1), new BlockPos(1, 3, 2)).kind());
        assertEquals(FaceClass.POLYGON, FaceClassifier.classify(points(BlockPos.ZERO, new BlockPos(4, 0, 0), new BlockPos(1, 0, 3))).kind());
        assertEquals(FaceClass.POLYGON, FaceClassifier.classify(points(BlockPos.ZERO, new BlockPos(4, 0, 0),
                new BlockPos(5, 0, 3), new BlockPos(0, 0, 2))).kind());
    }

    @Test void degeneracyTakesPriorityOverParallelogramAndPolygonCounts() {
        assertEquals(FaceClass.DEGENERATE, FaceClassifier.classify(List.of()).kind());
        assertEquals(FaceClass.DEGENERATE, FaceClassifier.classify(points(BlockPos.ZERO)).kind());
        assertEquals(FaceClass.DEGENERATE, quad(new BlockPos(2, 2, 2), new BlockPos(4, 4, 4)).kind());
        assertEquals(FaceClass.DEGENERATE, FaceClassifier.classify(points(BlockPos.ZERO, BlockPos.ZERO, BlockPos.ZERO)).kind());
        assertEquals(FaceClass.DEGENERATE, FaceClassifier.classify(points(BlockPos.ZERO, new BlockPos(2, 0, 0),
                new BlockPos(2, 0, 2), new BlockPos(2, 0, 0))).kind());
        assertEquals(FaceClass.DEGENERATE, FaceClassifier.classify(points(BlockPos.ZERO, new BlockPos(2, 0, 2),
                new BlockPos(0, 0, 2), new BlockPos(2, 0, 0))).kind());
    }

    @Test void ownsNegativeCoordinatesByFlooringExactlyLikeCurrentRasterizers() {
        var shape = FaceClassifier.classify(List.of(new Vec3(-0.1, 4.8, -0.1), new Vec3(2.9, 4.8, -0.1),
                new Vec3(2.9, 4.8, 2.9), new Vec3(-0.1, 4.8, 2.9)));
        assertEquals(FaceClass.AXIS_RECTANGLE, shape.kind());
        assertEquals(new BlockPos(-1, 4, -1), shape.corners().getFirst());
        assertEquals(Axis.Y, shape.normalAxis().orElseThrow());
    }

    @Test void exactCrossProductsDoNotOverflowAtIntegerCoordinateExtremes() {
        var shape = FaceClassifier.classify(points(new BlockPos(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE),
                new BlockPos(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE),
                new BlockPos(Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE)));
        assertEquals(FaceClass.POLYGON, shape.kind());
    }

    private static FaceShape quad(BlockPos first, BlockPos second) {
        return FaceClassifier.classify(points(BlockPos.ZERO, first, first.offset(second), second));
    }

    private static List<Vec3> points(BlockPos... corners) {
        return java.util.Arrays.stream(corners).map(Vec3::atCenterOf).toList();
    }
}
