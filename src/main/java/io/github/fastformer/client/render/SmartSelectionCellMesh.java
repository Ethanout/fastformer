package io.github.fastformer.client.render;

import io.github.fastformer.fastplace.geometry.GeometryPalette;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;

/** Displays selection membership as solid cells, including selected air. */
public final class SmartSelectionCellMesh {
   private SmartSelectionCellMesh() { }

   public static ShapeShellMesh.Mesh build(Set<BlockPos> cells, boolean editable) {
      var palette = editable ? GeometryPalette.ink() : GeometryPalette.muted();
      var fill = new ShapeShellMesh.Color(palette.red(), palette.green(), palette.blue());
      var edge = new ShapeShellMesh.Color(fill.red() * 0.25F, fill.green() * 0.25F, fill.blue() * 0.25F);
      var parts = new ArrayList<ShapeShellMesh.Part>();
      for (BlockPos cell : cells) {
         var hidden = EnumSet.noneOf(Direction.class);
         for (Direction face : Direction.values()) if (cells.contains(cell.relative(face))) hidden.add(face);
         // Separate groups retain grid lines between coplanar cells.
         if (hidden.size() < 6) parts.add(new ShapeShellMesh.Part(
            List.of(new AABB(cell)), fill, edge, true, hidden, cell.asLong()));
      }
      var mesh = ShapeShellMesh.build(parts);
      var shadedFaces = mesh.faces().stream().map(face -> new ShapeShellMesh.Face(
         face.direction(), face.plane(), face.u0(), face.u1(), face.v0(), face.v1(),
         shadedColor(face.color(), face.direction()), face.outlineColor(), face.outline())).toList();
      return new ShapeShellMesh.Mesh(shadedFaces, mesh.edges());
   }

   private static ShapeShellMesh.Color shadedColor(ShapeShellMesh.Color color, Direction direction) {
      float brightness = switch (direction) {
         case UP -> 1.0F;
         case DOWN -> 0.5F;
         case NORTH, SOUTH -> 0.8F;
         case WEST, EAST -> 0.6F;
      };
      return new ShapeShellMesh.Color(color.red() * brightness, color.green() * brightness, color.blue() * brightness);
   }
}
