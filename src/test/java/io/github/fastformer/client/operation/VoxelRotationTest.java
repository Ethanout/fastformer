package io.github.fastformer.client.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.fastformer.fastplace.geometry.AxisGizmo;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class VoxelRotationTest {
   @Test
   void quarterTurnUsesExactBlockCenters() {
      Map<BlockPos, String> source = new LinkedHashMap<>();
      source.put(new BlockPos(1, 0, 0), "near");
      source.put(new BlockPos(2, 0, 0), "far");

      Map<BlockPos, String> rotated = VoxelRotation.rotateValues(
         source, new Vec3(0.5, 0.5, 0.5), AxisGizmo.Axis.Y, Math.PI / 2.0
      );

      assertEquals(java.util.Set.of(new BlockPos(0, 0, -1), new BlockPos(0, 0, -2)), rotated.keySet());
   }

   @Test
   void nonOrthogonalCollisionsUseStableSourceCoordinateOrder() {
      Map<BlockPos, String> source = new LinkedHashMap<>();
      source.put(new BlockPos(1, 0, 0), "later");
      source.put(new BlockPos(0, 0, 0), "first");

      Map<BlockPos, String> rotated = VoxelRotation.rotateValues(
         source, new Vec3(0.5, 0.5, 0.5), AxisGizmo.Axis.Y, Math.toRadians(5.0)
      );

      assertEquals("first", rotated.get(BlockPos.ZERO));
   }

   @Test
   void nonOrthogonalRotationBridgesFaceAdjacentSourceVoxels() {
      Map<BlockPos, String> source = new LinkedHashMap<>();
      source.put(BlockPos.ZERO, "left");
      source.put(new BlockPos(1, 0, 0), "right");

      Map<BlockPos, String> rotated = VoxelRotation.rotateValues(
         source, new Vec3(0.5, 0.5, 0.5), AxisGizmo.Axis.Y, Math.toRadians(45.0)
      );

      assertEquals("left", rotated.get(BlockPos.ZERO));
      assertEquals("right", rotated.get(new BlockPos(1, 0, -1)));
      assertEquals("left", rotated.get(new BlockPos(1, 0, 0)));
   }
}
