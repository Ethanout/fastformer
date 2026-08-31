package io.github.fastformer.client.render.geometry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.github.fastformer.fastplace.geometry.AxisGizmo;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class PreviewGeometrySupportTest {
   @Test
   void unionAndDifferencePreserveEmptyInputs() {
      BlockPos first = new BlockPos(1, 2, 3);
      BlockPos second = new BlockPos(4, 5, 6);

      assertEquals(Set.of(first, second), PreviewGeometrySupport.unionBlocks(Set.of(first), Set.of(second)));
      assertEquals(Set.of(first), PreviewGeometrySupport.withoutBlocks(Set.of(first, second), Set.of(second)));
      assertEquals(Set.of(first), PreviewGeometrySupport.unionBlocks(Set.of(first), Set.of()));
      Set<BlockPos> empty = Set.of();
      assertSame(empty, PreviewGeometrySupport.withoutBlocks(empty, Set.of(second)));
   }

   @Test
   void localAxisRotationUsesEulerRotationOrder() {
      Vec3 rotated = PreviewGeometrySupport.rotateLocalAxis(
         AxisGizmo.Axis.X,
         new Vec3(0.0, 0.0, Math.PI * 0.5)
      );

      assertEquals(0.0, rotated.x, 1.0E-9);
      assertEquals(1.0, rotated.y, 1.0E-9);
      assertEquals(0.0, rotated.z, 1.0E-9);
   }
}
