package io.github.fastformer.client.interaction.path;

import io.github.fastformer.client.interaction.pointer.PointRaycast;
import io.github.fastformer.common.raycast.LongRangeBlockRaycast;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.HitResult.Type;
import net.minecraft.world.phys.Vec3;

/** Captured path facts; closure availability preserves each workflow's existing priority. */
public record PathCloseTarget(boolean hoveredStart, boolean doubleClickEnabled, BlockPos candidate) {
   public static PathCloseTarget resolve(List<BlockPos> operation, List<BlockPos> geometry, List<BlockPos> building,
      Vec3 eye, Vec3 view, LongRangeBlockRaycast.Result ray, BlockPos candidate) {
      var hoverPath = first(3, operation, geometry, building);
      boolean hovered = false;
      if (eye != null && view != null && ray != null && !hoverPath.isEmpty()) {
         double reach = LongRangeBlockRaycast.MAX_REACH;
         if (ray.hit().getType() == Type.BLOCK) reach = Math.min(reach, eye.distanceTo(ray.hit().getLocation()) + 1.0E-4);
         hovered = hoverPath.getFirst().equals(PointRaycast.nearest(List.of(hoverPath.getFirst()), eye, view, reach));
      }
      return new PathCloseTarget(hovered, !first(2, operation, geometry, building).isEmpty(), candidate);
   }

   private static List<BlockPos> first(int minimum, List<BlockPos> operation, List<BlockPos> geometry, List<BlockPos> building) {
      if (operation.size() >= minimum) return operation;
      if (geometry.size() >= minimum) return geometry;
      return building.size() >= minimum ? building : List.of();
   }
}
