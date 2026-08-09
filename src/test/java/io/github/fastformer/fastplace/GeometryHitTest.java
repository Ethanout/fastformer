package io.github.fastformer.fastplace;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class GeometryHitTest {
   private static final BlockPos HIT_BLOCK = new BlockPos(4, 8, -2);

   @Test
   void sphereNormalInputStaysInsideTheHitBlock() {
      GeometryHit hit = hit(new Vec3(5.0, 8.5, -1.5), Direction.EAST);

      assertEquals(new Vec3(4.5, 8.5, -1.5), hit.spherePoint(false));
   }

   @Test
   void spherePreciseInputUsesTheBlockCenterBeyondTheHitFace() {
      GeometryHit hit = hit(new Vec3(5.0, 8.5, -1.5), Direction.EAST);

      assertEquals(new Vec3(5.5, 8.5, -1.5), hit.spherePoint(true));
   }

   @Test
   void spherePreciseInputSnapsNearAThreeDimensionalCorner() {
      GeometryHit hit = hit(new Vec3(5.12, 8.09, -1.08), Direction.EAST);

      assertEquals(new Vec3(5.0, 8.0, -1.0), hit.spherePoint(true));
   }

   @Test
   void spherePreciseInputDoesNotTreatAFaceOrEdgeAsACorner() {
      GeometryHit hit = hit(new Vec3(5.0, 8.5, -1.24), Direction.EAST);

      assertEquals(new Vec3(5.5, 8.5, -1.5), hit.spherePoint(true));
   }

   @Test
   void coneNormalInputUsesEmbeddedWholeBlockGrid() {
      GeometryHit hit = hit(new Vec3(5.0, 8.26, -1.74), Direction.EAST);

      assertEquals(new Vec3(4.5, 8.5, -1.5), hit.conePoint(false));
   }

   @Test
   void coneAltInputUsesSurfaceHalfBlockGrid() {
      GeometryHit hit = hit(new Vec3(5.0, 8.26, -1.74), Direction.EAST);

      assertEquals(new Vec3(5.0, 8.5, -1.5), hit.conePoint(true));
   }

   private static GeometryHit hit(Vec3 location, Direction face) {
      return GeometryHit.from(new BlockHitResult(location, face, HIT_BLOCK, false));
   }
}
