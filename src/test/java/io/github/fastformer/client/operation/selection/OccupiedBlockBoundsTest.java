package io.github.fastformer.client.operation.selection;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.fastformer.fastplace.geometry.AxisGizmo;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class OccupiedBlockBoundsTest {
   @Test
   void sparsePrismLikeOccupancyUsesActualIntegerBlocks() {
      OccupiedBlockBounds bounds = OccupiedBlockBounds.from(List.of(
         new BlockPos(2, 4, -3), new BlockPos(4, 5, 1), new BlockPos(3, 9, 0)
      )).orElseThrow();

      assertEquals(new BlockPos(2, 4, -3), bounds.min());
      assertEquals(new BlockPos(4, 9, 1), bounds.max());
      assertEquals(3, bounds.width(AxisGizmo.Axis.X));
      assertEquals(6, bounds.width(AxisGizmo.Axis.Y));
      assertEquals(5, bounds.width(AxisGizmo.Axis.Z));
      assertEquals(new Vec3(3.5, 7.0, -0.5), bounds.center());
   }

   @Test
   void unionAndSmallestAxisAreStableWithXyzTieBreaking() {
      OccupiedBlockBounds union = OccupiedBlockBounds.from(List.of(BlockPos.ZERO)).orElseThrow()
         .union(OccupiedBlockBounds.from(List.of(new BlockPos(1, 4, 1))).orElseThrow());

      assertEquals(new BlockPos(0, 0, 0), union.min());
      assertEquals(new BlockPos(1, 4, 1), union.max());
      assertEquals(AxisGizmo.Axis.X, union.smallestAxis());
   }
}
