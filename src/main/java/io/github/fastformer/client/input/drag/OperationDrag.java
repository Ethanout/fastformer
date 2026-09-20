package io.github.fastformer.client.input.drag;

import io.github.fastformer.fastplace.geometry.AxisGizmo;
import io.github.fastformer.fastplace.geometry.OperationGeometry;
import net.minecraft.world.phys.Vec3;

/** Immutable state for selection-face and operation-gizmo dragging. */
public record OperationDrag(
   int axis,
   boolean positive,
   DragAxisFrame frame,
   Vec3 normal,
   int sentSteps,
   int mouseButton,
   OperationGeometry.RayHit faceHit,
   AxisGizmo.HandleKey gizmoKey,
   double gizmoBaseValue,
   DeferredDragClick deferredClick,
   long captureToken
) {
   public OperationDrag {
      if (captureToken == 0L) {
         throw new IllegalArgumentException("An operation drag requires a capture token");
      }
   }

   public OperationDrag withFrame(DragAxisFrame value) {
      return new OperationDrag(
         axis, positive, value, normal, sentSteps, mouseButton, faceHit, gizmoKey, gizmoBaseValue, deferredClick, captureToken
      );
   }

   public OperationDrag withSentSteps(int value) {
      return new OperationDrag(
         axis, positive, frame, normal, value, mouseButton, faceHit, gizmoKey, gizmoBaseValue, deferredClick, captureToken
      );
   }

   public OperationDrag withDeferredClick(DeferredDragClick value) {
      return new OperationDrag(
         axis, positive, frame, normal, sentSteps, mouseButton, faceHit, gizmoKey, gizmoBaseValue, value, captureToken
      );
   }
}
