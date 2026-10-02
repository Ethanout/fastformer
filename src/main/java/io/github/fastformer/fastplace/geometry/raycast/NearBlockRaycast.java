package io.github.fastformer.fastplace.geometry.raycast;

import java.util.HashSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Adds decreasing full-cell tolerance for the FF reach transition only. */
public final class NearBlockRaycast {
   public static final double ASSIST_DISTANCE = 20.0;
   private static final double MAX_RADIUS = 0.25;

   private NearBlockRaycast() { }

   public static double radiusAt(double distance) {
      return MAX_RADIUS * Math.clamp(1.0 - distance / ASSIST_DISTANCE, 0.0, 1.0);
   }

   public static BlockHitResult assist(Level level, Entity source, Vec3 start, Vec3 direction,
      BlockHitResult direct, double loadedDistance, boolean skipReplaceable) {
      double directDistance = direct.getType() == HitResult.Type.BLOCK
         ? start.distanceTo(direct.getLocation()) : Double.POSITIVE_INFINITY;
      double limit = Math.min(ASSIST_DISTANCE, Math.min(loadedDistance, directDistance));
      if (limit <= 0 || direction.lengthSqr() < 1.0E-12) return direct;
      Vec3 ray = direction.normalize();
      Vec3 end = start.add(ray.scale(limit));
      var context = new ClipContext(start, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, source);
      var visited = new HashSet<BlockPos>();
      BlockHitResult nearest = direct;
      double nearestDistance = directDistance;
      // Half-cell steps with one neighbor on each side cover the narrow swept ray.
      for (double step = 0; step <= limit + 0.5; step += 0.5) {
         BlockPos center = BlockPos.containing(start.add(ray.scale(Math.min(step, limit))));
         for (BlockPos cursor : BlockPos.betweenClosed(center.offset(-1, -1, -1), center.offset(1, 1, 1))) {
            BlockPos pos = cursor.immutable();
            if (!visited.add(pos) || !level.hasChunkAt(pos) || !level.isInWorldBounds(pos)) continue;
            // Tolerance must not steal an exact hit from an adjoining shape (for example crops over farmland).
            if (direct.getType() == HitResult.Type.BLOCK && adjoining(pos, direct.getBlockPos())) continue;
            if (!level.getWorldBorder().isWithinBounds(pos)) continue;
            var state = level.getBlockState(pos);
            if (state.isAir() || skipReplaceable && state.canBeReplaced()) continue;
            if (context.getBlockShape(state, level, pos).isEmpty()) continue;
            for (AABB localBox : List.of(new AABB(0, 0, 0, 1, 1, 1))) {
               AABB box = localBox.move(pos);
               double distance = Math.max(0, box.getCenter().subtract(start).dot(ray));
               BlockHitResult hit = clipBox(box, pos, start, end, radiusAt(distance));
               if (hit == null) continue;
               double hitDistance = start.distanceTo(hit.getLocation());
               if (hitDistance < nearestDistance && hitDistance <= loadedDistance) {
                  nearest = hit;
                  nearestDistance = hitDistance;
               }
            }
         }
      }
      return nearest;
   }

   private static boolean adjoining(BlockPos first, BlockPos second) {
      return Math.abs(first.getX() - second.getX()) <= 1
         && Math.abs(first.getY() - second.getY()) <= 1
         && Math.abs(first.getZ() - second.getZ()) <= 1;
   }

   static BlockHitResult clipBox(AABB box, BlockPos pos, Vec3 start, Vec3 end, double radius) {
      // A real intersection keeps the exact vanilla face and location.
      BlockHitResult direct = AABB.clip(List.of(box), start, end, BlockPos.ZERO);
      if (direct != null) return new BlockHitResult(direct.getLocation(), direct.getDirection(), pos, false);
      BlockHitResult expanded = AABB.clip(List.of(box.inflate(radius)), start, end, BlockPos.ZERO);
      if (expanded == null || box.inflate(radius).contains(start)) return null;
      Vec3 point = expanded.getLocation();
      Vec3 surface = new Vec3(Mth.clamp(point.x, box.minX, box.maxX),
         Mth.clamp(point.y, box.minY, box.maxY), Mth.clamp(point.z, box.minZ, box.maxZ));
      return new BlockHitResult(surface, expanded.getDirection(), pos, false);
   }
}
