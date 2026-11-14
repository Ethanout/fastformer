package io.github.fastformer.client.preview.selection;

import io.github.fastformer.client.interaction.intent.OperationInteractionIntent;
import io.github.fastformer.common.geometry.gizmo.AxisGizmo;
import io.github.fastformer.common.selection.geometry.OperationGeometry;
import io.github.fastformer.common.selection.OperationSelectionVolume;
import io.github.fastformer.protocol.s2c.operation.OperationPreviewPayload;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Selection pointers and source snapshot frozen at the common visual boundary. */
public record SelectionPreviewInput(OperationPreviewPayload snapshot, OperationInteractionIntent pointer,
   AxisGizmo remoteGizmo, OperationGeometry.RayHit draggedFace, OperationGeometry.RayHit fallbackFace,
   OperationGeometry.RayHit operationGuide, OperationGeometry.RayHit workspaceGuide,
   boolean serverSelection, boolean workspace) {
   public OperationGeometry.RayHit faceTarget(OperationSelectionVolume selection) {
      OperationGeometry.RayHit dragged = draggedFace;
      if (dragged == null) {
         return fallbackFace;
      }
      if (selection == null || selection.prism() != null || dragged.axis() < 0 || dragged.axis() > 2) {
         return dragged;
      }
      AABB bounds = selection.bounds();
      boolean positive = dragged.normal().dot(selection.axis(dragged.axis())) > 0.0;
      double coordinate = switch (dragged.axis()) {
         case 0 -> positive ? bounds.maxX : bounds.minX;
         case 1 -> positive ? bounds.maxY : bounds.minY;
         default -> positive ? bounds.maxZ : bounds.minZ;
      };
      Vec3 point = switch (dragged.axis()) {
         case 0 -> new Vec3(
            coordinate,
            Math.clamp(dragged.point().y, bounds.minY, bounds.maxY),
            Math.clamp(dragged.point().z, bounds.minZ, bounds.maxZ)
         );
         case 1 -> new Vec3(
            Math.clamp(dragged.point().x, bounds.minX, bounds.maxX),
            coordinate,
            Math.clamp(dragged.point().z, bounds.minZ, bounds.maxZ)
         );
         default -> new Vec3(
            Math.clamp(dragged.point().x, bounds.minX, bounds.maxX),
            Math.clamp(dragged.point().y, bounds.minY, bounds.maxY),
            coordinate
         );
      };
      Vec3 normal = selection.axis(dragged.axis()).scale(positive ? 1.0 : -1.0);
      return new OperationGeometry.RayHit(point, normal, dragged.distance(), dragged.axis());
   }
}
