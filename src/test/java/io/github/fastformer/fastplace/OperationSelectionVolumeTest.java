package io.github.fastformer.fastplace;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class OperationSelectionVolumeTest {
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
