package io.github.fastformer.client.interaction.pointer;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Resolves semantic point cells against an already captured ray and distance limit. */
public final class PointRaycast {
   private PointRaycast() {}

   public static BlockPos nearest(List<BlockPos> points, Vec3 eye, Vec3 view, double reach) {
      Vec3 end = eye.add(view.normalize().scale(reach));
      BlockPos closest = null;
      double best = Double.POSITIVE_INFINITY;
      for (BlockPos point : points) {
         var hit = new AABB(point).clip(eye, end);
         if (hit.isEmpty()) continue;
         double distance = eye.distanceToSqr(hit.orElseThrow());
         if (distance < best) { best = distance; closest = point; }
      }
      return closest;
   }
}
