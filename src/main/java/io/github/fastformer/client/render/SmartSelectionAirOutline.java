package io.github.fastformer.client.render;

import io.github.fastformer.fastplace.geometry.GeometryPalette;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;

/** Air has no model, but explicit selection membership still needs a visible boundary. */
public final class SmartSelectionAirOutline {
   private SmartSelectionAirOutline() { }

   public static List<ShapeShellMesh.StyledEdge> build(Set<BlockPos> cells) {
      var palette = GeometryPalette.ink();
      var color = new ShapeShellMesh.Color(palette.red(), palette.green(), palette.blue());
      var parts = new ArrayList<ShapeShellMesh.Part>();
      for (BlockPos cell : cells) {
         var hidden = EnumSet.noneOf(Direction.class);
         for (Direction face : Direction.values()) if (cells.contains(cell.relative(face))) hidden.add(face);
         if (hidden.size() < 6) parts.add(new ShapeShellMesh.Part(List.of(new AABB(cell)), color, color, true, hidden));
      }
      return ShapeShellMesh.build(parts).edges();
   }
}
