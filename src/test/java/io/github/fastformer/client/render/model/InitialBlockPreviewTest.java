package io.github.fastformer.client.render.model;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.fastplace.geometry.GeometryPalette;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

class InitialBlockPreviewTest {
   @Test
   void slabOutlineKeepsItsHeightAndInkColor() {
      var mesh = InitialBlockPreview.mesh(new BlockPos(3, 4, 5), List.of(new AABB(0, 0, 0, 1, 0.5, 1)));
      assertFalse(mesh.edges().isEmpty());
      var ink = GeometryPalette.ink();
      for (var edge : mesh.edges()) {
         assertEquals(ink.red(), edge.color().red());
         assertEquals(ink.green(), edge.color().green());
         assertEquals(ink.blue(), edge.color().blue());
         assertTrue(edge.from().y >= 4 && edge.from().y <= 4.5);
         assertTrue(edge.to().y >= 4 && edge.to().y <= 4.5);
      }
      assertEquals(4.5, mesh.edges().stream().mapToDouble(edge -> Math.max(edge.from().y, edge.to().y)).max().orElseThrow());
   }

   @Test
   void emptyShapeDoesNotBecomeAFullCube() {
      var mesh = InitialBlockPreview.mesh(BlockPos.ZERO, List.of());
      assertTrue(mesh.faces().isEmpty());
      assertTrue(mesh.edges().isEmpty());
   }
}
