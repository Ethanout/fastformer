package io.github.fastformer.fastplace.selection;


import io.github.fastformer.fastplace.world.*;

import io.github.fastformer.fastplace.session.*;
import io.github.fastformer.fastplace.workflow.*;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class OperationSelectionVolumeTest {
   @Test
   void expandedBoundsRemainAuthoritativeUntilAnInputPointChanges() {
      BlockPos first = new BlockPos(4, 3, 2);
      BlockPos second = new BlockPos(-2, -3, -4);
      OperationSelectionVolume selection = OperationSelectionVolume.cuboid(
         new BlockPos(-10, -8, -6), new BlockPos(8, 6, 4), first, second
      );
      selection = selection.expandCuboidTo(new BlockPos(-12, 9, 1))
         .expandCuboidTo(new BlockPos(0, 0, 0));
      org.junit.jupiter.api.Assertions.assertEquals(first, selection.point1());
      org.junit.jupiter.api.Assertions.assertEquals(second, selection.point2());
      org.junit.jupiter.api.Assertions.assertEquals(
         new net.minecraft.world.phys.AABB(-12, -8, -6, 9, 10, 5), selection.bounds()
      );
      assertNotNull(selection.raycast(new Vec3(-14, 0, 0), new Vec3(1, 0, 0), 3));
      selection = selection.withCuboidPoint(1, new BlockPos(1, 1, 1));
      org.junit.jupiter.api.Assertions.assertEquals(first, selection.point1());
      org.junit.jupiter.api.Assertions.assertEquals(
         new net.minecraft.world.phys.AABB(1, 1, 1, 5, 4, 3), selection.bounds()
      );
   }

   @Test
   void cuboidRaycastUsesTheSameSmallInflationAsItsVisualSurface() {
      OperationSelectionVolume selection = OperationSelectionVolume.create(
         OperationSelectionMode.CUBOID,
         List.of(BlockPos.ZERO, BlockPos.ZERO),
         BlockPos.ZERO,
         BlockPos.ZERO,
         0
      );
      double insideInflation = OperationSelectionVolume.RAYCAST_INFLATE * 0.5;

      assertNotNull(selection);
      assertNotNull(selection.raycast(
         new Vec3(-1.0, 1.0 + insideInflation, 0.5),
         new Vec3(1.0, 0.0, 0.0),
         4.0
      ));
   }
}
