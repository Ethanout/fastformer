package io.github.fastformer.fastplace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.github.fastformer.fastplace.geometry.AxisGizmo;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class OperationStackRegionTest {
   @Test
   void originContainsExactlyOneCell() {
      OperationStackRegion region = OperationStackRegion.origin();

      assertEquals(BlockPos.ZERO, region.min());
      assertEquals(BlockPos.ZERO, region.max());
      assertEquals(1L, region.cellCount());
      assertEquals(List.of(BlockPos.ZERO), region.repetitions(10));
   }

   @Test
   void repeatingAlongTheSameAxisAddsCopiesLinearly() {
      OperationStackRegion region = OperationStackRegion.origin()
         .repeat(AxisGizmo.Axis.X, 1, 3)
         .repeat(AxisGizmo.Axis.X, 1, 1);

      assertEquals(new BlockPos(0, 0, 0), region.min());
      assertEquals(new BlockPos(4, 0, 0), region.max());
      assertEquals(5L, region.cellCount());
   }

   @Test
   void negativeStackExpandsTheMinimumEndpoint() {
      OperationStackRegion region = OperationStackRegion.origin()
         .repeat(AxisGizmo.Axis.Z, -1, 2);

      assertEquals(new BlockPos(0, 0, -2), region.min());
      assertEquals(BlockPos.ZERO, region.max());
   }

   @Test
   void reverseRepeatRetractsOnlyTheDraggedSideAndNeverFlipsTheFace() {
      OperationStackRegion region = OperationStackRegion.origin()
         .repeat(AxisGizmo.Axis.X, 1, 3)
         .repeat(AxisGizmo.Axis.X, -1, 2);

      OperationStackRegion positiveRetracted = region.withAxisEndpoint(AxisGizmo.Axis.X, 1, -9);
      OperationStackRegion negativeRetracted = region.withAxisEndpoint(AxisGizmo.Axis.X, -1, -1);

      assertEquals(new BlockPos(-2, 0, 0), positiveRetracted.min());
      assertEquals(BlockPos.ZERO, positiveRetracted.max(), "the +X handle stops at the origin instead of becoming -X");
      assertEquals(new BlockPos(-1, 0, 0), negativeRetracted.min());
      assertEquals(new BlockPos(3, 0, 0), negativeRetracted.max());
   }

   @Test
   void axesFormACartesianProduct() {
      OperationStackRegion region = OperationStackRegion.origin()
         .repeat(AxisGizmo.Axis.X, 1, 1)
         .repeat(AxisGizmo.Axis.Y, -1, 2)
         .repeat(AxisGizmo.Axis.Z, 1, 3);

      assertEquals(24L, region.cellCount());
      assertEquals(24, region.repetitions(100).size());
   }

   @Test
   void onlyRepeatsOnDifferentAxesMultiplyTheCurrentRegion() {
      OperationStackRegion region = OperationStackRegion.origin()
         .repeat(AxisGizmo.Axis.X, 1, 3)
         .repeat(AxisGizmo.Axis.X, 1, 1)
         .repeat(AxisGizmo.Axis.Y, 1, 1);

      assertEquals(10L, region.cellCount());
   }

   @Test
   void zeroCopiesPreservesTheSameImmutableValue() {
      OperationStackRegion origin = OperationStackRegion.origin();

      assertSame(origin, origin.repeat(AxisGizmo.Axis.X, 1, 0));
   }

   @Test
   void endpointsAndEnumerationAreBoundedByOperationLimit() {
      OperationStackRegion region = OperationStackRegion.origin()
         .repeat(AxisGizmo.Axis.X, 1, Integer.MAX_VALUE)
         .repeat(AxisGizmo.Axis.Y, -1, Integer.MAX_VALUE)
         .repeat(AxisGizmo.Axis.Z, 1, Integer.MAX_VALUE);

      assertEquals(new BlockPos(0, -128, 0), region.min());
      assertEquals(new BlockPos(128, 0, 128), region.max());
      assertEquals(129L * 129L * 129L, region.cellCount());
      assertEquals(7, region.repetitions(7).size());
   }
}
