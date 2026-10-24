package io.github.fastformer.fastplace.geometry.movement;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

final class CollisionSafeDashTraversal implements ForwardDashTraversal {
   private static final double CHECK_SEGMENT_LENGTH = 4.0;

   @Override
   public double clearFraction(Entity traveler, Vec3 movement) {
      int segments = Math.max(1, (int)Math.ceil(movement.length() / CHECK_SEGMENT_LENGTH));
      Vec3 step = movement.scale(1.0 / segments);
      AABB box = traveler.getBoundingBox();
      // Query small boxes along the ray rather than a huge diagonal enclosing volume.
      for (int index = 0; index < segments; index++) {
         double fraction = clearSegment(traveler, box.move(step.scale(index)), step);
         if (fraction < 1.0) return (index + fraction) / segments;
      }
      return 1.0;
   }

   private double clearSegment(Entity traveler, AABB box, Vec3 movement) {
      var level = traveler.level();
      var swept = box.expandTowards(movement);
      if (!level.hasChunksAt(BlockPos.containing(swept.minX, swept.minY, swept.minZ),
         BlockPos.containing(swept.maxX, swept.maxY, swept.maxZ))) return 0.0;
      double blocks = ForwardDashPath.clearFraction(box, movement, level.getBlockCollisions(traveler, swept));
      double border = ForwardDashPath.clearFraction(box, movement, List.of(level.getWorldBorder().getCollisionShape()));
      return Math.min(blocks, border);
   }

   @Override
   public void move(Entity traveler, Vec3 movement) {
      int segments = Math.max(1, (int)Math.ceil(movement.length() / CHECK_SEGMENT_LENGTH));
      Vec3 step = movement.scale(1.0 / segments);
      // Entity.move also queries the swept box, so keep its diagonal queries small.
      for (int index = 0; index < segments; index++) traveler.move(MoverType.SELF, step);
   }
}
