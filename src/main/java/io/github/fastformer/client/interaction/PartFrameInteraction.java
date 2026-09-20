package io.github.fastformer.client.interaction;

import io.github.fastformer.fastplace.geometry.OperationGeometry;
import java.util.UUID;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** The selection envelope is shared by frame rendering and picking, including empty space. */
public final class PartFrameInteraction {
   private static final double EDGE_THRESHOLD = 0.12;

   private PartFrameInteraction() { }

   public static InteractionObject create(UUID session, long identity, AABB bounds) {
      return create(session, identity, bounds, InteractionComponents.SelectionRole.TRANSFORMED_PART);
   }

   public static InteractionObject create(UUID session, long identity, AABB bounds,
      InteractionComponents.SelectionRole role) {
      return InteractionObject.builder(new InteractionObject.Id(session, "part_frame", identity))
         .with(InteractionComponents.WORLD_BOUNDS, bounds)
         .with(InteractionComponents.SELECTION_ROLE, role)
         .with(InteractionComponents.TOOLTIP, InteractionTooltip.FRAME)
         .with(InteractionComponents.PRESS_BINDING, InteractionPressBinding.FRAME)
         .build();
   }

   public static OperationGeometry.RayHit hit(
      InteractionObject frame, Vec3 eye, Vec3 view, double reach, boolean includeFaces
   ) {
      if (frame == null) return null;
      AABB bounds = frame.require(InteractionComponents.WORLD_BOUNDS);
      var hit = OperationGeometry.raycast(bounds.inflate(0.015), eye, view, reach);
      return hit != null && (includeFaces || nearEdge(hit.point(), bounds)) ? hit : null;
   }

   private static boolean nearEdge(Vec3 point, AABB bounds) {
      int axes = 0;
      if (Math.min(Math.abs(point.x - bounds.minX), Math.abs(point.x - bounds.maxX)) <= EDGE_THRESHOLD) axes++;
      if (Math.min(Math.abs(point.y - bounds.minY), Math.abs(point.y - bounds.maxY)) <= EDGE_THRESHOLD) axes++;
      if (Math.min(Math.abs(point.z - bounds.minZ), Math.abs(point.z - bounds.maxZ)) <= EDGE_THRESHOLD) axes++;
      return axes >= 2;
   }
}
