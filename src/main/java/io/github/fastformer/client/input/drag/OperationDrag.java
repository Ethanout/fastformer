package io.github.fastformer.client.input.drag;

import io.github.fastformer.fastplace.geometry.AxisGizmo;
import io.github.fastformer.fastplace.geometry.OperationGeometry;
import io.github.fastformer.network.payload.operation.OperationCallbackScope;
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
   long captureToken,
   long revision,
   OperationCallbackScope callbackScope,
   long requestId
) {
   public OperationDrag {
      if (captureToken == 0L) {
         throw new IllegalArgumentException("An operation drag requires a capture token");
      }
      if (revision < 0 || callbackScope == null || requestId <= 0) throw new IllegalArgumentException("Operation drag requires identity");
   }

   public OperationDrag withFrame(DragAxisFrame value) {
      return new OperationDrag(
         axis, positive, value, normal, sentSteps, mouseButton, faceHit, gizmoKey, gizmoBaseValue, deferredClick, captureToken, revision, callbackScope, requestId
      );
   }

   public OperationDrag withSentSteps(int value) {
      return new OperationDrag(
         axis, positive, frame, normal, value, mouseButton, faceHit, gizmoKey, gizmoBaseValue, deferredClick, captureToken, revision, callbackScope, requestId
      );
   }

   public OperationDrag withDeferredClick(DeferredDragClick value) {
      return new OperationDrag(
         axis, positive, frame, normal, sentSteps, mouseButton, faceHit, gizmoKey, gizmoBaseValue, value, captureToken, revision, callbackScope, requestId
      );
   }
}
