package io.github.fastformer.fastplace.interaction;

import io.github.fastformer.common.raycast.InteractionShapeSnapshot;

import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Frozen at the first press and shared by client recognition and server snapshot consumption. */
public record EntryDoubleClickTolerance(long intervalNanos, double shapePadding) {
   public static final long NEAR_INTERVAL_NANOS = 200_000_000L;
   private static final long FAR_INTERVAL_NANOS = 300_000_000L;
   private static final double FAR_DISTANCE = 15.0;
   private static final double PADDING_ANGLE_RADIANS = Math.toRadians(0.5);
   private static final double MAX_SHAPE_PADDING = 0.5;

   public static EntryDoubleClickTolerance capture(InteractionShapeSnapshot snapshot, Vec3 eye, Vec3 view) {
      var hit = snapshot.clip(eye, view);
      double distance = eye.distanceTo(hit == null ? Vec3.atCenterOf(snapshot.position()) : hit.getLocation());
      if (distance < FAR_DISTANCE) return new EntryDoubleClickTolerance(NEAR_INTERVAL_NANOS, 0);
      return new EntryDoubleClickTolerance(FAR_INTERVAL_NANOS,
         Math.min(MAX_SHAPE_PADDING, distance * Math.tan(PADDING_ANGLE_RADIANS)));
   }

   public BlockHitResult clip(InteractionShapeSnapshot snapshot, Vec3 eye, Vec3 view) {
      return snapshot.clip(eye, view, shapePadding);
   }
}
