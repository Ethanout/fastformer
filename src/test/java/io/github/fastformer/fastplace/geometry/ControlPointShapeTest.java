package io.github.fastformer.fastplace.geometry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class ControlPointShapeTest {
   @Test
   void blockCentersAndSpecialPointsRenderAsFullBlocksWithoutChangingHitBounds() {
      Vec3 fullBlock = new Vec3(0.5, 0.5, 0.5);
      Vec3 blockCenter = new Vec3(2.5, 4.5, -1.5);

      assertEquals(ControlPointShape.BLOCK, ControlPointShape.at(blockCenter));
      assertEquals(fullBlock, ControlPointShape.BLOCK.visualHalfExtents(blockCenter));
      assertEquals(fullBlock, ControlPointShape.POINT.visualHalfExtents(new Vec3(2.0, 4.0, -1.0)));
      assertNotEquals(fullBlock, ControlPointShape.POINT.halfExtents());
      assertEquals(
         ControlPointShape.BLOCK.halfExtents(),
         ControlPointShape.BLOCK.visualHalfExtents(new Vec3(2.25, 4.25, -1.25))
      );
   }
}
