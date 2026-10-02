package io.github.fastformer.client.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

class SmartSelectionCellMeshTest {
   @Test
   void cubeFacesUseVanillaDirectionalBrightness() {
      var mesh = SmartSelectionCellMesh.build(Set.of(BlockPos.ZERO), true);
      var colors = mesh.faces().stream().collect(java.util.stream.Collectors.toMap(
         ShapeShellMesh.Face::direction, ShapeShellMesh.Face::color));
      assertEquals(1.0F, colors.get(Direction.UP).blue(), 0.0001F);
      assertEquals(0.8F, colors.get(Direction.NORTH).blue(), 0.0001F);
      assertEquals(colors.get(Direction.NORTH), colors.get(Direction.SOUTH));
      assertEquals(0.6F, colors.get(Direction.WEST).blue(), 0.0001F);
      assertEquals(colors.get(Direction.WEST), colors.get(Direction.EAST));
      assertEquals(0.5F, colors.get(Direction.DOWN).blue(), 0.0001F);
   }

   @Test
   void adjacentCellsKeepGridSeamButRemoveInternalFaces() {
      var mesh = SmartSelectionCellMesh.build(Set.of(BlockPos.ZERO, new BlockPos(1, 0, 0)), true);
      assertEquals(10, mesh.faces().size());
      assertTrue(mesh.faces().stream().noneMatch(face -> face.direction().getAxis() == Direction.Axis.X
         && face.vertices().getFirst().x == 1.0));
      assertEquals(4, mesh.edges().stream().filter(edge -> edge.from().x == 1.0 && edge.to().x == 1.0).count());
   }

   @Test
   void membershipAloneProducesFullCellWithoutWorldModel() {
      var mesh = SmartSelectionCellMesh.build(Set.of(new BlockPos(-4, 20, 8)), true);
      assertEquals(6, mesh.faces().size());
      assertEquals(12, mesh.edges().size());
   }
}
