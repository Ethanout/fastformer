package io.github.fastformer.fastplace;

import io.github.fastformer.fastplace.world.*;

import io.github.fastformer.fastplace.session.*;
import io.github.fastformer.fastplace.workflow.*;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.fastformer.fastplace.geometry.AxisGizmo;
import io.github.fastformer.fastplace.geometry.OperationGeometry;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

class OperationStackPlacementTest {
   private static final AABB BOUNDS = new AABB(0, 0, 0, 2, 3, 4);

   @Test
   void sameAxisRepeatsAndTranslationProduceALinearTargetGrid() {
      OperationStackRegion region = OperationStackRegion.origin()
         .repeat(AxisGizmo.Axis.X, 1, 3)
         .repeat(AxisGizmo.Axis.X, 1, 1);

      assertEquals(
         List.of(
            new BlockPos(10, 0, 0), new BlockPos(12, 0, 0), new BlockPos(14, 0, 0), new BlockPos(16, 0, 0),
            new BlockPos(18, 0, 0)
         ),
         OperationGeometry.stackTargets(BOUNDS, region, new BlockPos(10, 0, 0), 100)
      );
   }

   @Test
   void negativeAndMultipleAxesUseSelectionDimensions() {
      OperationStackRegion region = OperationStackRegion.origin()
         .repeat(AxisGizmo.Axis.X, -1, 1)
         .repeat(AxisGizmo.Axis.Y, 1, 1)
         .repeat(AxisGizmo.Axis.Z, -1, 1);

      assertEquals(8, OperationGeometry.stackTargets(BOUNDS, region, BlockPos.ZERO, 100).size());
      assertEquals(
         new BlockPos(-2, 3, -4),
         OperationGeometry.stackTargets(BOUNDS, region, BlockPos.ZERO, 100).get(2)
      );
   }

   @Test
   void moveThenStackStillMovesTheOriginalSourceWhenNotCopying() {
      assertEquals(
         true,
         OperationExecutionSemantics.clearsSource(
            OperationMode.STACK, new BlockPos(5, 0, 0), false
         )
      );
      assertEquals(
         false,
         OperationExecutionSemantics.clearsSource(
            OperationMode.STACK, BlockPos.ZERO, false
         )
      );
      assertEquals(
         false,
         OperationExecutionSemantics.clearsSource(
            OperationMode.MOVE, new BlockPos(5, 0, 0), true
         )
      );
   }
}
