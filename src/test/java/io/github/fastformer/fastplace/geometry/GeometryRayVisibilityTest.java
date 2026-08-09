package io.github.fastformer.fastplace.geometry;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class GeometryRayVisibilityTest {
   @Test
   void unobstructedRayKeepsItsConfiguredReach() {
      assertEquals(
         128.0,
         GeometryRayVisibility.visibleReach(128.0, Vec3.ZERO, null),
         1.0E-9
      );
   }

   @Test
   void obstructedRayStopsJustAfterTheFirstBlockSurface() {
      BlockHitResult hit = BlockHitResult.miss(
         new Vec3(4.0, 0.0, 0.0), Direction.WEST, new BlockPos(4, 0, 0)
      );
      BlockHitResult block = new BlockHitResult(
         new Vec3(4.0, 0.0, 0.0), Direction.WEST, new BlockPos(4, 0, 0), false
      );

      assertEquals(4.0001, GeometryRayVisibility.visibleReach(128.0, Vec3.ZERO, block), 1.0E-9);
      assertEquals(128.0, GeometryRayVisibility.visibleReach(128.0, Vec3.ZERO, hit), 1.0E-9);
   }

   @Test
   void invalidReachNeverProducesNegativeVisibility() {
      assertEquals(0.0, GeometryRayVisibility.visibleReach(Double.NaN, Vec3.ZERO, null), 1.0E-9);
      assertEquals(0.0, GeometryRayVisibility.visibleReach(-2.0, Vec3.ZERO, null), 1.0E-9);
   }
}
